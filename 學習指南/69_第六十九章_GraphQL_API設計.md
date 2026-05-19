# 第六十九章：GraphQL API 設計

## 前言：REST API 的不足之處

```
場景：手機 App 首頁需要顯示：
  - 用戶名稱、頭像
  - 最近 3 筆訂單（只需要 orderId, status, totalAmount）
  - 用戶的積分餘額

REST API 的做法：
  GET /users/123             → 返回 用戶全部欄位（20 個欄位，但只需要 2 個）
  GET /users/123/orders      → 返回 所有訂單（但只需要最近 3 筆的 3 個欄位）
  GET /users/123/points      → 返回 積分詳情（又一次請求）

問題：
  ❌ Over-fetching：每個 API 都返回一堆用不到的欄位，浪費頻寬
  ❌ Under-fetching：一頁需要打 3 個 API（N+1 問題）
  ❌ 前端換一個需求，後端要改 API 或加新 endpoint

GraphQL 的解法：
  一個請求，精確指定需要什麼欄位：

  query {
    user(id: "123") {
      name
      avatar
      recentOrders(limit: 3) {
        orderId
        status
        totalAmount
      }
      pointBalance
    }
  }

  → 一次請求，剛好返回需要的所有數據，不多不少
```

---

## 一、GraphQL 核心概念

```
Schema（型別定義）：
  type User {
    id: ID!                  # ! = 非空（non-null）
    name: String!
    email: String!
    orders(limit: Int): [Order]  # 可帶參數的欄位
    pointBalance: Int
  }

  type Order {
    orderId: ID!
    status: OrderStatus!
    totalAmount: Float!
    items: [OrderItem]
  }

  enum OrderStatus { PENDING, PAID, SHIPPED, COMPLETED }

三種根操作：
  Query（查詢）：只讀，對應 REST GET
  Mutation（變更）：寫入，對應 REST POST/PUT/DELETE
  Subscription（訂閱）：實時推送，WebSocket 長連線
```

---

## 二、Spring Boot 整合 GraphQL

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-graphql</artifactId>
</dependency>
```

```graphql
# src/main/resources/graphql/schema.graphqls

type Query {
  user(id: ID!): User
  order(orderId: ID!): Order
  orders(customerId: ID!, limit: Int = 10): [Order]
}

type Mutation {
  createOrder(input: CreateOrderInput!): Order
  cancelOrder(orderId: ID!): Boolean
}

type Subscription {
  orderStatusUpdated(orderId: ID!): Order
}

type User {
  id: ID!
  name: String!
  email: String!
  orders(limit: Int): [Order]
  pointBalance: Int
}

type Order {
  orderId: ID!
  customerId: ID!
  status: OrderStatus!
  totalAmount: Float!
  items: [OrderItem]
  createdAt: String
}

type OrderItem {
  productId: ID!
  productName: String!
  quantity: Int!
  unitPrice: Float!
}

enum OrderStatus {
  PENDING
  PAID
  SHIPPED
  COMPLETED
  CANCELLED
}

input CreateOrderInput {
  customerId: ID!
  items: [OrderItemInput!]!
}

input OrderItemInput {
  productId: ID!
  quantity: Int!
}
```

```java
// Query Resolver（處理查詢）
@Controller
public class UserGraphQLController {

    private final UserService userService;
    private final OrderService orderService;

    // 對應 schema 裡的 Query.user(id: ID!): User
    @QueryMapping
    public User user(@Argument String id) {
        return userService.findById(id);
    }

    // 對應 User.orders(limit: Int): [Order]（欄位解析器）
    @SchemaMapping(typeName = "User", field = "orders")
    public List<Order> userOrders(User user, @Argument Integer limit) {
        int actualLimit = limit != null ? limit : 10;
        return orderService.findByCustomerId(user.getId(), actualLimit);
    }
}

// Mutation Resolver（處理寫入）
@Controller
public class OrderGraphQLController {

    private final OrderService orderService;

    @MutationMapping
    public Order createOrder(@Argument CreateOrderInput input) {
        return orderService.createOrder(input);
    }

    @MutationMapping
    public Boolean cancelOrder(@Argument String orderId) {
        orderService.cancelOrder(orderId);
        return true;
    }

    // Subscription（實時推送）
    @SubscriptionMapping
    public Flux<Order> orderStatusUpdated(@Argument String orderId) {
        // 返回 Flux<Order>，Spring GraphQL 自動轉成 WebSocket 推送
        return orderEventService.subscribeToOrderUpdates(orderId);
    }
}
```

---

## 三、N+1 問題與 DataLoader

GraphQL 最常見的性能問題：查詢 10 個訂單，每個訂單都查商品名稱 → 11 次資料庫查詢：

```java
// ❌ N+1 問題
@SchemaMapping(typeName = "Order", field = "items")
public List<OrderItem> orderItems(Order order) {
    // 每個 Order 各自查一次資料庫（10 個訂單 = 10 次查詢）
    return orderItemRepository.findByOrderId(order.getOrderId());
}

// ✅ 使用 DataLoader 批次查詢（1 次查詢搞定 10 個訂單的商品）
@SchemaMapping(typeName = "Order", field = "items")
public CompletableFuture<List<OrderItem>> orderItems(
        Order order, DataLoader<String, List<OrderItem>> orderItemLoader) {
    // DataLoader 自動收集本次請求裡的所有 orderId，批次查詢
    return orderItemLoader.load(order.getOrderId());
}

// 註冊 DataLoader（批次查詢的實作）
@Bean
public BatchLoaderRegistry batchLoaderRegistry(OrderItemRepository repo) {
    return registrar -> registrar
        .forTypePair(String.class, List.class)
        .withName("orderItemLoader")
        .registerBatchLoader((orderIds, env) -> {
            // 一次查詢所有 orderId 的商品
            Map<String, List<OrderItem>> itemsByOrder = repo
                .findByOrderIdIn(orderIds)
                .stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId));

            // 按原始 orderId 順序返回結果
            return Mono.just(orderIds.stream()
                .map(id -> itemsByOrder.getOrDefault(id, Collections.emptyList()))
                .collect(Collectors.toList()));
        });
}
```

---

## 四、GraphQL vs REST 選型

| 維度 | REST | GraphQL |
|------|------|---------|
| **資料獲取精確度** | 固定（後端決定）| 精確（前端決定）|
| **請求次數** | 多個 endpoint | 一個 endpoint |
| **Over/Under-fetching** | 常見問題 | 解決了 |
| **型別系統** | 靠 OpenAPI/Swagger | 內建強型別 Schema |
| **瀏覽器快取** | ✅（GET 請求可快取）| ❌（POST 請求難快取）|
| **檔案上傳** | ✅（multipart）| ❌（需要特殊處理）|
| **學習成本** | 低 | 中 |
| **監控/除錯** | 簡單（curl/Postman）| 需要 GraphiQL/Apollo Studio |

**選型建議：**
```
選 GraphQL 的場景：
  ✅ 前端有多個客戶端（Web、iOS、Android）需求不同
  ✅ 資料關係複雜，前端需要靈活組合查詢
  ✅ 快速迭代的產品（前端可以自由取得需要的欄位，不需要後端改 API）

選 REST 的場景：
  ✅ 對外開放的 Public API（生態更成熟）
  ✅ 需要 HTTP 快取（CDN 快取 GET 請求）
  ✅ 簡單的 CRUD 服務（GraphQL 的複雜度不值得）
  ✅ 檔案上傳/下載

最佳實踐：
  內部 BFF（Backend For Frontend）層用 GraphQL
  對外 Public API 繼續用 REST
```

---

## 本章練習題

**Q1：GraphQL 的 Schema 定義中，`!` 的作用是什麼？**
<details>
<summary>答案</summary>
`!` 表示 Non-Null（非空），有兩層含義：(1) 在 type 定義中，`name: String!` 表示 Server 保證這個欄位永遠不為 null，客戶端不需要做 null 檢查；(2) 在查詢參數中，`user(id: ID!)` 表示這個參數是必填的，不傳會在 Schema 驗證時就報錯（不等到 Resolver 執行）。如果沒有 `!`，`name: String` 表示這個欄位可以是 null（Optional），客戶端需要做 null 判斷。
</details>

**Q2：為什麼 GraphQL 不能直接替代所有 REST API？**
<details>
<summary>答案</summary>
(1) HTTP 快取：REST 的 GET 請求可以被 CDN 和瀏覽器快取，大幅降低伺服器壓力。GraphQL 幾乎所有請求都是 POST，傳統 HTTP 快取層無法識別，需要應用層自己實作查詢快取（如 Apollo Client 的 normalized cache）。(2) 檔案上傳：REST multipart 上傳非常成熟，GraphQL 上傳檔案需要用 multipart spec 擴展，設定複雜。(3) 簡單查詢的額外開銷：GraphQL Runtime 需要解析 Query、走 Schema 驗證、執行 Resolver 鏈，比直接的 REST 請求有更多的計算開銷，對非常簡單的 CRUD 不值得。(4) 第三方生態：很多服務（Webhook、OAuth 回調）期望 REST 介面，GraphQL 接入需要轉換層。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 27 章 | Phase 4：資料庫 + Spring 後端
> 下一章（第 28 章）：[第三十章：JWT 與 Spring Security](30_第三十章_JWT與SpringSecurity.md)
