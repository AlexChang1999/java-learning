# 第二十二章：REST API 設計
> 前後端溝通的語言：設計讓人看得懂、用得安心的 API

---

## 前言：什麼是 REST？

REST（Representational State Transfer）不是一個標準，是一種**設計風格**。

```
核心思想：用 HTTP 的既有語義，而不是自己發明一套通訊協定

壞的設計（RPC 風格）：
  POST /api/createOrder
  POST /api/cancelOrder
  POST /api/getOrderStatus
  POST /api/updateOrderQuantity

好的設計（REST 風格）：
  POST   /api/orders          — 建立訂單
  DELETE /api/orders/{id}     — 取消訂單
  GET    /api/orders/{id}     — 查詢訂單
  PATCH  /api/orders/{id}     — 修改訂單部分欄位
```

---

## 一、HTTP 方法的語義

```
GET    — 讀取資源，不改變狀態（冪等）
POST   — 建立新資源
PUT    — 完整替換資源（冪等）
PATCH  — 部分更新資源
DELETE — 刪除資源（冪等）

冪等：多次執行結果相同
  GET   /orders/O-001  — 呼叫 100 次，結果都一樣 ✅ 冪等
  DELETE /orders/O-001 — 第一次刪除成功，之後都是 404，但資源狀態相同 ✅ 冪等
  POST  /orders        — 每次呼叫都建立一筆新訂單 ❌ 不冪等
```

---

## 二、URL 設計原則

```
資源用名詞，動作由 HTTP 方法表達

✅ 好的 URL：
  GET    /orders               — 訂單列表
  GET    /orders/O-001         — 單一訂單
  POST   /orders               — 新增訂單
  DELETE /orders/O-001         — 取消訂單
  GET    /orders/O-001/trades  — 某訂單的成交記錄（子資源）
  GET    /symbols/TSLA/orderbook  — TSLA 的訂單簿

❌ 壞的 URL（動詞出現在 URL 中）：
  POST  /createOrder
  GET   /getOrderById?id=O-001
  POST  /cancelOrder/O-001
  GET   /fetchAllActiveOrders

URL 使用 kebab-case（小寫 + 連字號）：
  /order-book         ✅
  /orderBook          ❌（不統一，不同語言習慣不同）
  /order_book         ❌（部分系統可能有問題）
```

### 版本控制

```
# 方案一：URL 前綴（最常見）
/api/v1/orders
/api/v2/orders   ← 破壞性變更時，用新版本

# 方案二：Header（更符合 REST 精神，但較少用）
Accept: application/vnd.exchange.v2+json
```

---

## 三、HTTP 狀態碼

```
2xx — 成功
  200 OK           — 一般成功（GET 查詢）
  201 Created      — 建立成功（POST 新增）
  204 No Content   — 成功但沒有回應體（DELETE 刪除）

4xx — 客戶端錯誤（你的問題）
  400 Bad Request  — 請求格式或參數錯誤
  401 Unauthorized — 未認證（沒有帶 token）
  403 Forbidden    — 已認證但無權限
  404 Not Found    — 資源不存在
  409 Conflict     — 資源狀態衝突（訂單已取消不能再取消）
  422 Unprocessable Entity — 格式正確但業務邏輯拒絕

5xx — 伺服器錯誤（我的問題）
  500 Internal Server Error — 未預期的伺服器錯誤
  503 Service Unavailable   — 服務暫時不可用（過載或維護中）
```

**實際應用到撮合引擎：**

```
POST /api/orders
  → 201 Created（新訂單成功加入）
  → 400 Bad Request（數量為負數）
  → 422 Unprocessable Entity（FOK 數量不足，整筆取消）

DELETE /api/orders/{id}
  → 204 No Content（取消成功）
  → 404 Not Found（訂單不存在）
  → 409 Conflict（訂單已成交，無法取消）
```

---

## 四、Request / Response 設計

### Request Body（輸入）

```json
// POST /api/orders
{
  "symbol": "TSLA",
  "side": "BUY",
  "type": "LIMIT",
  "price": 350.00,
  "quantity": 100
}
```

**設計原則：**
- 欄位名用 `camelCase`（JSON 的慣例）
- 數字用數字型別，不要用字串（`350.00` 不是 `"350.00"`）
- 不要在請求裡帶 `orderId`（讓伺服器產生，避免衝突）

### Response Body（輸出）

```json
// 201 Created
{
  "orderId": "O-20240512-001",
  "symbol": "TSLA",
  "side": "BUY",
  "type": "LIMIT",
  "price": 350.00,
  "quantity": 100,
  "status": "ACTIVE",
  "trades": [],
  "createdAt": "2024-05-12T10:30:00.123Z"
}

// 有立即成交的情況
{
  "orderId": "O-20240512-002",
  "status": "FILLED",
  "trades": [
    {
      "tradeId": "T-001",
      "price": 350.00,
      "quantity": 100,
      "executedAt": "2024-05-12T10:30:00.456Z"
    }
  ]
}
```

### 錯誤回應要統一格式

```json
// 400 Bad Request
{
  "error": {
    "code": "INVALID_QUANTITY",
    "message": "訂單數量必須大於 0，收到：-50",
    "timestamp": "2024-05-12T10:30:00.000Z"
  }
}

// 不要這樣（每個 API 格式不同，前端難以統一處理）：
// {"msg": "error"}
// {"errorMessage": "bad request"}
// "quantity must be positive"   ← 直接回字串更糟
```

---

## 五、查詢參數設計（分頁、篩選、排序）

```
GET /api/orders?symbol=TSLA&status=ACTIVE&page=0&size=20&sort=createdAt,desc

篩選（Filter）：
  symbol=TSLA        — 只回傳 TSLA 的訂單
  status=ACTIVE      — 只回傳活躍訂單
  side=BUY           — 只回傳買單

分頁（Pagination）：
  page=0&size=20     — 第 0 頁，每頁 20 筆（從 0 起算）
  limit=20&offset=40 — 跳過前 40 筆，取 20 筆（另一種常見格式）

排序（Sorting）：
  sort=createdAt,desc  — 依建立時間降冪排序
  sort=price,asc       — 依價格升冪排序
```

**回應格式（帶分頁資訊）：**

```json
{
  "content": [...],           // 當頁資料
  "page": {
    "number": 0,              // 目前頁碼
    "size": 20,               // 每頁筆數
    "totalElements": 1543,    // 總筆數
    "totalPages": 78          // 總頁數
  }
}
```

---

## 六、完整的 Spring Boot 實作

```java
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final TradingService service;
    private final OrderRepository repo;

    public OrderController(TradingService service, OrderRepository repo) {
        this.service = service;
        this.repo    = repo;
    }

    // POST /api/v1/orders
    @PostMapping
    public ResponseEntity<OrderResponse> create(
            @RequestBody @Valid OrderRequest req) {
        Order order = OrderFactory.create(
            UUID.randomUUID().toString(),
            req.getSymbol(), req.getSide(), req.getType(),
            req.getPrice(), req.getQuantity()
        );
        List<Trade> trades = service.submitOrder(order);
        return ResponseEntity.status(201).body(new OrderResponse(order, trades));
    }

    // GET /api/v1/orders/{orderId}
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOne(@PathVariable String orderId) {
        return repo.findById(orderId)
            .map(ResponseEntity::ok)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    // GET /api/v1/orders?symbol=TSLA&status=ACTIVE&page=0&size=20
    @GetMapping
    public Page<Order> list(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return repo.findBySymbolAndStatus(
            symbol, status, PageRequest.of(page, size, Sort.by("createdAt").descending())
        );
    }

    // DELETE /api/v1/orders/{orderId}
    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> cancel(@PathVariable String orderId) {
        if (!service.cancelOrder(orderId)) {
            throw new OrderNotFoundException(orderId);
        }
        return ResponseEntity.noContent().build();  // 204
    }
}
```

### Bean Validation：自動驗證請求

```java
import jakarta.validation.constraints.*;

public class OrderRequest {
    @NotBlank(message = "symbol 不能為空")
    private String symbol;

    @Pattern(regexp = "BUY|SELL", message = "side 必須是 BUY 或 SELL")
    private String side;

    @Pattern(regexp = "LIMIT|MARKET|IOC|FOK")
    private String type;

    @Positive(message = "price 必須大於 0")
    private double price;

    @Min(value = 1, message = "quantity 最少為 1")
    @Max(value = 100_000, message = "quantity 最多為 100,000")
    private int quantity;

    // Getters and Setters...
}

// Controller 加上 @Valid 就會自動觸發驗證
// 驗證失敗時自動回傳 400 Bad Request
@PostMapping
public ResponseEntity<OrderResponse> create(@RequestBody @Valid OrderRequest req) { ... }
```

---

## 七、Swagger / OpenAPI：讓 API 自己說話

寫完 API 還需要手動維護一份 Word 文件告訴前端「這個端點要傳什麼」——這是很多團隊的痛苦。**Swagger UI** 讓你的程式碼自動生成互動式 API 文件，前端可以直接在瀏覽器裡試打 API。

```xml
<!-- pom.xml：加入 springdoc-openapi（比 springfox 更現代，支援 Spring Boot 3）-->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>2.3.0</version>
</dependency>
```

```yaml
# application.yml
springdoc:
  api-docs:
    path: /api-docs          # JSON 格式的 API spec
  swagger-ui:
    path: /swagger-ui.html   # Swagger UI 介面
    operations-sorter: method
```

加完依賴後直接啟動，打開 `http://localhost:8080/swagger-ui.html` 就能看到所有 API。

**用注解增加說明：**

```java
import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

@Tag(name = "訂單管理", description = "建立、查詢、取消訂單")  // API 分組
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Operation(
        summary = "建立訂單",
        description = "提交一個新的限價單或市價單，若庫存充足立即撮合"
    )
    @ApiResponse(responseCode = "201", description = "訂單建立成功")
    @ApiResponse(responseCode = "400", description = "請求格式錯誤")
    @ApiResponse(responseCode = "401", description = "未登入")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderDTO createOrder(
        @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "訂單內容，side 只能是 BUY 或 SELL"
        )
        @RequestBody @Valid CreateOrderRequest request
    ) {
        return orderService.createOrder(request);
    }

    @Operation(summary = "查詢訂單", description = "根據訂單 ID 查詢單一訂單")
    @Parameter(name = "id", description = "訂單的唯一 ID", example = "ORD-20240115-001")
    @GetMapping("/{id}")
    public OrderDTO getOrder(@PathVariable String id) {
        return orderService.findById(id);
    }
}
```

**在 DTO 上增加欄位說明：**

```java
public class CreateOrderRequest {

    @Schema(description = "商品 ID", example = "PRD-001", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank
    private String productId;

    @Schema(description = "購買數量，必須大於 0", example = "2", minimum = "1")
    @Min(1)
    private int quantity;

    @Schema(description = "下單方向", allowableValues = {"BUY", "SELL"})
    private String side;
}
```

**Security 設定：讓 Swagger 能帶 JWT Token：**

```java
@Configuration
public class SwaggerConfig {
    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
            .info(new Info()
                .title("撮合引擎 API")
                .version("1.0.0")
                .description("提供訂單管理、市場行情查詢功能"))
            .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
            .components(new Components()
                .addSecuritySchemes("bearerAuth",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")));
    }
}
```

設定完後，Swagger UI 右上角會出現「Authorize」按鈕，貼入 JWT Token 後就能在 UI 裡直接測試需要認證的 API。

---

## 八、API 設計清單（上線前自我檢查）

```
URL 設計：
  ✅ 用名詞，不用動詞
  ✅ 複數資源用複數（/orders，不是 /order）
  ✅ URL 有版本號（/api/v1/）
  ✅ kebab-case 命名

HTTP 方法：
  ✅ GET 只讀，不改狀態
  ✅ POST 建立，PUT/PATCH 更新，DELETE 刪除

回應：
  ✅ 狀態碼語意正確（建立用 201，刪除用 204，不是全部用 200）
  ✅ 錯誤回應有統一格式（code + message）
  ✅ 成功回應有 createdAt / updatedAt 時間戳

安全：
  ✅ 輸入驗證（@Valid + @NotNull 等）
  ✅ 不回傳不必要的敏感欄位（密碼、內部 ID）
  ✅ 考慮認證（JWT Token）
```

---

## 本章練習題

**Q1：以下哪個 API 設計比較好？為什麼？**
```
A: POST /api/cancelOrder?orderId=O-001
B: DELETE /api/orders/O-001
```
<details>
<summary>答案</summary>
B 更好。原因：(1) URL 應該是名詞（/orders/O-001），動作由 HTTP 方法（DELETE）表達；(2) 使用路徑變數（/O-001）比查詢參數（?orderId=O-001）更直觀，明確表示這是一個資源的 URL；(3) DELETE 是冪等的，重複呼叫不會產生副作用，符合 REST 語義；(4) A 的設計無法利用 HTTP 快取和代理伺服器的機制（它們依賴 HTTP 方法判斷請求特性）。
</details>

**Q2：POST 和 PUT 都可以用來建立資源，它們有什麼差異？**
<details>
<summary>答案</summary>
PUT 是冪等的——用相同的請求呼叫多次結果相同；POST 不是冪等的——每次呼叫都可能建立新資源。PUT 通常用在「客戶端指定 ID」的情況：PUT /orders/O-001（我知道這個 ID，幫我建立或替換這個資源）。POST 用在「伺服器產生 ID」的情況：POST /orders（幫我建立一個訂單，ID 由你決定）。撮合引擎的訂單 ID 通常由伺服器產生，所以用 POST。如果是幂等的設定更新（如更新系統參數），用 PUT 更適合。
</details>

**Q3：設計一個「查詢 TSLA 訂單簿（最優五檔）」的 API endpoint，包含 URL、HTTP 方法和回應格式。**
<details>
<summary>答案</summary>

```
GET /api/v1/orderbook/TSLA?depth=5

回應（200 OK）：
{
  "symbol": "TSLA",
  "timestamp": "2024-05-12T10:30:00.123Z",
  "asks": [
    {"price": 351.00, "quantity": 200},
    {"price": 352.00, "quantity": 150},
    ...
  ],
  "bids": [
    {"price": 350.00, "quantity": 100},
    {"price": 349.00, "quantity": 300},
    ...
  ]
}
```

設計理由：用 GET（只讀），symbol 放 path variable（它是資源識別符），depth 放 query parameter（它是修飾符，不是識別符）。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 26 章 | Phase 4：資料庫 + Spring 後端
> 下一章（第 27 章）：[第六十九章：GraphQL API 設計](69_第六十九章_GraphQL_API設計.md)
