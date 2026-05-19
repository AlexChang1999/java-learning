# 第六十四章：ClickHouse 與 OLAP 分析資料庫

## 前言：為什麼報表查詢要另開一個資料庫？

```
情境：產品經理問「上個月各省份的訂單金額和轉化率趨勢，按商品類別分組」

在 MySQL 上跑這個查詢：
  SELECT province, category, DATE(created_at) as date,
         SUM(amount) as revenue, COUNT(*) as orders
  FROM orders
  JOIN order_items ON orders.id = order_items.order_id
  JOIN products ON order_items.product_id = products.id
  WHERE created_at >= '2024-01-01'
  GROUP BY province, category, date
  ORDER BY date, province;

  → 掃描 3 億行 + 多表 JOIN
  → 執行時間：5~30 分鐘（期間鎖表，影響下單）
  → 產品經理每天要問 10 次不同的問題...

ClickHouse 跑同樣的查詢：
  → 執行時間：0.3~3 秒（快 100~1000 倍）
  → 不影響 MySQL 的線上業務
```

---

## 一、列存儲 vs 行存儲：為什麼 OLAP 用列存？

```
行存儲（Row Store）— MySQL 的方式：
  每一行資料連續存放在磁碟上

  [id=1, name="Alice", age=25, city="Taipei", amount=1000]
  [id=2, name="Bob",   age=30, city="Kaohsiung", amount=2000]
  [id=3, name="Carol", age=28, city="Taipei", amount=1500]

  問題：查詢 SUM(amount) 時，必須讀出每一行（含 name, age, city），
        然後丟棄不需要的欄位 → 浪費大量 IO

列存儲（Column Store）— ClickHouse 的方式：
  每一列資料連續存放

  [id 列]:     1, 2, 3, ...
  [name 列]:   "Alice", "Bob", "Carol", ...
  [age 列]:    25, 30, 28, ...
  [city 列]:   "Taipei", "Kaohsiung", "Taipei", ...
  [amount 列]: 1000, 2000, 1500, ...

  優點 1：查詢 SUM(amount) 只讀 amount 列，其他列完全不碰 → IO 最小化
  優點 2：同一列的資料類型相同 → 壓縮率極高
         （"Taipei", "Kaohsiung", "Taipei" 可以用字典壓縮，變成 0, 1, 0）
  優點 3：SIMD 向量化執行：CPU 一次處理 8 個/16 個整數（批次運算）
```

### 壓縮率的意義

```
MySQL 存 1 億筆訂單：~50 GB
ClickHouse 存同樣的資料：~5 GB（壓縮率 10x）

→ 同樣的記憶體能快取更多資料
→ 磁碟 IO 減少 10 倍
→ 查詢速度大幅提升（IO bound 的查詢直接快 10 倍）
```

---

## 二、MergeTree 引擎家族

ClickHouse 的 Table Engine 是其核心競爭力，不同引擎適合不同場景：

```sql
-- MergeTree：最基礎，也是最常用的引擎
CREATE TABLE orders (
    order_id    UInt64,
    customer_id UInt32,
    province    LowCardinality(String),   -- 低基數字串用 LowCardinality（字典壓縮）
    category    LowCardinality(String),
    amount      Decimal(12, 2),
    created_at  DateTime,
    -- 分區鍵（Partition Key）：按月份分區，每個月份的資料在一起
    -- 查詢 WHERE created_at >= '2024-01-01' 只掃描對應月份的分區
    PARTITION BY toYYYYMM(created_at)
)
ENGINE = MergeTree()
-- 主鍵 = 稀疏索引（不像 MySQL 是唯一索引！）
-- 每 8192 行記錄一個索引點，而不是每行都記錄
-- → 目的是幫助跳過不需要的資料塊
ORDER BY (province, category, created_at);   -- 主鍵決定資料的排列順序
```

```sql
-- ReplicatedMergeTree：帶副本的 MergeTree（高可用）
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/orders', '{replica}')
ORDER BY (province, category, created_at);

-- SummingMergeTree：自動合併 SUM（適合預聚合）
-- 插入時自動把相同主鍵的行合並加總，減少後續查詢的計算量
CREATE TABLE order_daily_summary (
    province    LowCardinality(String),
    category    LowCardinality(String),
    date        Date,
    total_amount Decimal(15, 2),    -- 會被自動加總
    order_count  UInt64              -- 會被自動加總
)
ENGINE = SummingMergeTree()
ORDER BY (province, category, date);

-- AggregatingMergeTree：更通用的預聚合（支援 MAX, MIN, COUNT...）
-- CollapsingMergeTree：支援更新/刪除（通過寫入 sign=+1/-1 行來表示增加/撤銷）
```

---

## 三、高效查詢技巧

```sql
-- 查詢 1：各省份各月份的銷售額（典型 OLAP 查詢）
SELECT
    province,
    toYYYYMM(created_at) AS month,
    SUM(amount) AS revenue,
    COUNT() AS order_count,              -- COUNT() 比 COUNT(*) 快
    AVG(amount) AS avg_order_value
FROM orders
WHERE created_at >= '2024-01-01'
  AND created_at <  '2024-02-01'
GROUP BY province, month
ORDER BY revenue DESC;
-- 執行時間：0.1 秒（掃描 1 億行）

-- 查詢 2：漏斗分析（用戶瀏覽 → 加購 → 下單）
SELECT
    countIf(event = 'view')    AS views,
    countIf(event = 'addcart') AS add_carts,
    countIf(event = 'order')   AS orders,
    countIf(event = 'order') / countIf(event = 'view') AS conversion_rate
FROM user_events
WHERE date >= today() - 7;

-- 查詢 3：使用物化視圖（Materialized View）預計算
-- 建立物化視圖：每次有新資料插入 orders，自動更新這個視圖
CREATE MATERIALIZED VIEW orders_by_day_mv
ENGINE = SummingMergeTree()
ORDER BY (province, category, day)
POPULATE   -- 用現有資料初始化
AS
SELECT
    province,
    category,
    toDate(created_at) AS day,
    SUM(amount) AS total_amount,
    COUNT() AS order_count
FROM orders
GROUP BY province, category, day;

-- 查詢物化視圖（超快，已預計算）
SELECT province, SUM(total_amount) FROM orders_by_day_mv
WHERE day >= today() - 30
GROUP BY province;
```

---

## 四、Spring Boot 整合

ClickHouse 沒有官方 Spring Data 支援，使用 JDBC 整合：

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.clickhouse</groupId>
    <artifactId>clickhouse-jdbc</artifactId>
    <version>0.6.0</version>
</dependency>
```

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:clickhouse://localhost:8123/analytics_db
    driver-class-name: com.clickhouse.jdbc.ClickHouseDriver
    username: default
    password: ""
    hikari:
      maximum-pool-size: 10
      connection-timeout: 30000
```

```java
// 使用 JdbcTemplate 查詢
@Repository
public class OrderAnalyticsRepository {

    private final JdbcTemplate jdbcTemplate;

    // 查詢各省月銷售額
    public List<SalesSummary> getMonthlySalesByProvince(
            LocalDate startDate, LocalDate endDate) {

        String sql = """
            SELECT
                province,
                toYYYYMM(created_at) AS month,
                SUM(amount) AS revenue,
                COUNT() AS order_count
            FROM orders
            WHERE created_at >= ? AND created_at < ?
            GROUP BY province, month
            ORDER BY revenue DESC
            """;

        return jdbcTemplate.query(sql,
            (rs, row) -> new SalesSummary(
                rs.getString("province"),
                rs.getInt("month"),
                rs.getBigDecimal("revenue"),
                rs.getLong("order_count")
            ),
            startDate, endDate
        );
    }

    // 批次插入（ClickHouse 最佳寫入方式：批次 > 單筆）
    public void insertOrdersBatch(List<Order> orders) {
        // ClickHouse 推薦一次插入至少 1000 行
        // 太多小批次插入會造成 Parts 碎片化，影響查詢性能
        String sql = "INSERT INTO orders VALUES (?, ?, ?, ?, ?, ?)";

        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                Order o = orders.get(i);
                ps.setLong(1, o.getOrderId());
                ps.setInt(2, o.getCustomerId());
                ps.setString(3, o.getProvince());
                ps.setString(4, o.getCategory());
                ps.setBigDecimal(5, o.getAmount());
                ps.setTimestamp(6, Timestamp.valueOf(o.getCreatedAt()));
            }

            @Override
            public int getBatchSize() { return orders.size(); }
        });
    }
}
```

---

## 五、資料同步：MySQL → ClickHouse

ClickHouse 作為分析庫，資料通常從 OLTP 資料庫同步過來：

### 方案 1：Kafka + ClickHouse Kafka Engine

```
MySQL Binlog → Debezium → Kafka → ClickHouse Kafka Table Engine
                                         ↓
                                  物化視圖觸發
                                         ↓
                                   MergeTree Table（查詢用）

延遲：< 1 秒（近即時同步）
```

```sql
-- ClickHouse 消費 Kafka 的訂單事件
CREATE TABLE orders_kafka_queue (
    order_id    UInt64,
    amount      Decimal(12,2),
    province    String,
    created_at  DateTime
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list  = 'mysql.orders',
    kafka_group_name  = 'clickhouse-consumer',
    kafka_format      = 'JSONEachRow';

-- 物化視圖：自動把 Kafka 的資料插入到 MergeTree
CREATE MATERIALIZED VIEW orders_from_kafka TO orders AS
SELECT order_id, amount, province, created_at
FROM orders_kafka_queue;
```

### 方案 2：定時 ETL（簡單場景）

```java
// 每小時把 MySQL 的新訂單批次同步到 ClickHouse
@Scheduled(cron = "0 0 * * * *")   // 每小時執行
public void syncOrdersToClickHouse() {
    // 查詢上次同步時間之後的新訂單
    LocalDateTime lastSync = getLastSyncTime();
    List<Order> newOrders = mysqlOrderRepository
        .findByCreatedAtAfter(lastSync);

    if (!newOrders.isEmpty()) {
        clickHouseRepository.insertOrdersBatch(newOrders);
        updateLastSyncTime(LocalDateTime.now());
        log.info("同步 {} 筆訂單到 ClickHouse", newOrders.size());
    }
}
```

---

## 六、ClickHouse vs Elasticsearch 選型 <!-- 💡 進階 -->

兩者都能做「大資料量的快速查詢」，但擅長的方向不同：

| 維度 | ClickHouse | Elasticsearch |
|------|-----------|---------------|
| **擅長** | 聚合計算（SUM/GROUP BY）| 全文搜尋（分詞/相關度）|
| **查詢語言** | SQL（最熟悉）| DSL JSON（有學習成本）|
| **壓縮率** | 極高（10x）| 一般（2~3x）|
| **寫入延遲** | 批次寫入好，不適合單筆即時 | 即時寫入（毫秒級）|
| **Join 支援** | 支援（但不推薦超大表 Join）| 不支援（Denormalize 或 Nested）|
| **更新/刪除** | 很慢（需要 Mutation）| 支援，但性能一般 |
| **資料量** | 千億行無壓力 | TB 級問題不大，但比 ClickHouse 貴 |
| **適合場景** | 銷售報表、用戶行為分析、BI | 商品搜尋、日誌搜尋、全文檢索 |

**選型原則：**
```
問題是「這些資料怎麼分佈、趨勢如何？」  → ClickHouse
問題是「幫我找到符合條件的文件/商品」    → Elasticsearch
兩個問題都有？同時用兩個（Polyglot Persistence）
```

---

## 本章練習題

**Q1：ClickHouse 的「稀疏索引」和 MySQL 的 B+Tree 索引有什麼本質差異？**
<details>
<summary>答案</summary>
MySQL B+Tree 索引：每一行都有對應的索引條目，可以精準定位單行。代價是索引本身佔用大量空間，每次插入都要維護索引樹。ClickHouse 稀疏索引（Sparse Index）：每 8192 行（一個 Granule）才記錄一個索引點，記錄的是那個 Granule 的第一行的 Key 值。查詢時，根據稀疏索引找到可能包含目標資料的 Granule，然後讀取整個 Granule（8192 行）掃描。這樣設計的原因是：OLAP 查詢本來就要掃描大量資料（不是精準點查），稀疏索引夠用了，還省了大量索引維護開銷和儲存空間。
</details>

**Q2：為什麼說 ClickHouse 不適合「大量小批次寫入」？**
<details>
<summary>答案</summary>
ClickHouse 每次 INSERT 都會建立一個新的「Part」（資料塊）。如果頻繁插入小批次（例如每秒插入 1000 次，每次 10 行），會產生大量小 Part。背景的 Merge 進程需要不斷合並這些小 Part，消耗大量 CPU 和 IO，最終可能超過 Merge 的速度，導致 Part 數量無限增長，查詢性能急劇下降（Too Many Parts 錯誤）。最佳實踐：應用層先把資料攢夠（至少 1000~10000 行），再批次插入，或者用 Kafka Buffer 收集後定時批次刷入。
</details>

**Q3：電商平台有個需求：「統計每個用戶最近 30 天購買的商品類別，推算偏好」。這個查詢適合放在 MySQL 還是 ClickHouse？**
<details>
<summary>答案</summary>
適合 ClickHouse。原因：(1) 需要掃描所有用戶最近 30 天的訂單（可能幾億行），這是典型 OLAP 查詢，不是點查；(2) 需要 GROUP BY user_id, category 做聚合計算；(3) 這個查詢和線上下單業務完全分離，不需要強一致性。架構方案：用 CDC（Debezium）把 MySQL 的訂單數據準即時同步到 ClickHouse，再在 ClickHouse 上跑分析查詢。可以建立物化視圖預先計算每日每用戶的商品類別偏好，讓查詢從幾秒降到毫秒級。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 38 章 | Phase 5：進階後端技術
> 下一章（第 39 章）：[第六十五章：時序數據庫](65_第六十五章_時序數據庫.md)
