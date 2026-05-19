# 第十九章：資料庫與 JDBC
> 讓撮合引擎的訂單和成交記錄活過重啟：從 SQL 基礎到 Java 操作資料庫

---

## 前言：為什麼需要資料庫

現在的撮合引擎有個致命問題：**重啟就歸零**。  
所有訂單和成交記錄都在記憶體裡，JVM 一停就消失。  
資料庫解決的是「如何讓資料活過程式的生命週期」。

---

## 一、SQL 快速回顧（工程師必備的五個操作）

```sql
-- 建立資料表
CREATE TABLE orders (
    order_id    VARCHAR(50)    PRIMARY KEY,
    symbol      VARCHAR(10)    NOT NULL,
    side        CHAR(4)        NOT NULL,   -- BUY / SELL
    type        VARCHAR(10)    NOT NULL,
    price       DECIMAL(15, 4) NOT NULL,
    quantity    INT            NOT NULL,
    status      VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE trades (
    trade_id      VARCHAR(50)    PRIMARY KEY,
    buy_order_id  VARCHAR(50)    NOT NULL,
    sell_order_id VARCHAR(50)    NOT NULL,
    price         DECIMAL(15, 4) NOT NULL,
    quantity      INT            NOT NULL,
    executed_at   TIMESTAMP      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (buy_order_id)  REFERENCES orders(order_id),
    FOREIGN KEY (sell_order_id) REFERENCES orders(order_id)
);

-- 新增
INSERT INTO orders (order_id, symbol, side, type, price, quantity)
VALUES ('O-001', 'TSLA', 'BUY', 'LIMIT', 350.00, 100);

-- 查詢（JOIN 兩張表）
SELECT t.trade_id, t.price, t.quantity,
       b.symbol, t.executed_at
FROM trades t
JOIN orders b ON t.buy_order_id = b.order_id
WHERE b.symbol = 'TSLA'
ORDER BY t.executed_at DESC
LIMIT 10;

-- 更新
UPDATE orders SET status = 'FILLED' WHERE order_id = 'O-001';

-- 刪除
DELETE FROM orders WHERE status = 'CANCELLED' AND created_at < NOW() - INTERVAL 30 DAY;
```

---

## 二、JDBC：Java 操作資料庫的標準 API

### 加入依賴（以 H2 記憶體資料庫為例，測試用）

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>2.2.224</version>
</dependency>
<!-- 生產環境換成 MySQL/PostgreSQL -->
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <version>8.3.0</version>
</dependency>
```

### 基本連線與查詢

```java
import java.sql.*;

public class JDBCBasics {
    // JDBC URL 格式：jdbc:資料庫類型://主機:埠/資料庫名
    private static final String URL  = "jdbc:h2:mem:exchange;DB_CLOSE_DELAY=-1";
    private static final String USER = "sa";
    private static final String PASS = "";

    public static void main(String[] args) throws SQLException {
        // 1. 取得連線（用完一定要關！）
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {

            // 2. 建立資料表
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS orders (
                        order_id VARCHAR(50) PRIMARY KEY,
                        symbol   VARCHAR(10) NOT NULL,
                        side     CHAR(4)     NOT NULL,
                        price    DOUBLE      NOT NULL,
                        quantity INT         NOT NULL,
                        status   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                    )
                """);
            }

            // 3. 新增資料（用 PreparedStatement，防止 SQL Injection！）
            String insertSql = "INSERT INTO orders VALUES (?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                ps.setString(1, "O-001");
                ps.setString(2, "TSLA");
                ps.setString(3, "BUY");
                ps.setDouble(4, 350.0);
                ps.setInt(5, 100);
                ps.setString(6, "ACTIVE");
                ps.executeUpdate();
            }

            // 4. 查詢資料
            String selectSql = "SELECT * FROM orders WHERE symbol = ?";
            try (PreparedStatement ps = conn.prepareStatement(selectSql)) {
                ps.setString(1, "TSLA");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        System.out.printf("訂單：%s | %s | %s | %.2f | %d%n",
                            rs.getString("order_id"),
                            rs.getString("symbol"),
                            rs.getString("side"),
                            rs.getDouble("price"),
                            rs.getInt("quantity")
                        );
                    }
                }
            }
        } // try-with-resources：自動關閉連線
    }
}
```

### 為什麼一定要用 PreparedStatement？

```java
// 危險：直接拼接字串（SQL Injection 漏洞！）
String userId = "' OR '1'='1";  // 惡意輸入
String sql = "SELECT * FROM orders WHERE order_id = '" + userId + "'";
// 實際執行的 SQL：SELECT * FROM orders WHERE order_id = '' OR '1'='1'
// → 回傳所有訂單！資料庫被攻破

// 安全：PreparedStatement 自動轉義特殊字元
PreparedStatement ps = conn.prepareStatement(
    "SELECT * FROM orders WHERE order_id = ?"
);
ps.setString(1, userId);  // 會自動把 ' 轉義成 ''，無法注入
```

---

## 三、ACID 交易：資料庫的四大保證

```
A - Atomicity（原子性）：  一組操作全部成功或全部失敗，不存在「一半完成」
C - Consistency（一致性）：交易前後，資料滿足所有約束條件
I - Isolation（隔離性）：  並發交易互不干擾（有等級之分）
D - Durability（持久性）：  一旦 COMMIT，資料就算崩潰也不丟失
```

**撮合引擎中的 ACID 應用：**

```java
// 一筆成交必須同時更新訂單狀態和插入成交記錄
// 如果中間程式崩潰，不能只更新了訂單但沒插成交記錄！

public void persistTrade(Connection conn, Trade trade,
                          Order buyOrder, Order sellOrder) throws SQLException {
    // 關閉自動提交（預設是 true，每個 executeUpdate 自動 COMMIT）
    conn.setAutoCommit(false);

    try {
        // 步驟 1：更新買單狀態
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE orders SET status = ?, quantity = ? WHERE order_id = ?")) {
            ps.setString(1, buyOrder.getStatus().name());
            ps.setInt(2, buyOrder.getRemainingQty());
            ps.setString(3, buyOrder.getOrderId());
            ps.executeUpdate();
        }

        // 步驟 2：更新賣單狀態
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE orders SET status = ?, quantity = ? WHERE order_id = ?")) {
            ps.setString(1, sellOrder.getStatus().name());
            ps.setInt(2, sellOrder.getRemainingQty());
            ps.setString(3, sellOrder.getOrderId());
            ps.executeUpdate();
        }

        // 步驟 3：插入成交記錄
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO trades VALUES (?, ?, ?, ?, ?, NOW())")) {
            ps.setString(1, trade.getTradeId());
            ps.setString(2, trade.getBuyOrderId());
            ps.setString(3, trade.getSellOrderId());
            ps.setDouble(4, trade.getPrice());
            ps.setInt(5,    trade.getQuantity());
            ps.executeUpdate();
        }

        conn.commit();  // 三步都成功：提交！

    } catch (SQLException e) {
        conn.rollback();  // 任何一步失敗：全部回滾
        throw e;
    } finally {
        conn.setAutoCommit(true);  // 恢復預設
    }
}
```

---

## 四、HikariCP：連線池（生產環境必用）

**為什麼需要連線池？**

```
建立資料庫連線的代價：
  TCP 三次握手：~1 ms
  資料庫認證：~5 ms
  合計：~6 ms

如果每個請求都建立新連線：
  1000 req/s × 6 ms = 6000 ms 都花在建連線上，完全不夠用

連線池的解法：
  預先建立 10 個連線，請求來了從池子借一個，用完還回去
  建立新連線的 6 ms 只在啟動時花一次
```

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>
```

```java
import com.zaxxer.hikari.*;

public class DatabaseConfig {

    private static final HikariDataSource dataSource;

    static {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:mysql://localhost:3306/exchange");
        config.setUsername("root");
        config.setPassword("password");

        // 連線池設定
        config.setMaximumPoolSize(10);       // 最多 10 條連線
        config.setMinimumIdle(5);            // 至少保持 5 條閒置連線
        config.setConnectionTimeout(3000);   // 等待連線的超時時間 3 秒
        config.setIdleTimeout(600_000);      // 閒置超過 10 分鐘就關閉
        config.setMaxLifetime(1_800_000);    // 每條連線最多活 30 分鐘（避免資料庫強制斷線）

        // 效能調優
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");

        dataSource = new HikariDataSource(config);
    }

    // 應用程式中用這個方法取得連線
    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();  // 從池子借，不是真的建立新連線
    }
}

// 使用
try (Connection conn = DatabaseConfig.getConnection()) {
    // 做資料庫操作...
}
// try-with-resources 結束時：把連線還回池子，不是真的關閉
```

---

## 五、OrderRepository：為撮合引擎加上持久化層

```java
public class OrderRepository {

    // 儲存新訂單
    public void save(Order order) throws SQLException {
        String sql = """
            INSERT INTO orders (order_id, symbol, side, type, price, quantity, status)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE status = VALUES(status), quantity = VALUES(quantity)
            """;
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, order.getOrderId());
            ps.setString(2, order.getSymbol());
            ps.setString(3, order.getSide());
            ps.setString(4, order.getType().name());
            ps.setDouble(5, order.getPrice());
            ps.setInt(6,    order.getRemainingQty());
            ps.setString(7, order.getStatus().name());
            ps.executeUpdate();
        }
    }

    // 查詢某商品的所有活躍訂單（用於系統重啟後重建訂單簿）
    public List<Order> findActiveBySymbol(String symbol) throws SQLException {
        String sql = "SELECT * FROM orders WHERE symbol = ? AND status IN ('ACTIVE', 'PARTIALLY_FILLED')";
        List<Order> result = new ArrayList<>();
        try (Connection conn = DatabaseConfig.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, symbol);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRowToOrder(rs));
                }
            }
        }
        return result;
    }

    private Order mapRowToOrder(ResultSet rs) throws SQLException {
        return new Order(
            rs.getString("order_id"),
            rs.getString("symbol"),
            rs.getString("side"),
            OrderType.valueOf(rs.getString("type")),
            rs.getDouble("price"),
            rs.getInt("quantity")
        );
    }
}
```

**系統重啟後恢復訂單簿：**

```java
// 應用程式啟動時
OrderRepository repo = new OrderRepository();
OrderBook book = new OrderBook("TSLA");

List<Order> activeOrders = repo.findActiveBySymbol("TSLA");
for (Order order : activeOrders) {
    book.addOrder(order);  // 從資料庫重建記憶體中的訂單簿
}
System.out.println("從資料庫恢復 " + activeOrders.size() + " 筆活躍訂單");
```

---

## 本章練習題

**Q1：Statement 和 PreparedStatement 有什麼差異？什麼情況下用哪個？**
<details>
<summary>答案</summary>
Statement 每次執行都會重新解析 SQL；PreparedStatement 只解析一次，之後可重複執行（效能更好），而且會自動轉義參數，防止 SQL Injection。規則：只要有任何使用者輸入或外部資料進入 SQL，都必須用 PreparedStatement。只有完全確定的靜態 SQL（如建表語句）才用 Statement。
</details>

**Q2：為什麼 try-with-resources 對 JDBC 特別重要？**
<details>
<summary>答案</summary>
Connection、Statement、ResultSet 都是需要手動關閉的資源。如果不關閉：Connection 會佔著連線池的位置（最終所有連線都被佔用，新請求等待超時）；Statement 和 ResultSet 會造成 cursor leak，資料庫伺服器端的資源無法釋放。try-with-resources 保證無論是否拋出例外，close() 一定會被呼叫。
</details>

**Q3：什麼是 N+1 查詢問題？**
<details>
<summary>答案</summary>
N+1 問題：先用 1 次查詢取得 N 個訂單，然後對每個訂單再執行 1 次查詢取得成交記錄，總共 N+1 次資料庫查詢。例如：查出 100 筆訂單，再分別查每筆訂單的成交記錄 = 101 次查詢。解法：用 JOIN 一次查詢取得所有需要的資料，或用 IN 子句批次查詢，例如 SELECT * FROM trades WHERE buy_order_id IN ('O1','O2',...,'O100')。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 22 章 | Phase 4：資料庫 + Spring 後端
> 下一章（第 23 章）：[第二十一章：Spring Boot 基礎](21_第二十一章_SpringBoot基礎.md)
