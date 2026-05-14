# 第二十一章：Spring Boot 基礎
> Java 後端的工業標準：用注解驅動，讓框架做苦工，你專注業務邏輯

---

## 前言：為什麼 Spring Boot 是必學的

在你開始找 Java 後端工作之前，必須先回答這個問題：  
「你會 Spring Boot 嗎？」

Spring Boot 是在 Spring Framework 基礎上做的「開箱即用」版本：
- Spring Framework：功能強大但設定複雜（需要大量 XML）
- Spring Boot：自動設定、內嵌伺服器、一個 main() 就能啟動

---

## 一、IoC 與 DI：Spring 最核心的概念

### 沒有 Spring 時的問題

```java
// 手動管理依賴：每個地方都要 new，改一個類別可能影響十幾個地方
public class TradingService {
    private OrderBook orderBook;
    private TradeReporter reporter;
    private RiskEngine risk;

    public TradingService() {
        // 手動建立所有依賴，高度耦合
        this.orderBook = new OrderBook("TSLA");
        this.reporter  = new FixReporter(new FixSession("localhost", 9999));
        this.risk      = new RiskEngine(new LimitConfig("config.yaml"));
    }
}
// 想換成測試用的 MockReporter？要改 TradingService 的程式碼
```

### IoC（Inversion of Control，控制反轉）

```
傳統：你的程式碼控制物件的建立
IoC：把控制權交給框架（Spring Container）

之前：你 new 物件           → TradingService new TradeReporter
之後：Spring 幫你注入物件   → Spring 建立 TradeReporter，注入到 TradingService
```

### DI（Dependency Injection，依賴注入）

```java
import org.springframework.stereotype.*;
import org.springframework.beans.factory.annotation.*;

// @Service 告訴 Spring：請幫我管理這個類別的實例（Bean）
@Service
public class TradingService {

    private final OrderBook orderBook;
    private final TradeReporter reporter;

    // 建構子注入（最推薦的方式）
    // Spring 看到這個建構子，自動把對應的 Bean 傳進來
    @Autowired
    public TradingService(OrderBook orderBook, TradeReporter reporter) {
        this.orderBook = orderBook;
        this.reporter  = reporter;
    }

    public List<Trade> submitOrder(Order order) {
        return orderBook.addOrder(order);
    }
}

@Service
public class FixReporter implements TradeReporter {
    @Override
    public void report(Trade trade) {
        System.out.println("FIX 回報：" + trade);
    }
}
```

---

## 二、建立第一個 Spring Boot 專案

### pom.xml 結構

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
    </parent>

    <groupId>tw.brad</groupId>
    <artifactId>exchange</artifactId>
    <version>1.0.0</version>

    <dependencies>
        <!-- Web（包含內嵌 Tomcat + Spring MVC）-->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- 資料庫（JPA + HikariCP）-->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- MySQL 驅動 -->
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
        </dependency>

        <!-- 測試 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 啟動類別

```java
package tw.brad.exchange;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication  // = @Configuration + @EnableAutoConfiguration + @ComponentScan
public class ExchangeApplication {
    public static void main(String[] args) {
        SpringApplication.run(ExchangeApplication.class, args);
        // 啟動內嵌 Tomcat，預設 port 8080
    }
}
```

---

## 三、核心注解速覽

```java
// 元件（Component）— 把類別交給 Spring 管理
@Component      // 通用元件
@Service        // 業務邏輯層（語意上表示服務）
@Repository     // 資料存取層（額外：自動轉換資料庫例外）
@Controller     // MVC 控制器（回傳 HTML View）
@RestController // REST API 控制器（= @Controller + @ResponseBody，回傳 JSON）

// 設定
@Configuration  // 設定類別（裡面定義 @Bean）
@Bean           // 手動定義一個 Bean（不適合用 @Service 的情況）

// 注入
@Autowired      // 自動注入依賴（建構子/Setter/欄位）
@Value("${server.port}") // 從 application.properties 注入設定值
@Qualifier("fixReporter") // 同型別有多個 Bean 時，指定要用哪一個

// Web
@RequestMapping("/api/orders")   // 映射 URL 路徑
@GetMapping    @PostMapping      // HTTP GET / POST
@PutMapping    @DeleteMapping    // HTTP PUT / DELETE
@PathVariable  @RequestParam     // 取得 URL 路徑變數 / 查詢參數
@RequestBody   @ResponseBody     // 讀取/輸出 JSON 請求/回應體
```

---

## 四、建立 REST API：訂單管理端點

```java
package tw.brad.exchange.controller;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

// 所有 @RestController 方法預設回傳 JSON
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final TradingService tradingService;
    private final OrderRepository orderRepository;

    // 建構子注入（Spring 自動填充）
    public OrderController(TradingService tradingService,
                            OrderRepository orderRepository) {
        this.tradingService  = tradingService;
        this.orderRepository = orderRepository;
    }

    // POST /api/orders  — 新增訂單
    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(
            @RequestBody OrderRequest request) {
        Order order = OrderFactory.create(
            UUID.randomUUID().toString(),
            request.getSymbol(),
            request.getSide(),
            request.getType(),
            request.getPrice(),
            request.getQuantity()
        );
        List<Trade> trades = tradingService.submitOrder(order);
        return ResponseEntity
            .status(HttpStatus.CREATED)   // 201 Created
            .body(new OrderResponse(order, trades));
    }

    // GET /api/orders/{orderId}  — 查詢單一訂單
    @GetMapping("/{orderId}")
    public ResponseEntity<Order> getOrder(@PathVariable String orderId) {
        return orderRepository.findById(orderId)
            .map(ResponseEntity::ok)              // 200 OK + 訂單資料
            .orElse(ResponseEntity.notFound().build());  // 404 Not Found
    }

    // GET /api/orders?symbol=TSLA&status=ACTIVE  — 查詢訂單列表
    @GetMapping
    public List<Order> listOrders(
            @RequestParam String symbol,
            @RequestParam(required = false, defaultValue = "ACTIVE") String status) {
        return orderRepository.findBySymbolAndStatus(symbol, status);
    }

    // DELETE /api/orders/{orderId}  — 取消訂單
    @DeleteMapping("/{orderId}")
    public ResponseEntity<Void> cancelOrder(@PathVariable String orderId) {
        boolean cancelled = tradingService.cancelOrder(orderId);
        return cancelled
            ? ResponseEntity.noContent().build()   // 204 No Content
            : ResponseEntity.notFound().build();    // 404 Not Found
    }
}
```

### Request / Response DTO

```java
// 接收請求的資料結構（不要直接用 Order 領域物件）
public class OrderRequest {
    private String symbol;
    private String side;    // "BUY" / "SELL"
    private String type;    // "LIMIT" / "MARKET" / "IOC" / "FOK"
    private double price;
    private int    quantity;
    // Jackson 需要 Getter/Setter 或 Lombok 的 @Data
    // Getters and Setters...
}

public class OrderResponse {
    private String       orderId;
    private OrderStatus  status;
    private List<Trade>  trades;   // 立即成交的成交記錄（可能是空列表）

    public OrderResponse(Order order, List<Trade> trades) {
        this.orderId = order.getOrderId();
        this.status  = order.getStatus();
        this.trades  = trades;
    }
    // Getters...
}
```

---

## 五、application.properties 設定

```properties
# src/main/resources/application.properties

# 伺服器設定
server.port=8080

# 資料庫
spring.datasource.url=jdbc:mysql://localhost:3306/exchange?useSSL=false&serverTimezone=UTC
spring.datasource.username=root
spring.datasource.password=password
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver

# HikariCP 連線池
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=5

# JPA
spring.jpa.hibernate.ddl-auto=update   # 自動建立/更新資料表（生產環境改 validate）
spring.jpa.show-sql=true               # 顯示執行的 SQL（除錯用）

# 日誌
logging.level.tw.brad=DEBUG
logging.level.org.springframework.web=INFO
logging.file.name=logs/exchange.log
```

---

## 六、例外處理：統一回應格式

```java
// 全域例外處理器（所有 Controller 共用）
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 處理業務例外
    @ExceptionHandler(OrderNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(OrderNotFoundException e) {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)   // 404
            .body(new ErrorResponse("ORDER_NOT_FOUND", e.getMessage()));
    }

    // 處理驗證失敗
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)  // 400
            .body(new ErrorResponse("INVALID_REQUEST", e.getMessage()));
    }

    // 處理所有未預期的例外（兜底）
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("未預期的例外", e);
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)  // 500
            .body(new ErrorResponse("INTERNAL_ERROR", "系統發生錯誤，請稍後再試"));
    }
}

public record ErrorResponse(String code, String message) {}
```

---

## 七、測試 Spring Boot Controller

```java
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OrderController.class)  // 只載入 Web 層，不啟動整個 Spring Context
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;  // 模擬 HTTP 請求，不需要真的啟動伺服器

    @MockBean
    private TradingService tradingService;  // Mock 掉業務層

    @MockBean
    private OrderRepository orderRepository;

    @Test
    void createOrderShouldReturn201() throws Exception {
        String requestBody = """
            {
                "symbol": "TSLA",
                "side": "BUY",
                "type": "LIMIT",
                "price": 350.0,
                "quantity": 100
            }
            """;

        when(tradingService.submitOrder(any())).thenReturn(List.of());

        mockMvc.perform(post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody))
            .andExpect(status().isCreated())           // 檢查 HTTP 狀態碼 201
            .andExpect(jsonPath("$.status").value("ACTIVE"));  // 檢查 JSON 欄位
    }

    @Test
    void getOrderShouldReturn404WhenNotFound() throws Exception {
        when(orderRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/orders/UNKNOWN"))
            .andExpect(status().isNotFound());  // 404
    }
}
```

---

## 八、Spring Cloud 生態系統概覽 <!-- 🔴 資深 -->

> Spring Boot 讓你快速建一個服務；Spring Cloud 讓你把幾十個服務接在一起，解決微服務架構中的通信、配置、容錯等問題。本節帶你鳥瞰整個生態，後續章節（第四十章起）有每個元件的完整實作。

### 為什麼需要 Spring Cloud？

單體應用只有一個部署單元，服務內部呼叫是方法調用（奈秒級）。微服務架構把系統拆成十幾個服務，帶來了一堆新問題：

| 問題 | 單體應用 | 微服務需要 |
|------|---------|-----------|
| 服務地址 | 本地呼叫 | 服務發現（哪個 IP:Port？） |
| 配置管理 | 一份 config 檔 | 統一配置中心（百個服務） |
| API 入口 | 直接連後端 | API Gateway（路由/認證） |
| 服務通信 | 方法調用 | HTTP Feign / gRPC |
| 容錯處理 | try-catch | 熔斷器（避免雪崩） |
| 鏈路追蹤 | 無需要 | Zipkin / Jaeger |

### Spring Cloud 主要元件地圖

```
                  ┌──────────────────────────────────────────┐
                  │           Spring Cloud 生態系              │
                  └──────────────────────────────────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         ▼                    ▼                    ▼
  ┌─────────────┐    ┌─────────────────┐   ┌─────────────┐
  │  API Gateway│    │  服務發現 / 註冊  │   │  配置中心   │
  │Spring Cloud │    │  Nacos / Eureka  │   │Config Server│
  │  Gateway    │    │                 │   │  / Nacos    │
  └─────────────┘    └─────────────────┘   └─────────────┘
         │                    │
  ┌──────▼──────┐    ┌────────▼────────┐
  │服務間通信   │    │  容錯 / 熔斷     │
  │OpenFeign    │    │  Resilience4j   │
  │（同步 HTTP）│    │  Circuit Breaker │
  └─────────────┘    └─────────────────┘
```

### 1. API Gateway（Spring Cloud Gateway）

所有外部請求的統一入口，負責：路由分發、身份驗證、限流、日誌。

```yaml
# application.yml
spring:
  cloud:
    gateway:
      routes:
        - id: order-service
          uri: lb://order-service    # lb:// 表示透過服務發現取得 IP
          predicates:
            - Path=/api/orders/**   # 符合此路徑才轉發
          filters:
            - StripPrefix=1         # 移除路徑前綴 /api
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 100
                redis-rate-limiter.burstCapacity: 200
```

### 2. 服務發現（Nacos）

服務啟動時向 Nacos 註冊自己的 IP 和 Port，其他服務透過「服務名稱」呼叫，不需要寫死 IP。

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
</dependency>
```

```yaml
spring:
  application:
    name: order-service         # 服務名稱（發現的 key）
  cloud:
    nacos:
      discovery:
        server-addr: localhost:8848
```

```java
// 啟動類加 @EnableDiscoveryClient（或自動觸發）
@SpringBootApplication
@EnableDiscoveryClient
public class OrderServiceApplication { ... }
```

### 3. 服務間通信（OpenFeign）

用聲明式介面呼叫其他服務，像本地方法一樣，Feign 底層幫你做 HTTP 請求。

```java
// 聲明介面：呼叫 inventory-service 的 /inventory/{id} 端點
@FeignClient(name = "inventory-service")   // name = Nacos 上的服務名稱
public interface InventoryClient {
    @GetMapping("/inventory/{productId}")
    InventoryDTO getInventory(@PathVariable Long productId);

    @PostMapping("/inventory/deduct")
    void deductStock(@RequestBody DeductRequest request);
}

// 使用：和本地 Service 完全一樣
@Service
public class OrderService {
    private final InventoryClient inventoryClient;

    public Order createOrder(OrderRequest req) {
        InventoryDTO inv = inventoryClient.getInventory(req.getProductId());
        // ...
    }
}
```

### 4. 配置中心（Spring Cloud Config / Nacos Config）

集中管理所有服務的配置，修改後可以動態推送，不需要重啟服務。

```yaml
# bootstrap.yml（Config Server 設定要在 application.yml 之前讀）
spring:
  application:
    name: order-service
  cloud:
    nacos:
      config:
        server-addr: localhost:8848
        file-extension: yaml
        namespace: dev              # 環境隔離

# 從 Nacos 讀取 order-service.yaml 的內容，覆蓋本地配置
```

```java
@RestController
@RefreshScope   // 加這個注解，Nacos 推送配置更新時自動重新注入
public class OrderController {
    @Value("${order.max-quantity:100}")
    private int maxQuantity;
}
```

### 5. 元件選型總覽

| 功能 | Spring Cloud Netflix（舊） | Spring Cloud Alibaba（現在） |
|------|--------------------------|----------------------------|
| 服務發現 | Eureka | **Nacos** |
| 配置中心 | Config Server | **Nacos Config** |
| 熔斷器 | Hystrix（停維） | **Resilience4j** |
| 服務通信 | Feign | **OpenFeign**（同款升級版） |
| API Gateway | Zuul（停維） | **Spring Cloud Gateway** |
| 鏈路追蹤 | Sleuth（整合） | **Micrometer Tracing** |

> 💡 Alibaba Cloud 版本（Nacos + Sentinel + Seata）在中國企業中極為普遍，如果你之後在電商或金融公司工作，幾乎一定會遇到。

### 微服務啟動順序

```
1. Nacos（服務發現 + 配置中心）先啟動
2. Config Server（如果用 Spring Cloud Config）
3. 各業務服務（order/inventory/payment...）
4. API Gateway（最後，等其他服務都在線）
```

---

## 本章練習題

**Q1：@Service 和 @Component 在功能上有什麼差異？**
<details>
<summary>答案</summary>
功能上完全相同，都是告訴 Spring 掃描並管理這個類別。差異在語意：@Component 是通用元件，@Service 明確表示這是業務邏輯層，@Repository 是資料存取層（額外會自動轉換資料庫例外成 Spring 的 DataAccessException）。良好的慣例是按職責選擇對應的注解，讓程式碼的層次結構更清晰。
</details>

**Q2：什麼是 Spring Bean 的生命週期？預設的 Scope 是什麼？**
<details>
<summary>答案</summary>
預設 Scope 是 Singleton：整個 Spring Container 中只有一個實例，所有需要這個 Bean 的地方都共用同一個物件。這就是為什麼 Service、Repository 的欄位不能存放請求相關的狀態（執行緒不安全）。其他 Scope：Prototype（每次注入都建立新實例）、Request（每個 HTTP 請求一個實例，Web 環境）、Session（每個 HTTP Session 一個實例）。生命週期：Spring 建立實例 → 注入依賴 → @PostConstruct → 正常使用 → @PreDestroy → 銷毀。
</details>

**Q3：建構子注入和 @Autowired 欄位注入有什麼差異？為什麼推薦建構子注入？**
<details>
<summary>答案</summary>
欄位注入：@Autowired private TradeReporter reporter; — 簡潔但有問題：欄位是 private，單元測試難以注入 Mock 物件；依賴不明確，你無法一眼看出這個類別需要什麼；不能宣告為 final（依賴可以被修改）。建構子注入：在建構子參數中宣告依賴，Spring 自動注入。優點：可以宣告 final（不可變）；測試時直接 new MyService(mockReporter) 即可，不需要 Spring Context；依賴關係清晰可見；如果依賴缺失，啟動時立即報錯（而非執行到某個方法才 NullPointerException）。Spring 官方和業界最佳實踐都推薦建構子注入。
</details>
