# 第五十九章：DDD 領域驅動設計落地實作

## 前言：DDD 不是架構，是一種語言

**DDD（Domain-Driven Design）** 最大的價值不是技術，而是讓工程師和業務人員說同一種語言。

```
沒有 DDD 的對話：
  業務：「客戶下單後，如果庫存不足，要寄信通知客戶」
  工程師：「好，我在 OrderController 裡調 InventoryService.checkStock()，
           然後 if (stock < quantity) EmailService.sendEmail()」

有 DDD 的對話：
  業務：「客戶下單後，如果庫存不足，要寄信通知客戶」
  工程師：「好，Order Aggregate 在 submit() 時會發出 OrderSubmitted 事件，
           Inventory Context 訂閱後發出 StockInsufficient 事件，
           Notification Context 負責寄信」

→ 工程師的語言和業務的語言對齊了，需求變更時溝通成本大幅降低
```

---

## 一、DDD 核心概念速覽

### 戰略設計（Strategic Design）— 劃分邊界

```
Bounded Context（邊界上下文）：
  每個 Context 有自己的「語言」（Ubiquitous Language）
  同一個詞在不同 Context 可以有不同含義！

  訂單 Context：  Customer = 下單者（email, shipping_address）
  會員 Context：  Customer = 帳號持有者（login, point_balance）
  財務 Context：  Customer = 付款人（credit_card, billing_info）

  → 三個 Context 各自維護自己的 Customer 模型，互不干擾
    Context 之間透過「防腐層」或「事件」溝通
```

```
Context Map（上下文地圖）— 描述各 Context 的關係：

  訂單 Context ──── [Customer/Supplier] ──── 庫存 Context
       │                                          │
  [Conformist]                             [Anti-Corruption Layer]
       │                                          │
  支付 Context                             第三方物流 Context
       │
  [Open Host Service / Published Language]
       │
  對外 REST API（任何呼叫者都能使用的標準介面）
```

### 戰術設計（Tactical Design）— 設計模型

| 概念 | 說明 | 例子 |
|------|------|------|
| **Entity** | 有唯一 ID、有生命週期的物件 | Order（有 orderId）|
| **Value Object** | 無 ID、靠值相等判斷、不可變 | Money(100, "TWD")、Address |
| **Aggregate** | Entity 的群組，只通過 Aggregate Root 存取 | Order 是 Root，OrderItem 是內部 |
| **Repository** | 存取 Aggregate 的唯一管道（像 Collection）| OrderRepository |
| **Domain Service** | 跨 Aggregate 的業務邏輯（不屬於任何一個 Entity）| PricingService |
| **Domain Event** | Aggregate 狀態改變後發出的事件 | OrderSubmittedEvent |
| **Factory** | 建立複雜 Aggregate 的工廠 | OrderFactory |
| **Application Service** | 用例協調者，不含業務邏輯 | OrderApplicationService |

---

## 二、Value Object：讓程式碼說業務語言

```java
// ❌ 沒有 Value Object 的寫法
public class Order {
    private String currency;     // 誰知道合法值是什麼？
    private double amount;       // double 有精度問題！
    private String status;       // 是哪些狀態？
}

// ✅ Value Object 的寫法
public class Order {
    private Money totalAmount;   // Money 自帶單位，自帶精度保證
    private OrderStatus status;  // enum，編譯時就知道有哪些狀態
    private CustomerId customerId; // 強型別，不會把 productId 傳進來
}

// Money Value Object
@Embeddable  // JPA 嵌入型
public class Money {
    private final BigDecimal amount;    // 用 BigDecimal！
    private final Currency currency;   // java.util.Currency

    public Money(BigDecimal amount, Currency currency) {
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("金額不能為負數");
        }
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);  // 統一精度
        this.currency = currency;
    }

    public Money add(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new DomainException("不同幣別不能相加");
        }
        return new Money(this.amount.add(other.amount), this.currency);
    }

    // Value Object 相等靠值，不靠 identity
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Money)) return false;
        Money m = (Money) o;
        return this.amount.compareTo(m.amount) == 0
            && this.currency.equals(m.currency);
    }
    // 必須同時覆寫 hashCode
}
```

---

## 三、Aggregate：業務不變量的守護者

**Aggregate 的核心職責**：保證自己內部的「業務不變量」（Invariant）始終成立。

```java
// Order Aggregate Root
public class Order {

    private OrderId id;
    private CustomerId customerId;
    private OrderStatus status;
    private Money totalAmount;
    private List<OrderItem> items = new ArrayList<>();  // OrderItem 是 Order 的一部分

    // 工廠方法：建立新訂單（業務邏輯在 Aggregate 裡，不在 Service 裡）
    public static Order create(CustomerId customerId) {
        Order order = new Order();
        order.id = OrderId.generate();
        order.customerId = customerId;
        order.status = OrderStatus.DRAFT;
        order.totalAmount = Money.ZERO_TWD;
        return order;
    }

    // 業務操作：加入商品
    public void addItem(ProductId productId, String productName,
                        Money unitPrice, int quantity) {
        // 業務不變量：只有 DRAFT 狀態才能加商品
        if (this.status != OrderStatus.DRAFT) {
            throw new DomainException("訂單已提交，無法再加入商品");
        }
        // 業務不變量：最多 20 個商品
        if (this.items.size() >= 20) {
            throw new DomainException("訂單商品數量超過上限（20件）");
        }

        OrderItem item = new OrderItem(productId, productName, unitPrice, quantity);
        this.items.add(item);
        this.recalculateTotal();  // 同步更新總金額
    }

    // 業務操作：提交訂單
    public OrderSubmittedEvent submit() {
        if (this.items.isEmpty()) {
            throw new DomainException("訂單不能沒有商品");
        }
        if (this.status != OrderStatus.DRAFT) {
            throw new DomainException("訂單狀態不允許提交");
        }
        this.status = OrderStatus.SUBMITTED;
        // 發出 Domain Event（由 Application Service 發布）
        return new OrderSubmittedEvent(this.id, this.customerId, this.totalAmount);
    }

    private void recalculateTotal() {
        this.totalAmount = items.stream()
            .map(OrderItem::getSubtotal)
            .reduce(Money.ZERO_TWD, Money::add);
    }
}

// OrderItem：Order Aggregate 的內部 Entity
// 注意：外部不能直接 new OrderItem，只能通過 Order.addItem() 建立
class OrderItem {
    private OrderItemId id;
    private ProductId productId;
    private String productName;
    private Money unitPrice;
    private int quantity;

    // 包級別或同 package，防止外部直接建立
    OrderItem(ProductId productId, String productName, Money unitPrice, int quantity) {
        if (quantity <= 0) throw new DomainException("數量必須大於 0");
        this.id = OrderItemId.generate();
        // ...
    }

    public Money getSubtotal() {
        return unitPrice.multiply(quantity);
    }
}
```

### Aggregate 設計原則

```
1. 一個 Aggregate 只有一個 Root Entity
2. 外部只能持有 Aggregate Root 的 ID（不能持有內部 Entity 的引用）
3. Aggregate 內的所有變更都透過 Root 的方法進行（保護業務不變量）
4. Aggregate 應該盡量小（只包含需要同時保持一致的部分）

❌ 太大的 Aggregate（反模式）：
   Order 裡包含 Customer 完整資訊 + Product 完整資訊
   → 並發衝突多，性能差

✅ 正確設計：
   Order 裡只存 CustomerId 和 ProductId（ID 引用）
   需要顯示時再從各自的 Repository 查
```

---

## 四、Domain Event：讓 Aggregate 解耦

```java
// Domain Event 的定義（不可變的值物件）
public record OrderSubmittedEvent(
    OrderId orderId,
    CustomerId customerId,
    Money totalAmount,
    Instant occurredAt   // 事件發生時間（用於事件溯源）
) implements DomainEvent {
    public OrderSubmittedEvent(OrderId orderId, CustomerId customerId, Money totalAmount) {
        this(orderId, customerId, totalAmount, Instant.now());
    }
}

// Application Service：協調 Aggregate + 發布事件（不含業務邏輯！）
@Service
@Transactional
public class OrderApplicationService {

    private final OrderRepository orderRepository;
    private final ApplicationEventPublisher eventPublisher;  // Spring 的事件發布

    public OrderId submitOrder(SubmitOrderCommand command) {
        // 1. 從 Repository 取得 Aggregate
        Order order = orderRepository.findById(command.orderId())
            .orElseThrow(() -> new OrderNotFoundException(command.orderId()));

        // 2. 讓 Aggregate 執行業務邏輯（Application Service 不做業務判斷）
        OrderSubmittedEvent event = order.submit();

        // 3. 儲存 Aggregate 的狀態
        orderRepository.save(order);

        // 4. 發布 Domain Event（其他 Bounded Context 訂閱後自行處理）
        eventPublisher.publishEvent(event);

        return order.getId();
    }
}

// 庫存 Context 訂閱事件（在自己的 Context 內處理）
@Component
public class InventoryEventHandler {

    @EventListener
    @Async  // 非同步，不阻塞主流程
    public void onOrderSubmitted(OrderSubmittedEvent event) {
        // 庫存 Context 不直接操作 Order，只透過事件知道有訂單提交
        inventoryService.reserveStock(event.orderId(), event.items());
    }
}
```

---

## 五、Repository 模式 <!-- 💡 進階 -->

```java
// Repository Interface：在 Domain 層定義（只知道業務操作）
public interface OrderRepository {
    Optional<Order> findById(OrderId id);
    void save(Order order);
    void delete(OrderId id);

    // 業務查詢（用業務語言命名）
    List<Order> findPendingOrdersOlderThan(Duration duration);
    Optional<Order> findByIdempotencyKey(String key);
}

// Repository 實作：在 Infrastructure 層（處理 JPA/DB 細節）
@Repository
public class JpaOrderRepository implements OrderRepository {

    private final OrderJpaRepository jpaRepo;  // Spring Data JPA

    @Override
    public Optional<Order> findById(OrderId id) {
        return jpaRepo.findById(id.value())
            .map(OrderMapper::toDomain);   // JPA Entity → Domain Aggregate
    }

    @Override
    public void save(Order order) {
        OrderEntity entity = OrderMapper.toEntity(order);
        jpaRepo.save(entity);
    }

    @Override
    public List<Order> findPendingOrdersOlderThan(Duration duration) {
        LocalDateTime cutoff = LocalDateTime.now().minus(duration);
        return jpaRepo.findByStatusAndCreatedAtBefore("SUBMITTED", cutoff)
            .stream().map(OrderMapper::toDomain).toList();
    }
}
```

---

## 六、防腐層（Anti-Corruption Layer）<!-- 💡 進階 -->

當你需要整合遺留系統或第三方服務時，防腐層保護你的 Domain 模型不被污染：

```java
// 第三方物流系統的 API（你無法控制的外部模型）
public class ThirdPartyShippingAPI {
    public ShipmentResponse createShipment(ShipmentRequest req) { ... }
    // 它用自己的命名和結構，可能很醜陋
}

// 防腐層：把外部世界翻譯成我們的 Domain 語言
@Component
public class ShippingServiceAdapter {  // 防腐層

    private final ThirdPartyShippingAPI externalApi;

    // Domain 層呼叫這個方法（用 Domain 語言）
    public TrackingNumber ship(Order order, ShippingAddress address) {
        // 翻譯：把 Domain 物件轉成外部 API 需要的格式
        ShipmentRequest request = ShipmentRequest.builder()
            .recipientName(address.getFullName())
            .recipientAddress(address.getStreet() + "," + address.getCity())
            .packageWeight(calculateWeight(order))
            .build();

        ShipmentResponse response = externalApi.createShipment(request);

        // 翻譯：把外部 API 的回應轉成 Domain Value Object
        return new TrackingNumber(response.getTrackingCode());
    }
}
```

---

## 七、DDD 分層架構

```
┌──────────────────────────────────────┐
│  Interface Layer（介面層）             │  REST Controller / GraphQL / gRPC
│  只處理輸入輸出格式轉換                │  Request DTO → Command，Response DTO ← VO
├──────────────────────────────────────┤
│  Application Layer（應用層）           │  ApplicationService
│  用例協調者，不含業務邏輯              │  @Transactional，調用 Domain + 發事件
├──────────────────────────────────────┤
│  Domain Layer（領域層）                │  Entity / Aggregate / Value Object
│  業務邏輯的核心，不依賴任何框架        │  Domain Service / Domain Event
├──────────────────────────────────────┤
│  Infrastructure Layer（基礎設施層）    │  Repository 實作 / MQ / Cache / 第三方
│  技術細節，實作 Domain 定義的介面     │  JPA Entity / Mapper / ACL（防腐層）
└──────────────────────────────────────┘

依賴方向：外層依賴內層，Domain Layer 不依賴任何框架！
```

---

## 本章練習題

**Q1：Value Object 和 Entity 最根本的區別是什麼？**
<details>
<summary>答案</summary>
Entity 靠「唯一 ID」來識別身份，即使兩個 Entity 所有欄位都相同，只要 ID 不同，它們就是不同的東西（例如兩張面額相同的鈔票，序號不同就是不同的鈔票）。Value Object 靠「值」來識別身份，沒有 ID 概念，兩個 Value Object 只要所有屬性相同就視為相等（例如 Money(100, "TWD") 和另一個 Money(100, "TWD") 是完全一樣的東西，可以互換）。因此 Value Object 應該是不可變的（Immutable），修改時要建立新物件。
</details>

**Q2：Aggregate 為什麼應該盡量小？**
<details>
<summary>答案</summary>
Aggregate 是一致性邊界：修改 Aggregate 內部的任何東西，整個 Aggregate 都要在同一個事務裡儲存。如果 Aggregate 太大（例如 Order 裡包含完整的 Customer 和 Product 資料），那麼每次修改訂單，都要鎖住 Customer 和 Product 的資料，並發時鎖競爭嚴重，性能差。而且不同用戶的訂單本來可以並發處理，但如果都鎖同一個 Customer，就變成串行了。正確設計是 Aggregate 只包含需要「同時保持一致的最小邊界」，Order 裡只存 CustomerId，需要 Customer 資料時再查。
</details>

**Q3：Application Service 和 Domain Service 的差別是什麼？**
<details>
<summary>答案</summary>
Application Service（應用服務）是「用例協調者」，負責編排流程：從 Repository 取 Aggregate、呼叫 Domain 的方法、儲存、發送事件。它不含業務邏輯，換一個用例框架（從 REST 換成 gRPC）應該只需改 Application Service 的輸入輸出，不改業務邏輯。Domain Service（領域服務）含有業務邏輯，但這個邏輯不屬於任何單一 Aggregate（例如「轉帳」需要同時操作兩個 Account Aggregate，這個操作本身就是 Domain Service 的範疇）。簡單記：有「if 業務規則」的邏輯 = Domain；有「取 A、呼叫 B、存 C、發 D」的流程編排 = Application。
</details>
