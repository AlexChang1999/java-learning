# 第四十八章：MongoDB 與 NoSQL 設計

## 前言：為什麼有了 MySQL 還需要 MongoDB？

關聯式資料庫（MySQL、PostgreSQL）非常適合結構化、強一致性的資料。但有些場景它做起來很痛：

| 場景 | MySQL 的痛 | MongoDB 的優勢 |
|------|-----------|---------------|
| 商品資訊（每個品類欄位不同） | 要建幾十個欄位，大多數是 NULL | 文件模型，每個商品的 schema 可以不同 |
| 使用者行為日誌（海量寫入） | 寫入鎖競爭嚴重 | 高吞吐寫入，自然分片 |
| 巢狀資料（訂單+商品+評論） | 需要 JOIN 多張表 | 一個文件包含所有相關資料 |
| 快速迭代（schema 常改） | ALTER TABLE 大表很慢 | Schema-free，隨時新增欄位 |
| 地理位置查詢 | 需要外掛 | 內建 2dsphere 地理索引 |

---

## 一、核心概念：文件模型

MongoDB 儲存的是 **JSON-like 文件**（內部格式是 BSON，Binary JSON）。

```
SQL 概念         →    MongoDB 概念
Database         →    Database
Table            →    Collection（集合）
Row（一筆資料）   →    Document（文件）
Column           →    Field（欄位）
Primary Key      →    _id（自動生成 ObjectId）
JOIN             →    嵌入文件 / $lookup
```

```json
// 一個訂單文件：把所有相關資料放在一起
{
  "_id": ObjectId("65a1b2c3d4e5f6a7b8c9d0e1"),
  "orderId": "ORD-20240115-001",
  "status": "PAID",
  "createdAt": ISODate("2024-01-15T10:30:00Z"),
  "customer": {
    "id": "USR-001",
    "name": "王小明",
    "email": "wang@example.com"
  },
  "items": [
    {
      "productId": "PRD-001",
      "name": "Java 程式設計書",
      "price": 599.0,
      "quantity": 2
    },
    {
      "productId": "PRD-002",
      "name": "機械鍵盤",
      "price": 2800.0,
      "quantity": 1
    }
  ],
  "totalAmount": 3998.0,
  "shippingAddress": {
    "city": "台北市",
    "district": "信義區",
    "address": "信義路五段7號"
  },
  "tags": ["electronics", "books"]
}
```

這樣一次 query 就能取得訂單所有資訊，不需要 JOIN。

---

## 二、資料建模：嵌入 vs 引用

這是 MongoDB 設計中最重要的決策。

### 嵌入（Embedded）：把相關資料放在同一個文件

```json
// 使用者文件，地址直接嵌入
{
  "_id": ObjectId("..."),
  "name": "王小明",
  "addresses": [
    { "type": "home", "city": "台北", "street": "信義路" },
    { "type": "work", "city": "新北", "street": "板橋路" }
  ]
}
```

**適合嵌入的情況：**
- 資料之間是「包含」關係（一對少、一對一）
- 子資料很少單獨被查詢
- 子資料不會無限成長（如評論上限 100 條）

### 引用（Reference）：用 ID 連接不同 collection

```json
// 文章文件
{ "_id": ObjectId("POST-001"), "title": "K8s 入門", "authorId": ObjectId("USER-001") }

// 查詢時用 $lookup 做 JOIN（類似 SQL LEFT JOIN）
```

**適合引用的情況：**
- 資料之間是「多對多」
- 子資料會無限成長（如訂單的所有評論）
- 子資料需要獨立管理（CRUD）

### 設計原則總結

```
問：這個資料是否總是和父文件一起被查詢？
  ├── 是 → 考慮嵌入
  └── 否 → 考慮引用

問：這個嵌入陣列會無限成長嗎？
  ├── 是 → 一定要用引用（文件大小上限 16MB）
  └── 否 → 可以嵌入
```

---

## 三、Spring Boot 整合 MongoDB

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>
```

```yaml
# application.yml
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/orderdb
      # 生產環境：mongodb://user:password@host:27017/orderdb?authSource=admin
```

### 定義 Document 實體類

```java
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import java.time.LocalDateTime;
import java.util.List;

@Document(collection = "orders")   // 對應 MongoDB 的 collection 名稱
@CompoundIndex(def = "{'customerId': 1, 'createdAt': -1}", name = "customer_time_idx")
public class Order {

    @Id                            // 對應 _id 欄位
    private String id;             // MongoDB ObjectId 以 String 儲存

    @Indexed(unique = true)        // 建立唯一索引
    private String orderId;

    @Field("status")               // 可以指定 MongoDB 中的欄位名稱
    private OrderStatus status;

    private String customerId;

    @Indexed                       // 建立索引（查詢常用的欄位）
    private LocalDateTime createdAt;

    private List<OrderItem> items; // 嵌入文件（不需要 @Document）

    private double totalAmount;

    // Getters and Setters...
}

// 嵌入文件類（不需要 @Document）
public class OrderItem {
    private String productId;
    private String name;
    private double price;
    private int quantity;
}
```

### Repository 層

```java
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderRepository extends MongoRepository<Order, String> {

    // 方法名稱自動轉換為 MongoDB 查詢
    Optional<Order> findByOrderId(String orderId);

    List<Order> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<Order> findByStatusAndCreatedAtBetween(
        OrderStatus status, LocalDateTime start, LocalDateTime end);

    // 自訂查詢（MongoDB JSON 語法）
    @Query("{ 'customerId': ?0, 'totalAmount': { $gte: ?1 } }")
    List<Order> findHighValueOrdersByCustomer(String customerId, double minAmount);

    // 只取部分欄位（投影）
    @Query(value = "{ 'status': ?0 }", fields = "{ 'orderId': 1, 'totalAmount': 1, 'createdAt': 1 }")
    List<Order> findOrderSummaryByStatus(OrderStatus status);
}
```

---

## 四、MongoTemplate：複雜查詢

當 Repository 的方法名稱語法不夠用時，使用 `MongoTemplate`：

```java
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@Service
public class OrderService {

    private final MongoTemplate mongoTemplate;

    public OrderService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    // 複合查詢
    public List<Order> findOrders(String customerId, OrderStatus status,
                                   LocalDateTime from, LocalDateTime to) {
        Query query = new Query();

        // 建立查詢條件
        Criteria criteria = Criteria.where("customerId").is(customerId);
        if (status != null) {
            criteria.and("status").is(status);
        }
        if (from != null && to != null) {
            criteria.and("createdAt").gte(from).lte(to);
        }

        query.addCriteria(criteria);
        query.with(Sort.by(Sort.Direction.DESC, "createdAt"));
        query.limit(50);

        return mongoTemplate.find(query, Order.class);
    }

    // 部分欄位更新（只更新狀態，不影響其他欄位）
    public void updateOrderStatus(String orderId, OrderStatus newStatus) {
        Query query = Query.query(Criteria.where("orderId").is(orderId));
        Update update = new Update()
            .set("status", newStatus)
            .set("updatedAt", LocalDateTime.now())
            .push("statusHistory", newStatus.name());  // 追加到陣列

        mongoTemplate.updateFirst(query, update, Order.class);
    }

    // 查詢陣列內的元素
    public List<Order> findOrdersContainingProduct(String productId) {
        Query query = Query.query(
            Criteria.where("items.productId").is(productId)  // 查詢嵌入陣列
        );
        return mongoTemplate.find(query, Order.class);
    }
}
```

---

## 五、Aggregation Pipeline：強大的資料處理管道 <!-- 💡 進階 -->

Aggregation Pipeline 是 MongoDB 最強大的功能，把資料處理分成多個階段，像工廠流水線一樣：

```
輸入文件 → $match → $group → $sort → $project → 輸出結果
```

```java
import org.springframework.data.mongodb.core.aggregation.*;
import static org.springframework.data.mongodb.core.aggregation.Aggregation.*;

@Service
public class OrderAnalyticsService {

    private final MongoTemplate mongoTemplate;

    // 每月訂單統計
    public List<MonthlySales> getMonthlySales(int year) {
        Aggregation agg = newAggregation(
            // 第一階段：過濾今年的訂單
            match(Criteria.where("status").is("PAID")
                         .and("createdAt").gte(LocalDate.of(year, 1, 1).atStartOfDay())),

            // 第二階段：按月份分組，計算統計值
            group(
                dateOf("$createdAt").month().as("month")  // 按月分組
            )
            .count().as("orderCount")
            .sum("totalAmount").as("revenue")
            .avg("totalAmount").as("avgOrderValue"),

            // 第三階段：排序
            sort(Sort.Direction.ASC, "_id"),

            // 第四階段：重塑輸出結構
            project("orderCount", "revenue", "avgOrderValue")
                .and("_id").as("month")
        );

        return mongoTemplate.aggregate(agg, "orders", MonthlySales.class).getMappedResults();
    }

    // 熱門商品排行
    public List<ProductRanking> getTopProducts(int limit) {
        Aggregation agg = newAggregation(
            // 展開 items 陣列（一個訂單的每個商品變成一筆記錄）
            unwind("items"),

            // 按商品分組，累加銷售量
            group("items.productId")
                .first("items.name").as("productName")
                .sum("items.quantity").as("totalSold")
                .sum(
                    ArithmeticOperators.Multiply.valueOf("items.price")
                        .multiplyBy("items.quantity")
                ).as("totalRevenue"),

            // 排序取前 N
            sort(Sort.Direction.DESC, "totalRevenue"),
            limit(limit),

            project("productName", "totalSold", "totalRevenue")
                .and("_id").as("productId")
        );

        return mongoTemplate.aggregate(agg, "orders", ProductRanking.class).getMappedResults();
    }
}
```

---

## 六、索引策略 <!-- 💡 進階 -->

索引對 MongoDB 和 MySQL 同樣重要。

```java
// 在實體類上用注解建立索引
@Document(collection = "orders")
@CompoundIndex(name = "customer_status_idx", def = "{'customerId': 1, 'status': 1}")
@CompoundIndex(name = "time_status_idx", def = "{'createdAt': -1, 'status': 1}")
public class Order {
    @Id private String id;

    @Indexed(unique = true)
    private String orderId;

    @Indexed
    private String customerId;

    // 文字搜尋索引（支援全文搜尋）
    @TextIndexed
    private String description;
}
```

```java
// 或者用 MongoTemplate 手動建立
@Component
public class MongoIndexConfig {
    @Autowired
    private MongoTemplate mongoTemplate;

    @PostConstruct
    public void createIndexes() {
        MongoCollection<Document> collection = mongoTemplate
            .getCollection("orders");

        // 複合索引
        collection.createIndex(
            Indexes.compoundIndex(
                Indexes.ascending("customerId"),
                Indexes.descending("createdAt")
            ),
            new IndexOptions().name("customer_time_idx")
        );

        // TTL 索引（自動刪除過期文件）
        collection.createIndex(
            Indexes.ascending("expiresAt"),
            new IndexOptions().expireAfter(0L, TimeUnit.SECONDS)
        );
    }
}
```

**索引設計原則：**

| 原則 | 說明 |
|------|------|
| ESR 原則 | Equality → Sort → Range（複合索引欄位順序） |
| 覆蓋查詢 | 把所有需要的欄位都加入索引，避免回文件取資料 |
| 索引不是越多越好 | 每個索引都增加寫入開銷，一個 collection 通常 < 10 個索引 |
| TTL 索引 | 自動清理日誌/Session 等有過期時間的資料 |

---

## 七、複本集與分片 <!-- 🔴 資深 -->

### 複本集（Replica Set）：高可用

```
Primary（讀寫）
    ↓ 非同步複製 Oplog
Secondary 1（可讀取）
Secondary 2（可讀取）

當 Primary 掛掉：Secondary 互相投票選出新 Primary（需要 > 半數存活）
```

```yaml
# application.yml 讀寫分離設定
spring:
  data:
    mongodb:
      uri: mongodb://host1:27017,host2:27017,host3:27017/orderdb?replicaSet=rs0
      # 讀取偏好：secondaryPreferred = 優先從 Secondary 讀（降低 Primary 壓力）
      read-preference: secondaryPreferred
```

### 分片（Sharding）：水平擴展

```
           ┌── mongos（路由）
Client ────┤
           └── mongos（路由）
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
   Shard 1      Shard 2      Shard 3
 (customerId:   (customerId:  (customerId:
   A-H)          I-P)          Q-Z)
```

分片鍵選擇原則：
- **高基數**（cardinality 高）：避免熱點，如 `customerId` 比 `status` 好
- **均勻分布**：避免某個分片資料量遠大於其他
- **不可更改**：一旦設定不能修改文件的分片鍵值

---

## 八、MongoDB vs MySQL 選型指南

| 維度 | MySQL | MongoDB |
|------|-------|---------|
| 資料結構 | 固定 Schema，關聯表 | 彈性 Schema，文件 |
| 查詢能力 | SQL，JOIN 強 | Pipeline 強，JOIN 弱 |
| 事務 | 完整 ACID | MongoDB 4.0+ 支援多文件事務（但效能有代價） |
| 水平擴展 | 分庫分表（複雜） | 原生分片支援 |
| 適合場景 | 金融交易、ERP、需要複雜 JOIN | 內容管理、用戶行為、商品目錄、日誌 |
| 不適合場景 | 欄位不固定的資料 | 強一致性金融交易 |

**同時用兩種（混合架構）是非常常見的**：MySQL 存訂單/帳戶（強一致性），MongoDB 存商品資訊/用戶行為日誌（高彈性高吞吐）。

---

## 本章練習題

**Q1：MongoDB 的文件大小上限是多少？超過會怎樣？**
<details>
<summary>答案</summary>
MongoDB 單個文件的大小上限是 16MB。如果有個欄位（如 comments 陣列）可能無限成長，超過 16MB 後 insert/update 會報錯。解法：把會無限成長的資料拆到獨立的 collection，用引用（reference）代替嵌入（embedded）。例如文章和評論分開存，評論裡存 postId 引用文章。
</details>

**Q2：Aggregation Pipeline 的 $unwind 有什麼用途？**
<details>
<summary>答案</summary>
$unwind 將文件中的一個陣列欄位「展開」：一個含 3 個商品的訂單文件，經過 $unwind("items") 後變成 3 筆文件，每筆文件有一個商品。這讓你可以對陣列內的元素做 $group、$sort 等操作，例如統計每個商品的銷售總量。
</details>

**Q3：為什麼 MongoDB 的分片鍵不建議選 createdAt（時間）？**
<details>
<summary>答案</summary>
時間欄位做分片鍵會造成「熱點分片」（Hot Shard）：所有新寫入的資料永遠落在時間最大的那個分片，其他分片幾乎沒有寫入。這讓分片失去意義。應選擇高基數、均勻分布的欄位如 userId（按雜湊分片）或複合分片鍵。如果一定要包含時間，用 `{ userId: 1, createdAt: 1 }` 複合鍵，先按 userId 分散再按時間排序。
</details>
