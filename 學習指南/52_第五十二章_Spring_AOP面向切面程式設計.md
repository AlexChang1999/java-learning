# 第五十二章：Spring AOP 面向切面程式設計

## 前言：橫切關注點的問題

想像你有 100 個 Service 方法，每個都需要：記錄執行時間、開啟/關閉 Transaction、檢查權限。

**不用 AOP 的做法：** 在每個方法裡複製貼上這些邏輯 → 100 個方法改 100 次，日誌格式稍有變化就是噩夢。

**AOP 的做法：** 寫一次，宣告「所有 Service 方法執行前後都要做 X」。

這些與業務邏輯無關但散落各處的需求，叫做**橫切關注點（Cross-Cutting Concerns）**。

---

## 一、核心概念

```
Aspect（切面）     = 橫切關注點的模組化（如「效能監控切面」）
Join Point（連接點）= 可以插入邏輯的點（方法呼叫、例外拋出等）
Pointcut（切入點） = 「哪些」Join Point 要被攔截（用表達式描述）
Advice（通知）     = 在 Join Point 要執行「什麼」邏輯（前置/後置/環繞）
Weaving（織入）    = 把 Advice 應用到目標物件的過程（Spring 在運行時做）
```

```
你寫的 OrderService
    ↑ Weaving（Spring 啟動時）
OrderService 的 CGLIB Proxy
    ↓ 呼叫 createOrder()
    ├── @Before Advice：記錄進入方法
    ├── 執行真實 createOrder()
    └── @After / @AfterReturning Advice：記錄執行時間
```

---

## 二、快速上手：@Aspect

```xml
<!-- pom.xml：Spring Boot 已包含，不需要額外加 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

```java
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.stereotype.Component;

@Aspect      // 宣告這是一個切面
@Component   // 讓 Spring 管理這個 Bean
public class PerformanceAspect {

    // Pointcut 表達式：攔截 service 包下所有類別的所有方法
    @Pointcut("execution(* com.example.service.*.*(..))")
    public void serviceLayer() {}  // 方法名稱只是一個識別符

    // @Before：方法執行前
    @Before("serviceLayer()")
    public void beforeAdvice(JoinPoint joinPoint) {
        System.out.println("呼叫: " + joinPoint.getSignature().getName());
    }

    // @AfterReturning：方法正常回傳後（可以取得回傳值）
    @AfterReturning(pointcut = "serviceLayer()", returning = "result")
    public void afterReturning(JoinPoint joinPoint, Object result) {
        System.out.println("回傳: " + result);
    }

    // @AfterThrowing：方法拋出例外後
    @AfterThrowing(pointcut = "serviceLayer()", throwing = "ex")
    public void afterThrowing(JoinPoint joinPoint, Exception ex) {
        System.out.println("例外: " + ex.getMessage());
    }

    // @After：無論正常或例外都執行（相當於 finally）
    @After("serviceLayer()")
    public void afterAdvice(JoinPoint joinPoint) {
        System.out.println("方法結束: " + joinPoint.getSignature().getName());
    }

    // @Around：最強大，完全控制方法執行（包含前後）
    @Around("serviceLayer()")
    public Object aroundAdvice(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        String methodName = pjp.getSignature().getName();

        try {
            Object result = pjp.proceed();  // 執行真實方法（必須呼叫！）
            long elapsed = System.currentTimeMillis() - start;
            System.out.printf("[PERF] %s 耗時 %dms%n", methodName, elapsed);
            return result;
        } catch (Exception e) {
            System.out.printf("[ERROR] %s 拋出 %s%n", methodName, e.getMessage());
            throw e;  // 重新拋出，不要吞掉例外
        }
    }
}
```

---

## 三、Pointcut 表達式詳解

```java
// execution 語法：execution(修飾符 回傳型別 包名.類名.方法名(參數))

// 所有 public 方法
"execution(public * *(..))"

// service 包下所有方法（含子套件用 ..）
"execution(* com.example.service..*.*(..))"

// 所有 OrderService 的方法
"execution(* com.example.service.OrderService.*(..))"

// 方法名稱以 find 開頭的所有方法
"execution(* find*(..))"

// 只有一個 Long 參數的方法
"execution(* *(Long))"

// @annotation：帶有特定注解的方法（最實用！）
"@annotation(com.example.annotation.LogExecution)"

// @within：帶有特定注解的類別裡的所有方法
"@within(org.springframework.stereotype.Service)"

// bean：特定 Bean 名稱
"bean(orderService)"
```

---

## 四、自訂注解 + AOP（最佳實踐）

與其讓切面悄悄攔截所有 Service 方法，更好的方式是用自訂注解「明確標記」要攔截的方法：

```java
// 1. 自訂注解
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface LogExecution {
    String value() default "";  // 可以加說明
}

// 2. 切面：只攔截有 @LogExecution 的方法
@Aspect
@Component
public class LogAspect {

    @Around("@annotation(logExecution)")  // 注入注解物件
    public Object log(ProceedingJoinPoint pjp, LogExecution logExecution) throws Throwable {
        String methodName = pjp.getSignature().toShortString();
        String desc = logExecution.value().isEmpty() ? methodName : logExecution.value();

        log.info("[START] {}", desc);
        long start = System.currentTimeMillis();

        try {
            Object result = pjp.proceed();
            log.info("[OK] {} 耗時 {}ms", desc, System.currentTimeMillis() - start);
            return result;
        } catch (Exception e) {
            log.error("[FAIL] {} 例外: {}", desc, e.getMessage());
            throw e;
        }
    }
}

// 3. 在 Service 上使用
@Service
public class OrderService {

    @LogExecution("建立訂單")
    public Order createOrder(CreateOrderRequest req) {
        return orderRepository.save(Order.from(req));
    }

    @LogExecution("查詢訂單列表")
    public List<Order> findOrders(String customerId) {
        return orderRepository.findByCustomerId(customerId);
    }
}
```

---

## 五、實戰：三個常見的 Aspect

### 1. 統一請求日誌（記錄所有 Controller 的入參和出參）

```java
@Aspect
@Component
@Slf4j
public class RequestLogAspect {

    @Around("within(@org.springframework.web.bind.annotation.RestController *)")
    public Object logRequest(ProceedingJoinPoint pjp) throws Throwable {
        HttpServletRequest request = ((ServletRequestAttributes)
            RequestContextHolder.getRequestAttributes()).getRequest();

        String method = request.getMethod();
        String uri = request.getRequestURI();
        Object[] args = pjp.getArgs();

        log.info("→ {} {} args={}", method, uri, Arrays.toString(args));

        try {
            Object result = pjp.proceed();
            log.info("← {} {} response={}", method, uri, result);
            return result;
        } catch (Exception e) {
            log.error("← {} {} error={}", method, uri, e.getMessage());
            throw e;
        }
    }
}
```

### 2. 防重複提交（5 秒內同一個用戶同一個方法只能提交一次）

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreventDuplicate {
    int seconds() default 5;
}

@Aspect
@Component
public class DuplicatePreventAspect {

    private final RedisTemplate<String, String> redisTemplate;

    @Around("@annotation(preventDuplicate)")
    public Object prevent(ProceedingJoinPoint pjp,
                          PreventDuplicate preventDuplicate) throws Throwable {
        // 取出當前登入用戶
        String userId = SecurityContextHolder.getContext()
            .getAuthentication().getName();
        String methodKey = pjp.getSignature().toShortString();
        String lockKey = "prevent_dup:" + userId + ":" + methodKey;

        // Redis SETNX（Set if Not eXist）實現防重鎖
        Boolean acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, "1", Duration.ofSeconds(preventDuplicate.seconds()));

        if (!Boolean.TRUE.equals(acquired)) {
            throw new DuplicateSubmitException("請勿重複提交，請 " + preventDuplicate.seconds() + " 秒後再試");
        }

        return pjp.proceed();
    }
}

// 使用
@PostMapping("/orders")
@PreventDuplicate(seconds = 10)
public ResponseEntity<Order> createOrder(@RequestBody CreateOrderRequest req) {
    return ResponseEntity.ok(orderService.createOrder(req));
}
```

### 3. 操作審計日誌（記錄誰在什麼時候做了什麼）

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditLog {
    String action();   // 操作描述，如 "修改訂單狀態"
}

@Aspect
@Component
public class AuditLogAspect {

    private final AuditLogRepository auditLogRepository;

    @AfterReturning("@annotation(auditLog)")
    public void audit(JoinPoint jp, AuditLog auditLog) {
        String userId = SecurityContextHolder.getContext()
            .getAuthentication().getName();

        AuditLogEntity log = new AuditLogEntity();
        log.setUserId(userId);
        log.setAction(auditLog.action());
        log.setMethodArgs(Arrays.toString(jp.getArgs()));
        log.setCreatedAt(LocalDateTime.now());

        auditLogRepository.save(log);
    }
}

// 使用
@PutMapping("/orders/{id}/status")
@AuditLog(action = "修改訂單狀態")
public Order updateStatus(@PathVariable String id, @RequestBody StatusRequest req) {
    return orderService.updateStatus(id, req.getStatus());
}
```

---

## 六、AOP 的限制與常見坑

### 坑一：同類別內部呼叫不走 AOP

```java
@Service
public class OrderService {

    @Transactional
    public void createOrder(OrderRequest req) {
        // 業務邏輯...
        sendNotification(req);  // ❌ 這個呼叫不走 AOP！
    }

    @Transactional(propagation = REQUIRES_NEW)  // 期望新 Transaction，但不會生效
    public void sendNotification(OrderRequest req) {
        // 因為這是 this.sendNotification()，繞過了 Proxy
    }
}
```

**解法：** 注入自己（Spring 4.3+），或把方法移到另一個 Service：

```java
@Service
public class OrderService {

    @Autowired
    @Lazy  // 避免循環依賴
    private OrderService self;  // 注入自己的 Proxy

    @Transactional
    public void createOrder(OrderRequest req) {
        self.sendNotification(req);  // ✅ 走 Proxy，AOP 生效
    }
}
```

### 坑二：@Transactional 加在 private 方法上

```java
@Service
public class OrderService {
    @Transactional  // ❌ 完全沒效果！Proxy 無法覆寫 private 方法
    private void doCreate(Order order) { ... }
}
```

**規則：** 被 AOP 攔截的方法必須是 `public`（CGLIB 也無法覆寫 `final` 方法）。

### 坑三：沒有 Interface 的類別無法用 JDK 動態代理

Spring 預設用 CGLIB（子類別代理），不需要 Interface。但如果你的 Bean 有被 `final` 修飾，CGLIB 就無法建立子類別。

```java
public final class OrderService { ... }   // ❌ Spring AOP 無法代理
```

---

## 本章練習題

**Q1：@Around advice 裡如果忘記呼叫 `pjp.proceed()`，會發生什麼事？**
<details>
<summary>答案</summary>
真實方法不會被執行！@Around 完全控制方法呼叫，如果沒有呼叫 pjp.proceed()，原本的方法就被跳過了。這個 bug 很容易被忽略（不報錯，只是靜默不執行）。務必確保每條程式碼路徑都有 pjp.proceed()。
</details>

**Q2：Spring 的 @Transactional 底層是 AOP，那它是 @Before 還是 @Around 實作的？**
<details>
<summary>答案</summary>
是 @Around。因為 @Transactional 需要在方法「前」開啟 Transaction，在方法「後」根據是否有例外決定 commit 或 rollback。這必須完整包裹方法執行過程，只有 @Around 才能做到：pjp.proceed() 前開 Transaction，正常結束後 commit，catch 例外後 rollback。
</details>

**Q3：如果兩個 Aspect 都攔截了同一個方法，執行順序是什麼？**
<details>
<summary>答案</summary>
預設順序是不確定的。可以用 @Order 注解指定優先順序（數字越小越先執行外層）。類比洋蔥模型：@Order(1) 的 Aspect 是最外層，先執行 Before，最後執行 After；@Order(2) 的 Aspect 在內層。例如：外層做日誌，內層做事務，確保日誌能記錄到事務的完整結果。
</details>
