# 第二十七章：Spring Data JPA

> **閱讀前提**：你已讀過第十九章（JDBC 基礎）與第二十一章（Spring Boot）。  
> **核心專案**：本章用「撮合引擎」的訂單與成交資料貫穿所有範例。

---

## 一、為什麼從 JDBC 進化到 JPA？

### 1-1 JDBC 的痛點

先回顧你在第十九章寫的 JDBC 程式碼長什麼樣子：

```java
// 第十九章的舊寫法：用 JDBC 查詢一筆訂單
public Order findById(long id) throws SQLException {
    String sql = "SELECT id, symbol, side, price, quantity, status FROM orders WHERE id = ?";
    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {

        ps.setLong(1, id);                          // 手動設定參數
        ResultSet rs = ps.executeQuery();

        if (rs.next()) {
            Order order = new Order();
            order.setId(rs.getLong("id"));           // 手動一欄一欄取值
            order.setSymbol(rs.getString("symbol"));
            order.setSide(rs.getString("side"));
            order.setPrice(rs.getBigDecimal("price"));
            order.setQuantity(rs.getLong("quantity"));
            order.setStatus(rs.getString("status"));
            return order;
        }
        return null;
    }
}
```

光是查一筆訂單就要寫超過 15 行。問題清單如下：

| 痛點 | 說明 |
|------|------|
| **手寫 SQL 字串** | 字串拼接容易 typo，IDE 無法幫你檢查 |
| **手動欄位映射** | 每個 `rs.getString(...)` 都可能拼錯欄位名稱 |
| **大量重複程式碼** | findById、findAll、insert、update、delete 幾乎每個 DAO 都長一樣 |
| **Connection 管理** | 每次都要記得 close，try-with-resources 很囉嗦 |
| **型別轉換** | SQL 的 DECIMAL 對應 Java 的 BigDecimal，每次都要記 |

當撮合引擎有 10 個 DAO（Order、Trade、Account、Position…），這份痛苦乘以 10 倍。

---

### 1-2 ORM 的核心思想

ORM = **Object-Relational Mapping**，物件關聯映射。  
核心概念只有一句話：**讓 Java 物件自動對應到資料庫表格，省掉中間的手工翻譯。**

```
  Java 世界                         資料庫世界
  ─────────────────────────         ──────────────────────
  
  類別 (Class)           ←→         表格 (Table)
  
    Order                ←→           orders
    ├── Long id          ←→           ├── BIGINT id
    ├── String symbol    ←→           ├── VARCHAR symbol
    ├── BigDecimal price ←→           ├── DECIMAL price
    └── String status    ←→           └── VARCHAR status
    
  物件實例 (Object)       ←→         一列資料 (Row)
  
    new Order(           ←→           | 1 | BTC | BUY |
      id=1,                           | 50000.00 | OPEN |
      symbol="BTC",
      side="BUY",
      price=50000.00,
      status="OPEN"
    )
    
  欄位 (Field)           ←→         欄位 (Column)
```

ORM 框架幫你做中間的翻譯工作，你只需要操作 Java 物件，框架自動產生 SQL。

---

### 1-3 JPA 是規範，Hibernate 是實作

這個區別很重要，不要搞混：

```
  ┌─────────────────────────────────────────────────┐
  │              你寫的程式碼                          │
  │  @Entity  @Repository  @Transactional  @Query    │
  └──────────────────────┬──────────────────────────┘
                         │  使用 JPA API（javax.persistence）
  ┌──────────────────────▼──────────────────────────┐
  │           JPA（規範 / 介面）                       │
  │   Jakarta Persistence API — 只定義「要做什麼」     │
  └──────────────────────┬──────────────────────────┘
                         │  實作
  ┌──────────────────────▼──────────────────────────┐
  │         Hibernate（實作 / 引擎）                   │
  │   真正產生 SQL、管理 Session、處理快取             │
  └─────────────────────────────────────────────────┘
```

- **JPA**：定義了 `@Entity`、`EntityManager`、JPQL 等 API，是一份規範文件。
- **Hibernate**：實際執行的 ORM 引擎，Spring Boot 預設內建。
- **Spring Data JPA**：在 Hibernate 上面再加一層，讓你連 `EntityManager` 都不用碰，直接寫 `Repository` 介面就好。

**在撮合引擎中**：你的 `Order`、`Trade` 物件就是 JPA Entity，Hibernate 負責把它們存進 MySQL。

---

## 二、實體類別（Entity）設計

### 2-1 基礎 Annotation 說明

```java
import jakarta.persistence.*;  // JPA 的 Annotation 都在這個套件
import java.math.BigDecimal;
import java.time.LocalDateTime;

// @Entity 告訴 JPA：這個類別對應到資料庫的一張表
@Entity
// @Table 指定表格名稱；不加的話預設用類別名稱的小寫
@Table(name = "orders")
public class Order {

    // @Id 標記主鍵欄位
    @Id
    // @GeneratedValue 設定主鍵自動產生的策略
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @Column 細調欄位設定；不加的話欄位名稱預設等於變數名稱
    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;       // 交易標的，例如 "BTC/USDT"

    @Column(name = "side", nullable = false, length = 4)
    private String side;         // 買賣方向："BUY" 或 "SELL"

    // DECIMAL(18,8) 足夠存加密貨幣的精確價格
    @Column(name = "price", nullable = false, precision = 18, scale = 8)
    private BigDecimal price;    // 限價單的委託價格

    @Column(name = "quantity", nullable = false)
    private Long quantity;       // 委託數量（以最小單位計）

    @Column(name = "filled_quantity")
    private Long filledQuantity = 0L;  // 已成交數量，預設為 0

    @Column(name = "status", nullable = false, length = 10)
    private String status;       // 訂單狀態：OPEN / PARTIAL / FILLED / CANCELLED

    // 使用 LocalDateTime 存入時間，JPA 自動對應 DATETIME 欄位
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // 必要的無參數建構子（JPA 規範要求）
    protected Order() {}

    // 你自己的建構子
    public Order(String symbol, String side, BigDecimal price, Long quantity) {
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.status = "OPEN";
        this.createdAt = LocalDateTime.now();
    }

    // Getter / Setter（省略，實際要寫完整）
}
```

---

### 2-2 主鍵策略比較

`@GeneratedValue(strategy = ?)` 有四個選項，底層行為差異很大：

| 策略 | 說明 | 底層行為 | 適用場景 |
|------|------|----------|----------|
| `AUTO` | JPA 自動選擇 | 依資料庫決定，MySQL 通常用 TABLE 策略 | 不建議，行為不可預測 |
| `IDENTITY` | 依賴資料庫自增 | MySQL `AUTO_INCREMENT`，INSERT 後取回 ID | **MySQL 首選** |
| `SEQUENCE` | 用資料庫 Sequence | 先 `SELECT NEXTVAL`，再 INSERT | PostgreSQL 首選，可批次取號 |
| `TABLE` | 用獨立的流水號表 | 需要額外一張 `hibernate_sequence` 表 | 相容性最好，但效能最差 |

**撮合引擎建議**：用 `IDENTITY`，設定簡單，MySQL 原生支援，高頻插入時效能夠用。

```java
// 撮合引擎的 Trade（成交紀錄）實體
@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 關聯到買方訂單 ID（暫時先用 Long，第四節再談 @ManyToOne）
    @Column(name = "buy_order_id", nullable = false)
    private Long buyOrderId;

    // 關聯到賣方訂單 ID
    @Column(name = "sell_order_id", nullable = false)
    private Long sellOrderId;

    @Column(name = "symbol", nullable = false, length = 20)
    private String symbol;

    @Column(name = "price", nullable = false, precision = 18, scale = 8)
    private BigDecimal price;       // 實際成交價格

    @Column(name = "quantity", nullable = false)
    private Long quantity;          // 成交數量

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected Trade() {}

    public Trade(Long buyOrderId, Long sellOrderId,
                 String symbol, BigDecimal price, Long quantity) {
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.symbol = symbol;
        this.price = price;
        this.quantity = quantity;
        this.createdAt = LocalDateTime.now();
    }

    // Getter / Setter 省略
}
```

---

## 三、Repository 介面（核心！）

### 3-1 繼承層次

Spring Data JPA 的精華就是 `JpaRepository`。你只需要宣告一個介面，Spring 在啟動時自動產生實作類別。

```
  你宣告的介面
  OrderRepository
       │ extends
       ▼
  JpaRepository<Order, Long>        ← 提供 CRUD + 分頁 + 排序
       │ extends
       ▼
  PagingAndSortingRepository        ← 提供 findAll(Pageable)、findAll(Sort)
       │ extends
       ▼
  CrudRepository<Order, Long>       ← 提供 save、findById、findAll、delete
       │ extends
       ▼
  Repository<Order, Long>           ← 最頂層的標記介面（空介面）
```

`JpaRepository<T, ID>` 的泛型參數：
- `T`：Entity 類別，例如 `Order`
- `ID`：主鍵的型別，例如 `Long`

### 3-2 不用寫 SQL 就能用的方法

```java
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

// @Repository 標記這是資料存取層（可省略，Spring Data 自動偵測）
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {
    // 介面體可以是空的！Spring 自動提供以下所有方法：
    //
    //  save(Order order)            → INSERT 或 UPDATE（有 id 就 UPDATE）
    //  saveAll(List<Order> orders)  → 批次儲存
    //  findById(Long id)            → 回傳 Optional<Order>
    //  findAll()                    → 回傳所有訂單（小心！上線不要用）
    //  findAll(Pageable pageable)   → 分頁查詢
    //  count()                      → SELECT COUNT(*)
    //  deleteById(Long id)          → DELETE WHERE id = ?
    //  existsById(Long id)          → SELECT COUNT(*) > 0
}
```

使用方式：

```java
@Service
public class OrderService {

    // Spring 自動注入，不需要你寫 new OrderRepositoryImpl()
    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Order placeOrder(String symbol, String side,
                             BigDecimal price, Long quantity) {
        Order order = new Order(symbol, side, price, quantity);
        return orderRepository.save(order);  // 自動 INSERT，回傳含 id 的物件
    }

    public Optional<Order> getOrder(Long id) {
        return orderRepository.findById(id);  // 自動 SELECT WHERE id = ?
    }
}
```

---

### 3-3 方法命名規則（Derived Query）

Spring Data JPA 能根據方法名稱**自動推導 SQL**，這是它最神奇的功能之一。

規則：`findBy` + 欄位名稱 + 條件關鍵字

```java
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // 生成：SELECT * FROM orders WHERE symbol = ?
    List<Order> findBySymbol(String symbol);

    // 生成：SELECT * FROM orders WHERE status = ?
    List<Order> findByStatus(String status);

    // 生成：SELECT * FROM orders WHERE symbol = ? AND side = ?
    List<Order> findBySymbolAndSide(String symbol, String side);

    // 生成：SELECT * FROM orders WHERE status = ? AND side = ?
    //       ORDER BY created_at DESC
    List<Order> findByStatusAndSideOrderByCreatedAtDesc(String status, String side);

    // 生成：SELECT * FROM orders WHERE price >= ? AND price <= ?
    List<Order> findByPriceBetween(BigDecimal min, BigDecimal max);

    // 生成：SELECT * FROM orders WHERE status IN ('OPEN', 'PARTIAL')
    List<Order> findByStatusIn(List<String> statuses);

    // 生成：SELECT COUNT(*) FROM orders WHERE symbol = ?
    long countBySymbol(String symbol);

    // 生成：SELECT * FROM orders WHERE status = 'OPEN'
    //       LIMIT 1  (只取第一筆)
    Optional<Order> findFirstByStatusOrderByCreatedAtAsc(String status);
}
```

常用關鍵字對照表：

| 方法名稱關鍵字 | SQL 等效 |
|--------------|----------|
| `And` | `AND` |
| `Or` | `OR` |
| `Between` | `BETWEEN ? AND ?` |
| `LessThan` | `< ?` |
| `GreaterThan` | `> ?` |
| `Like` | `LIKE ?` |
| `In` | `IN (...)` |
| `IsNull` | `IS NULL` |
| `OrderBy...Asc/Desc` | `ORDER BY ... ASC/DESC` |
| `Top3` / `First3` | `LIMIT 3` |

---

### 3-4 自訂 JPQL 查詢

當方法名稱太長、或邏輯複雜，用 `@Query` 寫 JPQL（不是 SQL！）：

```java
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // JPQL：FROM 後面寫的是類別名稱 Order，不是表格名稱 orders
    // 參數用 :symbol 具名參數，對應 @Param("symbol")
    @Query("SELECT o FROM Order o WHERE o.symbol = :symbol AND o.status IN ('OPEN', 'PARTIAL')")
    List<Order> findActiveOrders(@Param("symbol") String symbol);

    // 撮合引擎常用：找到價格最優的賣單（賣方價格最低的先成交）
    @Query("SELECT o FROM Order o " +
           "WHERE o.symbol = :symbol " +
           "  AND o.side = 'SELL' " +
           "  AND o.status IN ('OPEN', 'PARTIAL') " +
           "  AND o.price <= :buyPrice " +
           "ORDER BY o.price ASC, o.createdAt ASC")
    List<Order> findMatchableSellOrders(@Param("symbol") String symbol,
                                        @Param("buyPrice") BigDecimal buyPrice);

    // 統計：計算某個標的的未成交總量
    @Query("SELECT SUM(o.quantity - o.filledQuantity) FROM Order o " +
           "WHERE o.symbol = :symbol AND o.side = :side AND o.status IN ('OPEN', 'PARTIAL')")
    Long sumOpenQuantity(@Param("symbol") String symbol, @Param("side") String side);
}
```

---

### 3-5 自訂 Native SQL

當你需要用 MySQL 特有語法、或效能優化時：

```java
@Repository
public interface TradeRepository extends JpaRepository<Trade, Long> {

    // nativeQuery = true 代表寫的是真正的 SQL，非 JPQL
    // 表格名稱用 trades，不是類別名稱 Trade
    @Query(value = """
            SELECT DATE(created_at) AS trade_date,
                   COUNT(*)          AS trade_count,
                   SUM(price * quantity) AS total_volume
            FROM trades
            WHERE symbol = :symbol
              AND created_at >= :startDate
            GROUP BY DATE(created_at)
            ORDER BY trade_date DESC
            """,
           nativeQuery = true)
    List<Object[]> getDailyVolume(@Param("symbol") String symbol,
                                  @Param("startDate") LocalDateTime startDate);
}
```

---

## 四、關聯映射（Relationship Mapping）

### 4-1 一對多 / 多對一

在撮合引擎中：一筆訂單（Order）可以有多筆成交（Trade）。

```
  orders 表                    trades 表
  ┌────────────────┐           ┌──────────────────────────┐
  │ id = 1         │ 1       N │ id = 10, buy_order_id = 1│
  │ symbol = BTC   ├──────────►│ id = 11, buy_order_id = 1│
  │ side = BUY     │           │ id = 12, buy_order_id = 1│
  │ quantity = 100 │           └──────────────────────────┘
  └────────────────┘
```

**在 Order 端（一的那側）加 @OneToMany：**

```java
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ... 其他欄位省略 ...

    // @OneToMany：一筆 Order 對應多筆 Trade
    // mappedBy = "buyOrder" 表示「關聯的維護方在 Trade 的 buyOrder 欄位」
    // cascade = CascadeType.ALL：對 Order 的操作會連動到 Trade（謹慎使用）
    // fetch = FetchType.LAZY：預設不載入，等你真正存取 trades 才查 DB（重要！）
    @OneToMany(mappedBy = "buyOrder", fetch = FetchType.LAZY)
    private List<Trade> trades = new ArrayList<>();
}
```

**在 Trade 端（多的那側）加 @ManyToOne：**

```java
@Entity
@Table(name = "trades")
public class Trade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @ManyToOne：多筆 Trade 對應一筆 Order（買方）
    // @JoinColumn：外鍵欄位名稱是 buy_order_id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buy_order_id", nullable = false)
    private Order buyOrder;

    // 同理，賣方訂單
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sell_order_id", nullable = false)
    private Order sellOrder;

    // ... 其他欄位省略 ...
}
```

---

### 4-2 FetchType：LAZY vs EAGER

| FetchType | 說明 | 什麼時候載入 |
|-----------|------|-------------|
| **LAZY（懶載入）** | 需要時才查 DB | 你第一次存取 `order.getTrades()` 時 |
| **EAGER（即刻載入）** | 查主物件時順便查 | `findById(1)` 時立刻 JOIN 查 trades |

**強烈建議預設用 LAZY**，原因是 N+1 問題。

---

### 4-3 N+1 問題圖解（一定要看懂！）

假設你要撈出 100 筆訂單，並且每筆都顯示它的成交記錄：

**使用 EAGER 或在迴圈中存取懶載入集合，會發生以下情況：**

```
  你以為只執行了 1 條 SQL：
  ─────────────────────────────────────────────────
  SELECT * FROM orders LIMIT 100;
  ─────────────────────────────────────────────────

  實際上 Hibernate 偷偷再執行了 100 條 SQL：
  ─────────────────────────────────────────────────
  SELECT * FROM trades WHERE buy_order_id = 1;    ← 第 1 筆訂單的成交
  SELECT * FROM trades WHERE buy_order_id = 2;    ← 第 2 筆訂單的成交
  SELECT * FROM trades WHERE buy_order_id = 3;    ← 第 3 筆訂單的成交
  ...（省略 97 條）
  SELECT * FROM trades WHERE buy_order_id = 100;  ← 第 100 筆訂單的成交
  ─────────────────────────────────────────────────
  
  總計：1 + 100 = 101 條 SQL  ← 這就是「N+1 問題」
  
  時間成本估算：
  - 每條 SQL 網路往返約 1ms
  - 101 條 × 1ms = 101ms（串行執行更慢）
  - 撮合引擎要求 < 1ms 延遲，這完全無法接受
```

**問題的觸發點（常見錯誤）：**

```java
// 這段看起來很無害，但藏著 N+1 問題
List<Order> orders = orderRepository.findAll();  // 1 條 SQL

for (Order order : orders) {
    // 這裡每次都觸發一條額外的 SQL！
    List<Trade> trades = order.getTrades();
    System.out.println(trades.size());
}
```

---

### 4-4 解法一：JOIN FETCH

```java
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // JOIN FETCH 讓 Hibernate 用一條 SQL 同時查 Order 和 Trade
    // 生成：SELECT o, t FROM orders o LEFT JOIN trades t ON t.buy_order_id = o.id
    //       WHERE o.symbol = ?
    @Query("SELECT DISTINCT o FROM Order o " +
           "LEFT JOIN FETCH o.trades " +
           "WHERE o.symbol = :symbol")
    List<Order> findBySymbolWithTrades(@Param("symbol") String symbol);
}
```

---

### 4-5 解法二：@EntityGraph

```java
@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // @EntityGraph 指定要預先載入的關聯，不用改 JPQL
    // attributePaths 列出要一起載入的欄位名稱（Java 欄位名，不是 DB 欄位名）
    @EntityGraph(attributePaths = {"trades"})
    List<Order> findBySymbol(String symbol);
}
```

**在撮合引擎中**：查詢撮合結果報表時用 JOIN FETCH；日常查訂單清單時保持 LAZY，避免不必要的 JOIN。

---

## 五、交易（Transaction）管理

### 5-1 @Transactional 的意義

資料庫「交易」（Transaction，不是買賣交易）是 ACID 的基礎：

- **A**tomicity（原子性）：一組操作要嘛全部成功，要嘛全部回滾
- **C**onsistency（一致性）：交易前後資料庫狀態合法
- **I**solation（隔離性）：並發交易互不干擾
- **D**urability（持久性）：成功的交易永久生效

```
  撮合引擎的一次撮合操作（必須是一個原子交易）：

  ┌─ @Transactional ────────────────────────────────────────┐
  │                                                          │
  │  1. 更新買單的 filledQuantity += 10                       │
  │  2. 更新賣單的 filledQuantity += 10                       │
  │  3. INSERT INTO trades (...) VALUES (...)                │
  │                                                          │
  │  ✓ 全部成功 → COMMIT → 資料永久存入                        │
  │  ✗ 任何一步失敗 → ROLLBACK → 三個操作全部撤銷              │
  │                                                          │
  └──────────────────────────────────────────────────────────┘

  如果沒有交易保護：
  步驟 1 成功，步驟 2 失敗 → 買單有成交紀錄，賣單沒有 → 資料不一致！
```

```java
@Service
public class MatchingService {

    private final OrderRepository orderRepository;
    private final TradeRepository tradeRepository;

    // @Transactional 加在 Service 方法上（不是 Repository）
    // Spring 在方法開始前 BEGIN TRANSACTION，結束後 COMMIT
    // 如果拋出 RuntimeException，自動 ROLLBACK
    @Transactional
    public Trade executeTrade(Order buyOrder, Order sellOrder,
                               BigDecimal price, Long quantity) {

        // 步驟 1：更新買單已成交數量
        buyOrder.setFilledQuantity(buyOrder.getFilledQuantity() + quantity);
        if (buyOrder.getFilledQuantity().equals(buyOrder.getQuantity())) {
            buyOrder.setStatus("FILLED");
        } else {
            buyOrder.setStatus("PARTIAL");
        }
        orderRepository.save(buyOrder);  // UPDATE orders SET ...

        // 步驟 2：更新賣單已成交數量
        sellOrder.setFilledQuantity(sellOrder.getFilledQuantity() + quantity);
        if (sellOrder.getFilledQuantity().equals(sellOrder.getQuantity())) {
            sellOrder.setStatus("FILLED");
        } else {
            sellOrder.setStatus("PARTIAL");
        }
        orderRepository.save(sellOrder); // UPDATE orders SET ...

        // 步驟 3：建立成交紀錄
        Trade trade = new Trade(buyOrder.getId(), sellOrder.getId(),
                                buyOrder.getSymbol(), price, quantity);
        return tradeRepository.save(trade);  // INSERT INTO trades ...

        // 方法正常結束 → Spring 自動 COMMIT 這三個操作
    }
}
```

---

### 5-2 交易傳播行為（Propagation）

當一個 `@Transactional` 方法呼叫另一個 `@Transactional` 方法，誰的交易範圍算誰的？

```java
@Service
public class OrderService {

    @Autowired
    private MatchingService matchingService;

    @Autowired
    private AuditService auditService;

    // Propagation.REQUIRED（預設）：
    // 有交易就加入，沒有就新開一個
    @Transactional(propagation = Propagation.REQUIRED)
    public void processOrder(Order order) {

        orderRepository.save(order);             // 在同一個交易內

        matchingService.executeTrade(...);       // 也在同一個交易內
        // 如果 executeTrade 拋出例外，整個 processOrder 的交易都會 ROLLBACK
    }
}

@Service
public class AuditService {

    // Propagation.REQUIRES_NEW：
    // 不管外面有沒有交易，一定開一個全新的獨立交易
    // 用途：審計日誌要獨立儲存，不能因為主交易失敗而消失
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logOrderEvent(String orderId, String event) {
        // 就算外層交易 ROLLBACK，這筆日誌依然被 COMMIT 儲存
        auditLogRepository.save(new AuditLog(orderId, event));
    }
}
```

```
  交易傳播行為圖解：

  REQUIRED（預設）：
  ──────────────────────────────────────────────────────────
  processOrder() BEGIN TX-1
    ├── save(order)              [TX-1]
    ├── executeTrade()           [加入 TX-1，共用]
    │     ├── save(buyOrder)     [TX-1]
    │     └── save(trade)        [TX-1]
    └── COMMIT TX-1  ← 全部一起提交

  REQUIRES_NEW：
  ──────────────────────────────────────────────────────────
  processOrder() BEGIN TX-1
    ├── save(order)              [TX-1]
    ├── logOrderEvent()
    │     ├── SUSPEND TX-1
    │     ├── BEGIN TX-2         [全新獨立交易]
    │     ├── save(auditLog)     [TX-2]
    │     └── COMMIT TX-2  ← 先單獨提交
    │     └── RESUME TX-1
    └── COMMIT TX-1（或 ROLLBACK，不影響 TX-2）
```

---

### 5-3 樂觀鎖（@Version）

在高頻撮合場景中，兩個執行緒可能同時更新同一筆訂單，造成「重複成交」。  
樂觀鎖用版本號解決這個問題，**不需要資料庫層的 row lock，效能更好**。

```java
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // @Version：樂觀鎖的版本號欄位
    // 每次 UPDATE 時，Hibernate 自動在 WHERE 子句加上版本號條件
    // 如果版本號不符（代表被其他執行緒搶先更新了），拋出 OptimisticLockException
    @Version
    private Long version = 0L;

    // ... 其他欄位省略 ...
}
```

```
  樂觀鎖運作原理：

  時間軸
  ──────┬──────────────────────────────────────────────────
  T=0   │ 執行緒 A 讀取 Order#1（version=5, quantity=100）
        │ 執行緒 B 讀取 Order#1（version=5, quantity=100）
  ──────┼──────────────────────────────────────────────────
  T=1   │ 執行緒 A 執行：
        │   UPDATE orders SET filled_quantity=10, version=6
        │   WHERE id=1 AND version=5   ← 版本號符合，成功！
  ──────┼──────────────────────────────────────────────────
  T=2   │ 執行緒 B 執行：
        │   UPDATE orders SET filled_quantity=10, version=6
        │   WHERE id=1 AND version=5   ← version 已是 6，條件不符！
        │   → 影響 0 列 → Hibernate 拋出 OptimisticLockException
        │   → @Transactional ROLLBACK
        │   → 呼叫端 catch 後重試
  ──────┴──────────────────────────────────────────────────
  結果：避免了重複成交，代價只是一次重試，無需資料庫鎖等待
```

---

## 六、JDBC vs Spring Data JPA 對比

用「查詢某標的的所有開放中訂單」這個功能做比較：

### 6-1 JDBC 寫法（第十九章風格）

```java
// 需要：DataSource、手動 SQL、手動映射、手動資源管理
public List<Order> findOpenOrdersBySymbol(String symbol) throws SQLException {
    List<Order> result = new ArrayList<>();
    String sql = "SELECT id, symbol, side, price, quantity, filled_quantity, " +
                 "       status, created_at " +
                 "FROM orders WHERE symbol = ? AND status IN ('OPEN', 'PARTIAL')";

    try (Connection conn = dataSource.getConnection();
         PreparedStatement ps = conn.prepareStatement(sql)) {

        ps.setString(1, symbol);
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Order o = new Order();
                o.setId(rs.getLong("id"));
                o.setSymbol(rs.getString("symbol"));
                o.setSide(rs.getString("side"));
                o.setPrice(rs.getBigDecimal("price"));
                o.setQuantity(rs.getLong("quantity"));
                o.setFilledQuantity(rs.getLong("filled_quantity"));
                o.setStatus(rs.getString("status"));
                o.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
                result.add(o);
            }
        }
    }
    return result;
}
// 程式碼：約 25 行
// 可能出錯點：SQL typo、欄位名稱 typo、欄位數量不符、忘記 close
```

### 6-2 Spring Data JPA 寫法

```java
// Repository 介面（只需這一行）
List<Order> findBySymbolAndStatusIn(String symbol, List<String> statuses);

// Service 呼叫
List<Order> openOrders = orderRepository.findBySymbolAndStatusIn(
    symbol,
    List.of("OPEN", "PARTIAL")
);
// 程式碼：2 行（不含 import）
// Spring 自動生成 SQL、映射結果、管理 Connection
```

---

### 6-3 什麼時候還是要用 JDBC 或 Native SQL？

Spring Data JPA 不是萬靈丹。以下場景考慮降回 JDBC：

| 場景 | 原因 | 建議方案 |
|------|------|----------|
| **批次大量插入** | Hibernate 的批次 INSERT 有額外開銷 | Spring JDBC `batchUpdate()` |
| **複雜報表查詢** | JPQL 不支援 `GROUP BY ROLLUP`、視窗函數等進階語法 | `@Query(nativeQuery=true)` |
| **超低延遲路徑** | Hibernate 的 dirty checking、proxy 物件有微秒級額外成本 | JDBC + 手動映射 |
| **資料庫特有功能** | `ON DUPLICATE KEY UPDATE`、`INSERT IGNORE` 等 MySQL 專屬語法 | Native SQL |
| **撮合引擎核心路徑** | 每微秒都重要，Hibernate 的反射開銷不可接受 | 考慮 JDBC 甚至 LMAX Disruptor + 記憶體撮合 |

**經驗法則**：管理介面、報表、設定資料 → 用 JPA，方便維護。  
撮合核心的熱路徑（hot path）→ 考慮 JDBC 或純記憶體操作。

---

## 七、Spring Boot 整合設定

```yaml
# application.yml：Spring Data JPA 常用設定
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/matching_engine?serverTimezone=UTC
    username: root
    password: ${DB_PASSWORD}     # 用環境變數，不要硬編碼密碼

  jpa:
    hibernate:
      # update：自動根據 Entity 修改表結構（開發環境用）
      # validate：只驗證不修改（正式環境用）
      # none：完全不動表結構（搭配 Flyway 管理時用）
      ddl-auto: update
    properties:
      hibernate:
        # 印出 Hibernate 產生的 SQL（除錯時很有用，上線記得關掉）
        show_sql: true
        # 格式化 SQL 讓它好讀
        format_sql: true
        # 批次大小：一次最多用幾個 INSERT 合批
        jdbc:
          batch_size: 50
    # 在 SQL log 中顯示真實參數值（而不是問號）
    show-sql: true
```

---

## 八、快速上手流程

```
  從零開始建立一個 Entity + Repository 的步驟：

  1. pom.xml 加入依賴
     spring-boot-starter-data-jpa
     mysql-connector-j

  2. application.yml 設定資料庫連線

  3. 建立 Entity 類別
     @Entity @Table @Id @GeneratedValue @Column

  4. 建立 Repository 介面
     extends JpaRepository<Entity, ID>

  5. 在 Service 注入 Repository
     private final XxxRepository repo;

  6. 啟動應用程式
     Hibernate 自動建立表格（ddl-auto: update）

  7. 測試
     repo.save(new Entity(...))
     repo.findById(1L)
```

---

## 九、Lazy Loading 的經典陷阱：LazyInitializationException <!-- 💡 進階 -->

這是 JPA 新手最常遇到的錯誤之一，理解它需要先知道 Hibernate Session 的生命週期。

### 問題重現

```java
@Entity
public class Order {
    @OneToMany(mappedBy = "order", fetch = FetchType.LAZY)  // 預設 LAZY
    private List<OrderItem> items;
}

// Service 層
@Service
public class OrderService {

    @Transactional
    public Order getOrder(Long id) {
        return orderRepository.findById(id).orElseThrow();
        // 這裡 Transaction 結束，Hibernate Session 關閉
    }
}

// Controller 層（Transaction 已結束）
@GetMapping("/orders/{id}")
public OrderDTO getOrder(@PathVariable Long id) {
    Order order = orderService.getOrder(id);
    // ❌ 觸發 LazyInitializationException！
    // Hibernate Session 已關閉，無法再去 DB 查 items
    order.getItems().size();
}
```

```
錯誤訊息：
org.hibernate.LazyInitializationException: failed to lazily initialize a collection,
could not initialize proxy - no Session
```

### 根本原因

```
Hibernate 的 Lazy Loading 依賴 Session（資料庫連線）。
Session 在 @Transactional 方法結束時自動關閉。
在 Transaction 外部存取 Lazy 屬性 → Session 已關閉 → 爆炸。
```

### 解法一：在 Transaction 內完成所有存取（推薦）

```java
@Service
public class OrderService {

    @Transactional  // Transaction 在這個方法內
    public OrderDTO getOrderWithItems(Long id) {
        Order order = orderRepository.findById(id).orElseThrow();
        // 在 Transaction 內觸發 Lazy Loading（此時 Session 還開著）
        order.getItems().size();  // 這裡會去 DB 查
        return OrderDTO.from(order);  // 轉換成 DTO，之後不再需要 Session
    }
    // Transaction 在這裡結束，但 DTO 不依賴 Session 所以沒問題
}
```

### 解法二：JOIN FETCH / @EntityGraph（一次查詢取所有資料）

```java
// 用 JOIN FETCH 一次 SQL 取出 order + items
@Query("SELECT o FROM Order o JOIN FETCH o.items WHERE o.id = :id")
Optional<Order> findByIdWithItems(@Param("id") Long id);

// 或用 @EntityGraph
@EntityGraph(attributePaths = {"items"})
Optional<Order> findById(Long id);
```

### 解法三：Open Session in View（不推薦，但常見）

```yaml
# application.yml
spring:
  jpa:
    open-in-view: true  # 預設 true！讓 Session 延長到 HTTP Response 結束
```

> ⚠️ `open-in-view: true` 是個反模式！Session 延長到 View 層，佔用資料庫連線過久，高並發下會耗盡連線池。強烈建議設為 `false`，改用解法一或二。

---

## 練習題

### 練習一：設計 Account 實體

撮合引擎需要追蹤用戶帳戶的餘額。請設計 `Account` 實體，欄位包含：
- `id`（Long，主鍵自增）
- `userId`（Long，不可為 null）
- `currency`（String，長度 10，例如 "USDT"）
- `balance`（BigDecimal，精度 18 位，小數 8 位）
- `version`（Long，樂觀鎖）

並建立 `AccountRepository` 提供：
1. 根據 `userId` 查出所有帳戶
2. 根據 `userId` 和 `currency` 查出單一帳戶

<details>
<summary>參考答案（點擊展開）</summary>

```java
// Account.java
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @Column(name = "balance", nullable = false, precision = 18, scale = 8)
    private BigDecimal balance;

    // 樂觀鎖：防止並發扣款時重複扣
    @Version
    private Long version = 0L;

    protected Account() {}

    public Account(Long userId, String currency, BigDecimal balance) {
        this.userId = userId;
        this.currency = currency;
        this.balance = balance;
    }

    // Getter / Setter 省略
}

// AccountRepository.java
@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    // 方法命名規則自動生成 SQL
    List<Account> findByUserId(Long userId);

    Optional<Account> findByUserIdAndCurrency(Long userId, String currency);
}
```
</details>

---

### 練習二：找出 N+1 問題並修正

下面的程式碼有 N+1 問題。請指出問題在哪裡，並用 `@Query + JOIN FETCH` 修正它。

```java
// Service 程式碼
public List<String> getSummary(String symbol) {
    List<Order> orders = orderRepository.findBySymbol(symbol);  // 1 條 SQL

    List<String> result = new ArrayList<>();
    for (Order order : orders) {
        int tradeCount = order.getTrades().size();  // ← 這裡有問題
        result.add("Order#" + order.getId() + " has " + tradeCount + " trades");
    }
    return result;
}
```

<details>
<summary>參考答案（點擊展開）</summary>

**問題所在**：`order.getTrades().size()` 在迴圈中觸發懶載入，每筆 Order 各產生一條 SQL。  
如果有 50 筆訂單，就產生 1 + 50 = 51 條 SQL。

**修正方法：在 Repository 加 JOIN FETCH 查詢**

```java
// OrderRepository.java 新增方法
@Query("SELECT DISTINCT o FROM Order o " +
       "LEFT JOIN FETCH o.trades " +
       "WHERE o.symbol = :symbol")
List<Order> findBySymbolWithTrades(@Param("symbol") String symbol);
```

**Service 改用新方法**

```java
public List<String> getSummary(String symbol) {
    // 改用 JOIN FETCH，只產生 1 條 SQL（含所有成交紀錄）
    List<Order> orders = orderRepository.findBySymbolWithTrades(symbol);

    List<String> result = new ArrayList<>();
    for (Order order : orders) {
        int tradeCount = order.getTrades().size();  // 不再觸發額外 SQL
        result.add("Order#" + order.getId() + " has " + tradeCount + " trades");
    }
    return result;
}
```
</details>

---

### 練習三：交易邊界設計

撮合引擎的取消訂單流程需要：
1. 把訂單 status 改為 `CANCELLED`
2. 把帳戶的餘額退回（加回 balance）
3. 寫一筆審計日誌（就算主流程失敗，日誌也要保留）

請說明每個步驟應該使用哪種 `Propagation`，並寫出對應的 Service 方法骨架。

<details>
<summary>參考答案（點擊展開）</summary>

```java
@Service
public class CancelOrderService {

    private final OrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final AuditService auditService;   // 審計服務

    // 步驟 1 + 2 要在同一個交易中：退款和取消必須同時成功或同時失敗
    @Transactional(propagation = Propagation.REQUIRED)
    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new RuntimeException("訂單不存在：" + orderId));

        // 計算未成交的剩餘資金
        BigDecimal remainQty = new BigDecimal(order.getQuantity() - order.getFilledQuantity());
        BigDecimal refundAmount = order.getPrice().multiply(remainQty);

        // 步驟 1：取消訂單
        order.setStatus("CANCELLED");
        orderRepository.save(order);

        // 步驟 2：退款（在同一個 TX 中）
        Account account = accountRepository
            .findByUserIdAndCurrency(order.getUserId(), "USDT")
            .orElseThrow();
        account.setBalance(account.getBalance().add(refundAmount));
        accountRepository.save(account);

        // 步驟 3：審計日誌（獨立交易，不受主流程影響）
        // auditService.log() 內部用 REQUIRES_NEW，即使上面拋例外日誌也會存
        auditService.log(orderId, "CANCELLED", refundAmount);
    }
}

@Service
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    // REQUIRES_NEW：不管外層交易成功或失敗，這筆日誌一定寫入
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(Long orderId, String action, BigDecimal amount) {
        AuditLog log = new AuditLog(orderId, action, amount, LocalDateTime.now());
        auditLogRepository.save(log);
    }
}
```

**設計說明**：
- `cancelOrder` 用 `REQUIRED`：訂單取消和退款是原子操作，其中一個失敗整個交易回滾。
- `auditService.log` 用 `REQUIRES_NEW`：即使 `cancelOrder` 因網路問題在 COMMIT 前崩潰，審計日誌已獨立提交，便於事後查帳與問題排查。
</details>

---

## 本章重點回顧

| 概念 | 記憶口訣 | 撮合引擎應用 |
|------|----------|-------------|
| Entity | `@Entity` 類別 = DB 表格 | `Order`、`Trade` 物件 |
| Repository | 介面 extends JpaRepository = 免費 CRUD | `OrderRepository`、`TradeRepository` |
| Derived Query | 方法名稱即 SQL | `findBySymbolAndStatus` |
| LAZY/EAGER | 預設 LAZY，需要時才載入 | 訂單列表不預載成交明細 |
| N+1 | 查 N 筆主表 + N 次子表 = 慢 | 用 JOIN FETCH 合並成一條 |
| @Transactional | 方法是一個原子操作 | 撮合一次成交 = 一個交易 |
| @Version | 版本號防並發衝突 | 高頻更新訂單時防重複 |
