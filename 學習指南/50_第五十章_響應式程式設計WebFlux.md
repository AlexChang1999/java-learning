# 第五十章：響應式程式設計（Project Reactor + WebFlux）

## 前言：傳統 Spring MVC 的瓶頸

傳統 Spring MVC 是**同步阻塞**模型：

```
每個 HTTP 請求 → 佔用一個 Thread
Thread 等待 DB 回應（假設需要 50ms）→ Thread 被阻塞，什麼都不能做

Tomcat 預設 200 個 Thread
200 個 Thread 全在等 DB → 第 201 個請求被迫排隊
```

這不是 CPU 慢，而是 **Thread 資源浪費在等待 I/O 上**。

**響應式（Reactive）模型的答案：**

```
少量 Thread（通常 = CPU 核心數）
每個 Thread 不阻塞，發出 I/O 請求後立刻去處理別的事
I/O 完成時收到通知，再繼續處理這個請求
```

這就是 Node.js 的核心思想，現在 Java 也有了：**Project Reactor + Spring WebFlux**。

---

## 一、核心概念：Mono 和 Flux

Project Reactor 的兩個核心類型：

```
Mono<T>  = 0 或 1 個值的非同步序列
Flux<T>  = 0 到 N 個值的非同步序列
```

類比：
- `Mono<User>` ≈ 非同步版的 `CompletableFuture<User>`
- `Flux<Order>` ≈ 非同步版的 `Stream<Order>`，但元素可以隨時間陸續到達

```java
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

// 建立 Mono
Mono<String> mono1 = Mono.just("Hello");          // 包含一個值
Mono<String> mono2 = Mono.empty();                // 空（沒有值）
Mono<String> mono3 = Mono.error(new RuntimeException("錯誤"));

// 建立 Flux
Flux<Integer> flux1 = Flux.just(1, 2, 3, 4, 5);
Flux<Integer> flux2 = Flux.range(1, 100);         // 1 到 100
Flux<Long> flux3 = Flux.interval(Duration.ofSeconds(1));  // 每秒發出一個數字

// ⚠️ 重要：Mono/Flux 是懶惰的（lazy）！
// 上面這些只是「定義了一條管道」，還沒有任何事情發生
// 只有訂閱（subscribe）後才真正執行
```

---

## 二、操作符：處理資料的管道

```java
// 和 Stream API 很像，但是非同步的
Flux<String> result = Flux.just("apple", "banana", "cherry")
    .filter(s -> s.length() > 5)                     // 過濾
    .map(String::toUpperCase)                         // 轉換
    .flatMap(s -> fetchFromDB(s))                    // 非同步 map（重要）
    .take(3)                                          // 只取前 3 個
    .timeout(Duration.ofSeconds(5))                   // 超時
    .onErrorReturn("預設值");                          // 錯誤時回傳預設值

// 訂閱（啟動執行）
result.subscribe(
    value -> System.out.println("收到：" + value),   // onNext
    error -> System.err.println("錯誤：" + error),   // onError
    () -> System.out.println("完成！")               // onComplete
);
```

### map vs flatMap（最重要的區別）

```java
// map：同步轉換，返回值直接包在 Flux 裡
Flux<String> names = Flux.just(1L, 2L, 3L)
    .map(id -> "User-" + id);   // 返回 String，不是 Mono<String>

// flatMap：非同步轉換，函數返回 Mono/Flux，會被「展平」
Flux<User> users = Flux.just(1L, 2L, 3L)
    .flatMap(id -> userRepository.findById(id));  // 返回 Mono<User>，展平後是 Flux<User>
    // flatMap 並行執行多個 Mono（訂單不保證）

// concatMap：和 flatMap 類似，但保持順序（串行執行）
Flux<User> usersOrdered = Flux.just(1L, 2L, 3L)
    .concatMap(id -> userRepository.findById(id));
```

---

## 三、Spring WebFlux：非阻塞 HTTP 服務 <!-- 💡 進階 -->

WebFlux 和 Spring MVC 的程式碼幾乎一樣，只是回傳值改成 `Mono` 或 `Flux`：

```xml
<!-- pom.xml：用 webflux 替換 web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
    <!-- 內建 Netty 伺服器（非阻塞），不是 Tomcat -->
</dependency>
```

### Controller 層

```java
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    // 查詢單一訂單（Mono = 0 or 1 個結果）
    @GetMapping("/{id}")
    public Mono<Order> getOrder(@PathVariable String id) {
        return orderService.findById(id);
        // 注意：這裡直接 return Mono，框架知道如何非同步處理
        // 不需要 .subscribe()，WebFlux 會幫你訂閱
    }

    // 查詢訂單列表（Flux = 0 to N 個結果）
    @GetMapping("/customer/{customerId}")
    public Flux<Order> getOrdersByCustomer(@PathVariable String customerId) {
        return orderService.findByCustomerId(customerId);
    }

    // 建立訂單
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Order> createOrder(@RequestBody Mono<CreateOrderRequest> requestMono) {
        return requestMono
            .flatMap(req -> orderService.createOrder(req));
    }

    // SSE（Server-Sent Events）：即時推送資料流給瀏覽器
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Order> streamOrders() {
        return orderService.getOrderStream();  // 持續發送新訂單
    }
}
```

### Service 層：組合非同步操作

```java
@Service
public class OrderService {

    private final ReactiveOrderRepository orderRepository;    // R2DBC
    private final ReactiveInventoryClient inventoryClient;    // WebClient

    // 建立訂單：需要先查庫存，再建訂單（鏈式非同步操作）
    public Mono<Order> createOrder(CreateOrderRequest req) {
        return inventoryClient.checkStock(req.getProductId(), req.getQuantity())
            .filter(available -> available)                       // 庫存足夠才繼續
            .switchIfEmpty(Mono.error(new InsufficientStockException()))
            .flatMap(available -> {
                Order order = Order.from(req);
                return orderRepository.save(order);               // 儲存訂單
            })
            .flatMap(order ->
                inventoryClient.deductStock(req.getProductId(), req.getQuantity())
                    .thenReturn(order)                            // 扣庫存後回傳訂單
            )
            .doOnSuccess(order ->
                log.info("訂單建立成功: {}", order.getId())       // 副作用（不影響流程）
            )
            .doOnError(e ->
                log.error("訂單建立失敗", e)
            );
    }

    // 同時查詢多個服務（並行）
    public Mono<OrderDetail> getOrderDetail(String orderId) {
        Mono<Order> orderMono = orderRepository.findById(orderId);
        Mono<User> userMono = userRepository.findById(/* userId from order */);
        Mono<List<Product>> productsMono = productRepository.findByOrderId(orderId);

        // zip：等三個 Mono 都完成後，合併結果（並行執行，不是串行！）
        return Mono.zip(orderMono, userMono, productsMono)
            .map(tuple -> new OrderDetail(
                tuple.getT1(),  // Order
                tuple.getT2(),  // User
                tuple.getT3()   // Products
            ));
    }
}
```

---

## 四、R2DBC：非阻塞資料庫存取 <!-- 💡 進階 -->

傳統 JDBC 是阻塞的（`connection.execute()` 會 block thread）。R2DBC（Reactive Relational Database Connectivity）是非阻塞版本：

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-r2dbc</artifactId>
</dependency>
<dependency>
    <groupId>io.asyncer</groupId>
    <artifactId>r2dbc-mysql</artifactId>
</dependency>
```

```yaml
spring:
  r2dbc:
    url: r2dbc:mysql://localhost:3306/orderdb
    username: root
    password: password
  sql:
    init:
      schema-locations: classpath:schema.sql
```

```java
// 實體類（和 JPA 不同，使用 Spring Data R2DBC 的注解）
@Table("orders")
public class Order {
    @Id
    private Long id;
    private String orderId;
    private String customerId;
    private OrderStatus status;
    private LocalDateTime createdAt;
}

// Repository：返回 Mono/Flux
public interface ReactiveOrderRepository extends ReactiveCrudRepository<Order, Long> {

    Flux<Order> findByCustomerId(String customerId);

    Mono<Order> findByOrderId(String orderId);

    @Query("SELECT * FROM orders WHERE status = :status AND created_at >= :from")
    Flux<Order> findRecentByStatus(String status, LocalDateTime from);
}
```

---

## 五、WebClient：非阻塞 HTTP 呼叫 <!-- 💡 進階 -->

響應式環境下，不能用阻塞的 `RestTemplate`，要用 `WebClient`：

```java
@Component
public class InventoryWebClient {

    private final WebClient webClient;

    public InventoryWebClient(WebClient.Builder builder) {
        this.webClient = builder
            .baseUrl("http://inventory-service")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    // GET 請求
    public Mono<InventoryDTO> getInventory(Long productId) {
        return webClient.get()
            .uri("/inventory/{id}", productId)
            .retrieve()
            .onStatus(HttpStatus::is4xxClientError,
                response -> Mono.error(new ProductNotFoundException()))
            .onStatus(HttpStatus::is5xxServerError,
                response -> Mono.error(new ServiceUnavailableException()))
            .bodyToMono(InventoryDTO.class)
            .timeout(Duration.ofSeconds(3))
            .retryWhen(Retry.backoff(3, Duration.ofMillis(500)));  // 失敗重試 3 次
    }

    // POST 請求
    public Mono<Void> deductStock(Long productId, int quantity) {
        return webClient.post()
            .uri("/inventory/deduct")
            .bodyValue(new DeductRequest(productId, quantity))
            .retrieve()
            .bodyToMono(Void.class);
    }
}
```

---

## 六、背壓（Backpressure）：流量控制 <!-- 🔴 資深 -->

**背壓是響應式程式設計的核心概念**，解決生產者太快、消費者太慢的問題。

```
問題：
  資料來源每秒產生 10,000 個事件
  下游每秒只能處理 1,000 個
  → 多出來的 9,000 個怎麼辦？

背壓策略：
  BUFFER  - 緩衝（有限佇列，超過就 drop 或等待）
  DROP    - 直接丟棄超出的元素
  LATEST  - 只保留最新的（即時資料更有價值）
  ERROR   - 拋出 OverflowException
```

```java
// WebFlux 在 Controller 中自動支援背壓（客戶端請求速度控制服務端發送速度）

// 手動控制背壓策略
Flux<Event> hotStream = getHighVolumeStream()
    .onBackpressureBuffer(1000)          // 緩衝最多 1000 個
    // .onBackpressureDrop()             // 直接丟棄
    // .onBackpressureLatest()           // 只保留最新的
    .publishOn(Schedulers.boundedElastic())  // 在獨立的 thread pool 消費
    .delayElements(Duration.ofMillis(10));   // 人工限速
```

---

## 七、Scheduler：在哪個 Thread 執行 <!-- 🔴 資深 -->

Reactor 的操作預設在呼叫鏈的 Thread 上執行，但可以指定：

```java
Flux.range(1, 100)
    .publishOn(Schedulers.parallel())     // 之後的操作在 parallel thread pool（CPU 密集）
    .map(i -> compute(i))
    .subscribeOn(Schedulers.boundedElastic())  // 訂閱時用這個 scheduler 開始
    // boundedElastic：I/O 密集任務（DB/HTTP 等阻塞操作）

// 四種 Scheduler：
// Schedulers.immediate()        - 目前 Thread（預設）
// Schedulers.single()           - 單一後台 Thread
// Schedulers.parallel()         - CPU 核心數的 Thread Pool（CPU bound）
// Schedulers.boundedElastic()   - 彈性大小的 Thread Pool（I/O bound，阻塞操作用這個）
```

**重要原則：Reactive 管道中絕對不能有阻塞操作！**

```java
// ❌ 錯誤：在 Reactive 管道中阻塞
Flux.just(1, 2, 3)
    .map(i -> {
        Thread.sleep(100);  // 阻塞了 Netty 的 I/O Thread！整個服務卡死
        return i * 2;
    });

// ✅ 正確：阻塞操作包裝到 boundedElastic
Flux.just(1, 2, 3)
    .flatMap(i ->
        Mono.fromCallable(() -> {
            Thread.sleep(100);  // 可以阻塞，因為在 boundedElastic
            return i * 2;
        }).subscribeOn(Schedulers.boundedElastic())
    );
```

---

## 八、Spring MVC vs WebFlux 選型

| | Spring MVC | Spring WebFlux |
|---|---|---|
| 程式設計模型 | 同步阻塞 | 非同步非阻塞 |
| 學習曲線 | 低 | 高（需要理解 Reactive 思維） |
| 適合場景 | 一般 CRUD、業務邏輯複雜 | 大量 I/O、高並發、SSE/WebSocket |
| 除錯難度 | 易（Stack trace 清晰） | 難（Stack trace 跨 Thread） |
| DB 支援 | JPA（成熟） | R2DBC（較新，部分功能少） |
| 生態系統 | 豐富 | 持續完善 |

**結論：不是所有服務都需要 WebFlux**。大多數業務服務用 Spring MVC 就好。WebFlux 適合：
- API Gateway（大量轉發，幾乎純 I/O）
- SSE / WebSocket 即時推送服務
- 需要同時聚合多個下游服務的 BFF（Backend For Frontend）

---

## 本章練習題

**Q1：Mono.zip() 和 flatMap() 有什麼根本差異？**
<details>
<summary>答案</summary>
flatMap() 是串行的：前一個操作完成後，才用結果去執行下一個操作。適合操作之間有依賴關係（如先查庫存，再建訂單）。

Mono.zip() 是並行的：同時啟動多個 Mono，等全部完成後把結果合併。適合互相獨立的操作（如同時查使用者資訊、查訂單、查商品），可以大幅降低總延遲。

例：若三個查詢各需要 100ms，串行需要 300ms，zip() 並行只需要約 100ms。
</details>

**Q2：為什麼在 WebFlux 管道中執行阻塞操作（如 JDBC）會導致嚴重問題？**
<details>
<summary>答案</summary>
WebFlux 底層使用 Netty，只有少量 I/O Thread（通常等於 CPU 核心數）。如果在這些 Thread 上執行阻塞操作，Thread 被佔用，無法處理其他請求，整個服務實際上退化成比 Tomcat 還差（因為 Thread 數更少）。解法是把阻塞操作包在 Mono.fromCallable() 並 subscribeOn(Schedulers.boundedElastic())，讓它在彈性 Thread Pool 中執行，不佔用 I/O Thread。
</details>

**Q3：Flux 的背壓和 Kafka 的消費者速率控制有什麼相似之處？**
<details>
<summary>答案</summary>
兩者解決的是同一個問題：生產者速度 > 消費者速度，防止消費者被壓垮。Kafka 的解法是持久化佇列（消息寫入磁碟，消費者按自己的速度消費）。Flux 背壓則是在記憶體中解決：BUFFER 策略保留未消費的元素（可能 OOM），LATEST 只保留最新（丟棄慢的），ERROR 直接失敗。核心概念都是讓消費者有能力「告訴」生產者自己的處理能力。
</details>
