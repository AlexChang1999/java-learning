# 第六十章：CQRS 與事件溯源（Event Sourcing）

## 前言：為什麼傳統 CRUD 不夠用？

```
傳統 CRUD 系統的問題：

問題 1：讀寫用同一個模型
  → 訂單查詢需要 JOIN 5 張表，每次下單也要更新這 5 張表
  → 讀的最佳化（反正規化）和寫的最佳化（正規化）互相衝突

問題 2：「過去發生了什麼」無從追查
  → 訂單金額從 1000 改成 800，為什麼改的？誰改的？
  → 只剩最新狀態，歷史快照消失了

問題 3：Bug 導致資料損壞
  → 無法回到 Bug 發生前的狀態重放，損失不可逆

CQRS 和 Event Sourcing 分別解決這兩個問題。
```

---

## 一、CQRS：命令查詢職責分離

**CQRS（Command Query Responsibility Segregation）** 把讀和寫分成兩個獨立的模型：

```
Command Side（寫側）：                Query Side（讀側）：
  接收「命令」（改變狀態的操作）          接收「查詢」（不改變狀態的操作）
  CreateOrder, SubmitOrder…             GetOrderDetail, ListOrders…
  寫入正規化的 DB（保證一致性）           讀取反正規化的 Read Model（效能最佳）
       │                                        ↑
       │         事件 / 最終一致              │
       └──── OrderSubmittedEvent ──────────────┘
```

### 實作：Command Side（寫側）

```java
// Command：不可變的值物件，描述「做什麼」
public record SubmitOrderCommand(
    String orderId,
    String userId
) {}

// Command Handler：處理命令，調用 Aggregate
@Component
public class OrderCommandHandler {

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void handle(SubmitOrderCommand command) {
        Order order = orderRepository.findById(new OrderId(command.orderId()))
            .orElseThrow(() -> new OrderNotFoundException(command.orderId()));

        // Aggregate 執行業務邏輯，返回 Domain Event
        OrderSubmittedEvent event = order.submit();

        orderRepository.save(order);
        eventPublisher.publishEvent(event);
    }
}
```

### 實作：Query Side（讀側）

```java
// Read Model：專門為查詢最佳化的「扁平化」視圖
// 通常是非正規化的，可以直接查，不需要 JOIN
@Entity
@Table(name = "order_summary_view")  // 讀側有自己的 Table
public class OrderSummaryView {
    private String orderId;
    private String customerId;
    private String customerName;   // 反正規化：直接存 Customer 名稱
    private String customerEmail;  // 避免 JOIN customer 表
    private String status;
    private BigDecimal totalAmount;
    private int itemCount;
    private LocalDateTime createdAt;
}

// Query Handler：只做查詢，不做業務判斷
@Service
public class OrderQueryService {

    private final OrderSummaryViewRepository viewRepo;

    // 根據業務需求設計查詢介面
    public OrderSummaryView getOrderDetail(String orderId) {
        return viewRepo.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    public Page<OrderSummaryView> listOrders(String customerId, Pageable pageable) {
        return viewRepo.findByCustomerId(customerId, pageable);
    }
}

// 讀側 Projector：訂閱事件，更新 Read Model
@Component
public class OrderProjector {

    private final OrderSummaryViewRepository viewRepo;
    private final CustomerRepository customerRepo;

    @EventListener
    @Async
    public void on(OrderSubmittedEvent event) {
        // 收到訂單提交事件，更新讀側 View
        OrderSummaryView view = viewRepo.findById(event.orderId().value())
            .orElseGet(() -> new OrderSummaryView(event.orderId().value()));

        // 從 Customer Repository 取得最新名稱（反正規化存入）
        Customer customer = customerRepo.findById(event.customerId());
        view.setCustomerName(customer.getName());
        view.setCustomerEmail(customer.getEmail());
        view.setStatus("SUBMITTED");
        view.setTotalAmount(event.totalAmount().getAmount());

        viewRepo.save(view);
    }
}
```

### 什麼時候用 CQRS？

```
✅ 適合的場景：
  - 讀寫比例嚴重不對稱（讀 >> 寫，如社群平台）
  - 讀側需要複雜聚合查詢，寫側需要嚴格業務規則
  - 需要讀側水平擴展（讀側資料庫可以有多個副本）
  - 配合 Event Sourcing 使用

❌ 不適合的場景：
  - 簡單的 CRUD 系統（增加大量複雜性，得不償失）
  - 讀寫比例接近的系統
  - 對最終一致性無法接受的場景（寫完立刻查，可能還沒同步）
```

---

## 二、Event Sourcing：用事件記錄歷史

傳統系統儲存「最新狀態」，Event Sourcing 儲存「所有發生過的事件」：

```
傳統 CRUD：
  orders 表：{ id: "O1", status: "SHIPPED", total: 800 }
  → 現在是什麼狀態
  → 不知道從 1000 改成 800 的原因

Event Sourcing：
  events 表：
    1. OrderCreated    { orderId: "O1", customerId: "C1" }
    2. ItemAdded       { orderId: "O1", productId: "P1", price: 500, qty: 2 }
    3. DiscountApplied { orderId: "O1", discountCode: "VIP20", amount: 200 }
    4. OrderSubmitted  { orderId: "O1", totalAmount: 800 }
  → 完整的歷史記錄，知道每一步發生了什麼
  → 「重放」所有事件可以還原任何時間點的狀態
```

### Event Store 設計

```java
// 事件儲存的表結構
CREATE TABLE domain_events (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id    VARCHAR(36) NOT NULL UNIQUE,    -- UUID，冪等
    aggregate_id    VARCHAR(50) NOT NULL,        -- 哪個 Aggregate
    aggregate_type  VARCHAR(50) NOT NULL,        -- 哪種類型（Order, Payment...）
    event_type      VARCHAR(100) NOT NULL,       -- 事件類型
    event_version   INT NOT NULL,               -- Aggregate 的版本號（用於樂觀鎖）
    payload         JSON NOT NULL,               -- 事件內容（序列化的事件物件）
    occurred_at     DATETIME(3) NOT NULL,        -- 事件發生時間（毫秒精度）
    metadata        JSON,                        -- 附加資訊（userId, correlationId...）
    INDEX idx_aggregate (aggregate_id, event_version)
);
```

### 讀取並重建 Aggregate 狀態

```java
// Aggregate 不直接持有狀態，而是「重放事件」來還原狀態
public class Order {
    private OrderId id;
    private OrderStatus status;
    private Money totalAmount;
    private int version = 0;  // 用於樂觀鎖
    private List<DomainEvent> pendingEvents = new ArrayList<>();  // 待發布的新事件

    // ===== 狀態還原（Apply 方法，不做業務驗證）=====
    public void apply(OrderCreatedEvent event) {
        this.id = event.orderId();
        this.status = OrderStatus.DRAFT;
        this.totalAmount = Money.ZERO_TWD;
        this.version++;
    }

    public void apply(OrderSubmittedEvent event) {
        this.status = OrderStatus.SUBMITTED;
        this.totalAmount = event.totalAmount();
        this.version++;
    }

    // ===== 業務操作（先驗證，再記錄事件）=====
    public void submit() {
        // 業務驗證
        if (this.status != OrderStatus.DRAFT) {
            throw new DomainException("只有草稿訂單才能提交");
        }

        // 記錄事件（不直接改狀態，透過 apply 改）
        OrderSubmittedEvent event = new OrderSubmittedEvent(this.id, this.totalAmount);
        apply(event);                          // 立刻改狀態
        pendingEvents.add(event);              // 待發布
    }

    // 從事件流重建 Aggregate（靜態工廠方法）
    public static Order reconstitute(List<DomainEvent> events) {
        Order order = new Order();
        for (DomainEvent event : events) {
            if (event instanceof OrderCreatedEvent e) order.apply(e);
            else if (event instanceof OrderSubmittedEvent e) order.apply(e);
            // ...
        }
        return order;
    }
}

// Repository 實作：從 Event Store 重建 Aggregate
@Repository
public class EventSourcedOrderRepository implements OrderRepository {

    private final DomainEventStore eventStore;

    @Override
    public Optional<Order> findById(OrderId id) {
        List<DomainEvent> events = eventStore.loadEvents(id.value());
        if (events.isEmpty()) return Optional.empty();
        return Optional.of(Order.reconstitute(events));
    }

    @Override
    @Transactional
    public void save(Order order) {
        // 只儲存「新產生的事件」（pendingEvents）
        List<DomainEvent> newEvents = order.getPendingEvents();
        eventStore.append(order.getId().value(), newEvents, order.getVersion());
        order.clearPendingEvents();
    }
}
```

---

## 三、Snapshot：解決長事件流的效能問題 <!-- 💡 進階 -->

訂單可能有幾百個事件，每次重建都要讀幾百條記錄：

```java
// 每 N 個事件存一份快照
public class SnapshotStore {

    private static final int SNAPSHOT_THRESHOLD = 50;  // 每 50 個事件建一個快照

    public void maybeSaveSnapshot(Order order) {
        if (order.getVersion() % SNAPSHOT_THRESHOLD == 0) {
            OrderSnapshot snapshot = new OrderSnapshot(
                order.getId().value(),
                order.getVersion(),
                serializeState(order)   // 序列化當前狀態
            );
            snapshotRepository.save(snapshot);
        }
    }

    public Order loadWithSnapshot(OrderId id) {
        // 1. 嘗試找最新快照
        Optional<OrderSnapshot> snapshot = snapshotRepository
            .findLatestByAggregateId(id.value());

        if (snapshot.isPresent()) {
            // 2. 從快照版本開始，只重放後續的事件
            Order order = deserializeSnapshot(snapshot.get());
            List<DomainEvent> recentEvents = eventStore
                .loadEventsAfterVersion(id.value(), snapshot.get().getVersion());
            recentEvents.forEach(e -> apply(order, e));
            return order;
        }

        // 3. 沒有快照：從頭重放
        return Order.reconstitute(eventStore.loadEvents(id.value()));
    }
}
```

---

## 四、CQRS + Event Sourcing 組合

```
完整架構圖：

Client
  │
  ├── POST /orders/submit  ──→  OrderCommandHandler
  │                                   │
  │                             Order.submit()
  │                                   │
  │                         EventStore.append(events)
  │                                   │
  │                         ApplicationEventPublisher
  │                              ↙         ↘
  │                    OrderProjector    InventoryHandler
  │                         │
  │                  UpdateReadModel (最終一致)
  │
  └── GET /orders/{id}  ──→  OrderQueryService
                                   │
                             ReadModel DB（反正規化）
                                   │
                             OrderSummaryView
```

---

## 本章練習題

**Q1：Event Sourcing 說「重放事件可以還原任何時間點的狀態」，這有什麼實際用途？**
<details>
<summary>答案</summary>
用途 1（Bug 重現與修復）：發現某個訂單金額計算有 Bug，可以找到 Bug 發生的時間點，重放該時間點之前的所有事件，用修復後的邏輯重新計算，得出正確結果。傳統 CRUD 只有最後狀態，Bug 之前的狀態已經被覆蓋掉了。用途 2（時光機查詢）：「這筆訂單在 2024-01-15 09:30:00 的狀態是什麼？」只需要重放到那個時間戳之前的事件即可。用途 3（監管合規）：金融系統必須保存完整的操作記錄，Event Store 天然滿足這個要求。用途 4（新功能的讀側視圖）：新加一個統計報表，只需要建一個新的 Projector 從頭重放所有歷史事件，就能建構出完整的統計資料，不需要寫複雜的 migration script。
</details>

**Q2：CQRS 中，讀側的資料因為是「最終一致」，用戶下單後立刻刷新頁面可能看不到新訂單，怎麼解決？**
<details>
<summary>答案</summary>
方案 1（Read-Your-Own-Writes）：下單成功後，前端先用 CommandId 或 orderId 直接查 Write Side 的資料（繞過讀側），確保用戶看到自己剛寫入的資料。方案 2（版本號等待）：Command 成功後返回一個版本號，前端輪詢讀側，等到讀側版本號趕上再顯示。方案 3（樂觀 UI 更新）：前端不等後端，立刻把新訂單加入 UI（Optimistic Update），後端確認後再驗證。方案 4（延長一致性窗口）：確保事件處理足夠快（毫秒級），用戶感知不到延遲。在大多數場景下，100ms 以內的延遲用戶根本感覺不到。
</details>

**Q3：傳統 CRUD 系統遷移到 CQRS 的第一步應該怎麼做？**
<details>
<summary>答案</summary>
不要一次全部重寫（大爆炸風險極高）。遷移策略：Step 1 — 分離讀寫介面，在現有 Service 層引入 CommandService 和 QueryService，還是操作同一個資料庫，但邏輯開始分離。Step 2 — 建立 Read Model，在現有 DB 旁邊建一個用於查詢的視圖/快取（可以用 Redis 或額外的 Table），從現有 DB 同步數據，讓查詢走 Read Model。Step 3 — 逐步引入事件，讓寫側在儲存後發布事件，讀側訂閱事件更新 Read Model，逐步解耦。每一步都可以上線驗證，避免整個系統不可用的風險。
</details>
