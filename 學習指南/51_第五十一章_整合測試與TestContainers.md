# 第五十一章：整合測試與 TestContainers

## 前言：單元測試不夠用

單元測試（ch.16 TDD）用 Mock 替換所有外部依賴，速度快但有盲點：

```
單元測試通過 ≠ 程式跑起來正確

常見的「單元測試通過但整合失敗」場景：
  - SQL 語法在 H2 記憶體資料庫可以跑，但在 MySQL 有方言差異
  - Redis 命令 Mock 了，但實際 Lua Script 邏輯有錯
  - Kafka 消費者 Mock 了，但序列化格式不匹配
  - API 路徑和 Security Config 的 permit 路徑打字錯誤
```

**整合測試**：啟動真實的 Spring 容器 + 真實的外部服務，測試元件之間的協作。

**TestContainers**：在 JUnit 測試中用 Docker 啟動真實的 MySQL、Redis、Kafka，測試完自動清理。

---

## 一、環境準備

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>

<!-- TestContainers 核心 -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <scope>test</scope>
</dependency>

<!-- 各資料庫的 TestContainers 模組 -->
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>kafka</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>com.redis</groupId>
    <artifactId>testcontainers-redis</artifactId>
    <version>2.2.2</version>
    <scope>test</scope>
</dependency>
```

---

## 二、基礎：Spring Boot 整合測試

```java
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.web.client.TestRestTemplate;

// @SpringBootTest：啟動完整的 Spring Application Context（所有 Bean 都載入）
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")  // 使用 application-test.yml 設定
class OrderIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;  // 可以發真實 HTTP 請求

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void shouldCreateOrder() {
        // 準備測試資料
        CreateOrderRequest request = new CreateOrderRequest();
        request.setProductId("PRD-001");
        request.setQuantity(2);

        // 發 HTTP 請求（真實的 HTTP，不是 Mock）
        ResponseEntity<Order> response = restTemplate.postForEntity(
            "/api/orders",
            request,
            Order.class
        );

        // 驗證 HTTP 回應
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(OrderStatus.PENDING);

        // 驗證資料庫（真實查詢）
        Optional<Order> saved = orderRepository.findById(response.getBody().getId());
        assertThat(saved).isPresent();
    }
}
```

---

## 三、TestContainers + MySQL

```java
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers  // 開啟 TestContainers 支援
@ActiveProfiles("test")
class OrderRepositoryTest {

    // @Container：JUnit 5 管理容器的生命週期（測試類開始前啟動，結束後停止）
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("testdb")
        .withUsername("test")
        .withPassword("test")
        .withInitScript("sql/schema.sql");  // 建立 Schema

    // 動態設定：讓 Spring 使用 TestContainers 的 MySQL
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    private OrderRepository orderRepository;

    @Test
    void shouldSaveAndFindOrder() {
        // 使用真實 MySQL，不是 H2 記憶體資料庫
        Order order = new Order();
        order.setOrderId("ORD-001");
        order.setCustomerId("USR-001");
        order.setStatus(OrderStatus.PENDING);
        order.setTotalAmount(999.0);
        order.setCreatedAt(LocalDateTime.now());

        Order saved = orderRepository.save(order);

        // 查詢
        Optional<Order> found = orderRepository.findByOrderId("ORD-001");
        assertThat(found).isPresent();
        assertThat(found.get().getTotalAmount()).isEqualTo(999.0);
    }

    @Test
    void shouldFindOrdersByCustomer() {
        // 建立多筆測試資料
        orderRepository.saveAll(List.of(
            createOrder("ORD-001", "USR-001", 100.0),
            createOrder("ORD-002", "USR-001", 200.0),
            createOrder("ORD-003", "USR-002", 300.0)
        ));

        List<Order> userOrders = orderRepository.findByCustomerId("USR-001");
        assertThat(userOrders).hasSize(2);
    }
}
```

---

## 四、TestContainers + Redis

```java
import com.redis.testcontainers.RedisContainer;

@SpringBootTest
@Testcontainers
class RedisCacheTest {

    @Container
    static RedisContainer redis = new RedisContainer(
        DockerImageName.parse("redis:7.2")
    );

    @DynamicPropertySource
    static void configureRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ProductCacheService productCacheService;

    @Test
    void shouldCacheProduct() {
        Product product = new Product("PRD-001", "Java 書", 599.0);

        // 第一次查詢：快取 miss，從 DB 查詢並存入 Redis
        Product result1 = productCacheService.getProduct("PRD-001");
        assertThat(result1.getName()).isEqualTo("Java 書");

        // 驗證 Redis 中確實有資料
        assertThat(redisTemplate.hasKey("product:PRD-001")).isTrue();

        // 第二次查詢：快取 hit
        Product result2 = productCacheService.getProduct("PRD-001");
        assertThat(result2).isEqualTo(result1);
    }

    @Test
    void shouldDeductStockAtomically() {
        // 初始化庫存
        redisTemplate.opsForValue().set("stock:PRD-001", "100");

        // 多 Thread 同時扣減，測試原子性
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(100);

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    stockService.deductStock("PRD-001", 1);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);

        // 驗證庫存恰好是 0（不是負數 = 沒有超賣）
        String remaining = (String) redisTemplate.opsForValue().get("stock:PRD-001");
        assertThat(Integer.parseInt(remaining)).isEqualTo(0);
    }
}
```

---

## 五、TestContainers + Kafka

```java
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@Testcontainers
class OrderEventTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.4.0")
    );

    @DynamicPropertySource
    static void configureKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    // 測試 Producer：下單後是否有發送 Kafka 消息
    @Test
    void shouldPublishOrderCreatedEvent() throws Exception {
        // 用 Consumer 監聽 topic
        Consumer<String, String> consumer = createTestConsumer("order-events");
        consumer.subscribe(List.of("order-events"));

        // 觸發下單
        orderService.createOrder(new CreateOrderRequest("USR-001", "PRD-001", 2));

        // 等待 Kafka 消息到達（最多等 10 秒）
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));

        assertThat(records.count()).isEqualTo(1);
        ConsumerRecord<String, String> record = records.iterator().next();

        OrderCreatedEvent event = objectMapper.readValue(record.value(), OrderCreatedEvent.class);
        assertThat(event.getCustomerId()).isEqualTo("USR-001");
        assertThat(event.getProductId()).isEqualTo("PRD-001");
    }

    // 測試 Consumer：收到 Kafka 消息後是否正確處理
    @Test
    void shouldProcessPaymentEvent() throws Exception {
        // 先建一個訂單
        Order order = orderRepository.save(createPendingOrder());

        // 發送 PaymentSucceeded 事件
        PaymentSucceededEvent event = new PaymentSucceededEvent(order.getId(), "TX-001");
        kafkaTemplate.send("payment-events", objectMapper.writeValueAsString(event));

        // 等待 Consumer 處理（最多 10 秒）
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Order updated = orderRepository.findById(order.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(OrderStatus.PAID);
        });
    }
}
```

---

## 六、MockMvc：測試 Controller 層 <!-- 💡 進階 -->

MockMvc 不啟動真實 HTTP Server，但會啟動 Spring MVC 的 DispatcherServlet，可以測試：Controller 邏輯、路由、Security 設定、請求驗證、回應格式。

```java
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;

// @WebMvcTest：只載入 MVC 相關 Bean（快！不需要完整 Spring Context）
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean  // 用 Mock 替換真實的 Service（Controller 測試不需要真實 DB）
    private OrderService orderService;

    @Test
    @WithMockUser(username = "user1", roles = "USER")  // 模擬登入用戶
    void shouldReturnOrderById() throws Exception {
        Order mockOrder = new Order();
        mockOrder.setOrderId("ORD-001");
        mockOrder.setStatus(OrderStatus.PAID);

        when(orderService.findByOrderId("ORD-001")).thenReturn(mockOrder);

        mockMvc.perform(
            get("/api/orders/ORD-001")
                .accept(MediaType.APPLICATION_JSON)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orderId").value("ORD-001"))
        .andExpect(jsonPath("$.status").value("PAID"))
        .andDo(print());  // 印出請求/回應（除錯用）
    }

    @Test
    void shouldReturn401WhenNotAuthenticated() throws Exception {
        mockMvc.perform(get("/api/orders/ORD-001"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "USER")
    void shouldReturn403WhenAccessAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/admin/orders"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "USER")
    void shouldReturn400WhenInvalidRequest() throws Exception {
        String invalidJson = """
            {
                "productId": "",
                "quantity": -1
            }
            """;

        mockMvc.perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidJson)
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors").isArray());
    }
}
```

---

## 七、WireMock：Mock 外部 HTTP 服務 <!-- 💡 進階 -->

當你的服務需要呼叫外部 API（如支付閘道、物流 API），整合測試不應該真的呼叫這些外部服務。WireMock 模擬一個真實的 HTTP Server：

```xml
<dependency>
    <groupId>org.wiremock</groupId>
    <artifactId>wiremock-standalone</artifactId>
    <version>3.3.1</version>
    <scope>test</scope>
</dependency>
```

```java
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@SpringBootTest
@ExtendWith(WireMockExtension.class)
class PaymentServiceTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().dynamicPort())   // 隨機 Port，避免衝突
        .build();

    @DynamicPropertySource
    static void configurePaymentGatewayUrl(DynamicPropertyRegistry registry) {
        registry.add("payment.gateway.url", wireMock::baseUrl);
    }

    @Autowired
    private PaymentService paymentService;

    @Test
    void shouldProcessPaymentSuccessfully() {
        // 設定 WireMock：當收到 POST /payments，回傳以下 JSON
        wireMock.stubFor(
            post(urlEqualTo("/payments"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.amount", equalTo("999.0")))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("""
                        {
                            "transactionId": "TX-12345",
                            "status": "SUCCESS"
                        }
                        """))
        );

        // 執行（PaymentService 內部呼叫的 HTTP 請求會被 WireMock 攔截）
        PaymentResult result = paymentService.charge("ORD-001", 999.0);

        assertThat(result.getTransactionId()).isEqualTo("TX-12345");
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void shouldHandlePaymentGatewayTimeout() {
        // 模擬超時
        wireMock.stubFor(
            post(urlEqualTo("/payments"))
                .willReturn(aResponse()
                    .withFixedDelay(5000)  // 5 秒延遲
                    .withStatus(200))
        );

        // 驗證超時後的行為（應該觸發熔斷器或回傳錯誤）
        assertThatThrownBy(() -> paymentService.charge("ORD-001", 999.0))
            .isInstanceOf(PaymentTimeoutException.class);
    }
}
```

---

## 八、測試分層最佳實踐

```
測試金字塔：

          /\
         /  \   E2E 測試（端到端）
        /    \  少量，跑整個用戶流程
       /──────\
      /        \  整合測試（本章）
     /          \  中等數量，測試模組協作
    /────────────\
   /              \  單元測試（ch.16 TDD）
  /                \  大量，測試單個方法/類
 /──────────────────\
```

**速度 vs 可信度的取捨：**

| 測試類型 | 速度 | 可信度 | 典型工具 |
|---------|------|--------|---------|
| 單元測試 | 毫秒 | 低（Mock 太多） | JUnit 5 + Mockito |
| Controller 測試 | 秒 | 中（真實 Spring MVC） | MockMvc |
| Repository 測試 | 秒-分鐘 | 高（真實 DB） | TestContainers + MySQL |
| 服務整合測試 | 分鐘 | 很高（真實全棧） | TestContainers + 全套 |
| E2E 測試 | 分鐘+ | 最高 | Selenium / Playwright |

**CI/CD 中的策略：**
```
每次 PR → 單元測試（快速回饋，< 1 分鐘）
合併 main → 整合測試（TestContainers，< 10 分鐘）
每日深夜 → E2E 測試（完整流程，< 30 分鐘）
```

---

## 本章練習題

**Q1：@WebMvcTest 和 @SpringBootTest 的差別？各自適合測試什麼？**
<details>
<summary>答案</summary>
@WebMvcTest 只啟動 Spring MVC 相關的 Bean（Controller、ControllerAdvice、Filter、WebMvcConfigurer），不載入 Service、Repository、資料庫設定等。優點是啟動快（秒級），適合測試 Controller 邏輯、路由、Security 設定、請求驗證、回應格式。Service 層用 @MockBean 替換。

@SpringBootTest 啟動完整的 ApplicationContext，所有 Bean 都載入。適合整合測試，可以驗證元件之間的真實協作，通常配合 TestContainers 使用真實資料庫。缺點是啟動慢（10-30 秒）。
</details>

**Q2：TestContainers 的 @Container static 和非 static 有什麼差別？**
<details>
<summary>答案</summary>
static（靜態欄位）：容器在整個測試類的所有測試方法期間共享（只啟動一次，測試類結束後停止）。速度快，但測試之間可能有狀態污染（前一個測試的資料影響後一個）。需要在 @BeforeEach 清理資料庫。

非 static（實例欄位）：每個測試方法都啟動和停止一個新容器。測試完全隔離，但每次都要啟動 Docker 容器，速度很慢。通常使用 static + @BeforeEach 清理是更好的選擇。
</details>

**Q3：WireMock 和 @MockBean 都能 Mock 外部依賴，什麼時候選哪個？**
<details>
<summary>答案</summary>
@MockBean 是在 Spring 容器層面替換 Bean，完全繞過 HTTP（你在 Mock 的是 Java 物件方法）。適合測試 Service 層邏輯，簡單快速。

WireMock 是模擬真實 HTTP Server，你的程式碼會發出真實的 HTTP 請求打到 WireMock（只是 WireMock 不是真實的外部服務）。適合測試 HTTP 客戶端本身（序列化格式、超時處理、重試邏輯、錯誤碼處理），能測試到更真實的網路行為。如果你想驗證「呼叫外部 API 超時後，熔斷器是否正確觸發」，WireMock 才能真正模擬這個場景。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 47 章 | Phase 7：資料結構深化與效能調優
> 下一章（第 48 章）：[第八章：交易系統與撮合引擎基礎](08_第八章_交易系統與撮合引擎基礎.md)
