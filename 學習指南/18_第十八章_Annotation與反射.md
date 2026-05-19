# 第十八章：Annotation 與反射（Reflection）
> Spring、JUnit、Lombok 背後的魔法：程式在執行時讀取自己的結構

---

## 前言：框架怎麼「認識」你的類別？

你寫 `@RestController`，Spring 就知道這個類別要處理 HTTP 請求。  
你寫 `@Test`，JUnit 就知道這個方法要執行測試。

**它們怎麼做到的？靠的就是 Annotation + Reflection。**

---

## 一、Annotation（注解）基礎

### 你已經用過的注解

```java
@Override           // 告訴編譯器：這個方法覆寫了父類別的方法
@Deprecated         // 標記這個 API 已廢棄，呼叫時顯示警告
@SuppressWarnings("unchecked")  // 叫編譯器閉嘴，不要顯示特定警告
@FunctionalInterface            // 確認這個介面是函數式介面（只有一個抽象方法）
```

### 自訂 Annotation

```java
import java.lang.annotation.*;

// 定義一個注解（注解的注解稱為「元注解」）
@Retention(RetentionPolicy.RUNTIME)  // 保留到執行時（可以被反射讀取）
@Target(ElementType.METHOD)          // 只能加在方法上
public @interface RateLimit {
    int requestsPerSecond() default 100;  // 注解的屬性（有預設值）
    String message() default "超過請求速率限制";
}
```

```java
// 使用自訂注解
public class TradingController {

    @RateLimit(requestsPerSecond = 1000, message = "訂單提交頻率過高")
    public void submitOrder(Order order) {
        // ...
    }

    @RateLimit  // 使用預設值（100 requests/s）
    public List<Trade> getTradeHistory() {
        // ...
    }
}
```

### Retention 策略（注解的生命週期）

```
RetentionPolicy.SOURCE   → 只在原始碼存在，編譯後消失
                           例：@Override（編譯器用完就丟）

RetentionPolicy.CLASS    → 保留在 .class 檔案，執行時不可見（預設值）
                           例：某些 IDE 工具使用

RetentionPolicy.RUNTIME  → 保留到執行時，可以被反射讀取
                           例：@Test（JUnit 執行時讀取）、@RequestMapping（Spring 執行時讀取）
```

### Target（注解可以加在哪裡）

```java
@Target({
    ElementType.TYPE,        // 類別、介面、enum
    ElementType.METHOD,      // 方法
    ElementType.FIELD,       // 欄位（成員變數）
    ElementType.PARAMETER,   // 方法參數
    ElementType.CONSTRUCTOR, // 建構子
    ElementType.LOCAL_VARIABLE, // 區域變數
    ElementType.ANNOTATION_TYPE // 其他注解（元注解）
})
```

---

## 二、Reflection（反射）：程式在執行時「看清自己」

### 取得 Class 物件（三種方式）

```java
// 方式一：用 .class 語法（編譯時已知型別）
Class<Order> c1 = Order.class;

// 方式二：用物件的 getClass()（執行時的實際型別）
Order order = new Order(...);
Class<?> c2 = order.getClass();

// 方式三：用類別全名字串（最常見於框架）
Class<?> c3 = Class.forName("tw.brad.tutor.Order");  // 可能拋出 ClassNotFoundException
```

### 讀取類別資訊

```java
Class<?> clazz = Order.class;

// 類別基本資訊
System.out.println(clazz.getName());        // tw.brad.tutor.Order
System.out.println(clazz.getSimpleName()); // Order
System.out.println(clazz.getSuperclass()); // class java.lang.Object

// 取得所有欄位（包含 private）
Field[] fields = clazz.getDeclaredFields();
for (Field field : fields) {
    System.out.println(field.getName() + ": " + field.getType().getSimpleName());
}
// 輸出：orderId: String
//       symbol: String
//       price: double
//       ...

// 取得所有方法
Method[] methods = clazz.getDeclaredMethods();
for (Method method : methods) {
    System.out.println(method.getName() + "(" +
        Arrays.stream(method.getParameterTypes())
              .map(Class::getSimpleName)
              .collect(Collectors.joining(", ")) + ")");
}
// 輸出：getOrderId()
//       fill(int)
//       ...
```

### 動態建立物件與呼叫方法

```java
// 動態建立物件
Class<?> clazz = Class.forName("tw.brad.tutor.OrderBook");
Constructor<?> ctor = clazz.getDeclaredConstructor(String.class);
Object book = ctor.newInstance("TSLA");   // 等同 new OrderBook("TSLA")

// 動態呼叫方法
Method addOrder = clazz.getDeclaredMethod("addOrder", Order.class);
Object trades = addOrder.invoke(book, new Order(...));  // 等同 book.addOrder(order)

// 讀取 / 修改 private 欄位（突破封裝！）
Field field = clazz.getDeclaredField("symbol");
field.setAccessible(true);           // 強制開放 private 存取
String symbol = (String) field.get(book);  // 讀取
field.set(book, "AAPL");             // 修改
```

---

## 三、實戰：用 Annotation + Reflection 做一個小框架

這就是 Spring / JUnit 的縮小版。

### 場景：自動執行所有標記 `@Test` 的方法

```java
// 第一步：定義注解
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MyTest {
    String description() default "";
}

// 第二步：在測試類別中使用
public class OrderBookSpec {

    private OrderBook book;

    @MyTest(description = "限價單應該成功撮合")
    public void testLimitOrderMatch() {
        book = new OrderBook("TSLA");
        book.addOrder(new Order("S1", "TSLA", "SELL", OrderType.LIMIT, 350.0, 100));
        List<Trade> trades = book.addOrder(
            new Order("B1", "TSLA", "BUY", OrderType.LIMIT, 350.0, 100)
        );
        if (trades.size() != 1) throw new AssertionError("應該有 1 筆成交");
        System.out.println("✅ 通過");
    }

    @MyTest(description = "FOK 數量不足應該取消")
    public void testFOKCancel() {
        book = new OrderBook("TSLA");
        book.addOrder(new Order("S1", "TSLA", "SELL", OrderType.LIMIT, 350.0, 50));
        List<Trade> trades = book.addOrder(
            new Order("F1", "TSLA", "BUY", OrderType.FOK, 350.0, 100)
        );
        if (!trades.isEmpty()) throw new AssertionError("FOK 應該取消，不應有成交");
        System.out.println("✅ 通過");
    }
}

// 第三步：測試執行器（框架本體）
public class MyTestRunner {

    public static void run(Class<?> testClass) throws Exception {
        Object instance = testClass.getDeclaredConstructor().newInstance();

        int passed = 0, failed = 0;
        for (Method method : testClass.getDeclaredMethods()) {

            // 關鍵：用反射讀取注解！
            if (method.isAnnotationPresent(MyTest.class)) {
                MyTest annotation = method.getAnnotation(MyTest.class);
                System.out.print("測試：" + annotation.description() + " ... ");

                try {
                    method.invoke(instance);
                    passed++;
                } catch (Exception e) {
                    System.out.println("❌ 失敗：" + e.getCause().getMessage());
                    failed++;
                }
            }
        }
        System.out.printf("%n結果：%d 通過，%d 失敗%n", passed, failed);
    }

    public static void main(String[] args) throws Exception {
        run(OrderBookSpec.class);
    }
}
```

**你剛才實作的，就是 JUnit 的核心原理。**

---

## 四、實戰：自動注入依賴（Spring @Autowired 的原理）

```java
// 定義 @Inject 注解（對應 Spring 的 @Autowired）
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Inject {}

// 定義一個服務
public class TradeReporter {
    public void report(Trade trade) {
        System.out.println("回報成交：" + trade);
    }
}

// 使用 @Inject
public class MatchingService {
    @Inject
    private TradeReporter reporter;  // Spring 會幫你「注入」這個物件

    public void processOrder(Order order) {
        // reporter 不需要自己 new，框架會自動填充
        reporter.report(new Trade(...));
    }
}

// 簡易 IoC 容器（Spring 的縮小版）
public class SimpleContainer {

    public static <T> T create(Class<T> clazz) throws Exception {
        T instance = clazz.getDeclaredConstructor().newInstance();

        // 掃描所有欄位，找到標記了 @Inject 的欄位
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(Inject.class)) {
                // 自動建立依賴並注入
                Object dependency = field.getType()
                                        .getDeclaredConstructor()
                                        .newInstance();
                field.setAccessible(true);
                field.set(instance, dependency);
                System.out.println("注入：" + field.getType().getSimpleName()
                                   + " → " + clazz.getSimpleName());
            }
        }
        return instance;
    }
}

// 使用
MatchingService service = SimpleContainer.create(MatchingService.class);
// 輸出：注入：TradeReporter → MatchingService
// service.reporter 已自動填充，不需要手動 new！
```

---

## 五、實戰：自動速率限制（AOP 的原理）

```java
// 結合 @RateLimit 注解，在執行時自動攔截並限速
public class RateLimitProxy {

    private final Object target;
    private final Map<String, Long> lastCallTime = new ConcurrentHashMap<>();

    public RateLimitProxy(Object target) {
        this.target = target;
    }

    public Object invoke(String methodName, Object... args) throws Exception {
        Method method = findMethod(methodName);
        RateLimit rateLimit = method.getAnnotation(RateLimit.class);

        if (rateLimit != null) {
            long minInterval = 1000L / rateLimit.requestsPerSecond();
            long now = System.currentTimeMillis();
            Long last = lastCallTime.get(methodName);

            if (last != null && now - last < minInterval) {
                throw new RuntimeException(rateLimit.message());
            }
            lastCallTime.put(methodName, now);
        }

        return method.invoke(target, args);
    }

    private Method findMethod(String name) throws NoSuchMethodException {
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        throw new NoSuchMethodException(name);
    }
}

// 這就是 Spring AOP（Aspect-Oriented Programming）的核心概念：
// 用代理物件在方法呼叫前後插入橫切邏輯（日誌、權限、快取、速率限制...）
```

---

## 六、反射的效能考量

```
反射呼叫 vs 直接呼叫的效能比較：

直接呼叫：      method.invoke() 反射呼叫：
~ 1 ns          ~ 50~100 ns（慢 50~100 倍）

原因：
1. 需要做動態類型檢查
2. 無法被 JIT 內聯（第十一章學過的）
3. 安全性檢查（setAccessible）

解法：
- 快取 Method 物件（不要每次都 getDeclaredMethod()）
- 使用 MethodHandle（Java 7+，比反射快接近直接呼叫）
- 在啟動時用反射建立物件，執行時用直接呼叫（Spring 的做法）
```

```java
// 效能優化：快取 MethodHandle
MethodHandles.Lookup lookup = MethodHandles.lookup();
MethodHandle handle = lookup.findVirtual(
    OrderBook.class,
    "addOrder",
    MethodType.methodType(List.class, Order.class)
);
// 之後可以多次呼叫，效能接近直接呼叫
List<Trade> trades = (List<Trade>) handle.invoke(book, order);
```

---

## 本章練習題

**Q1：`@Retention(RetentionPolicy.CLASS)` 和 `@Retention(RetentionPolicy.RUNTIME)` 有什麼差異？**
<details>
<summary>答案</summary>
CLASS（預設值）：注解資訊保存在 .class 檔案中，但 JVM 執行時不會載入到記憶體，反射無法讀取到。適合編譯器或位元組碼分析工具使用。RUNTIME：注解資訊保存到執行時記憶體，可以用 method.getAnnotation() 讀取。JUnit 的 @Test、Spring 的 @Component 都必須是 RUNTIME，因為框架需要在程式執行時掃描並處理這些注解。
</details>

**Q2：用反射讀取 private 欄位需要做什麼？這樣做有什麼風險？**
<details>
<summary>答案</summary>
需要呼叫 field.setAccessible(true)，強制突破 private 存取限制。風險：(1) 破壞封裝原則，可能讀到或修改本不應該被外部存取的內部狀態；(2) Java 9+ 的模組系統（JPMS）會限制跨模組反射，可能在新版 JDK 上失效；(3) 效能比直接存取慢；(4) JVM 安全管理器（SecurityManager）可能阻止這個操作。正常業務程式碼不應該用這個，只有框架（如 ORM、序列化函式庫）才有理由這樣做。
</details>

**Q3：請說明為什麼 Spring 能在不改你程式碼的情況下，只靠 @Transactional 注解就讓方法具備資料庫交易能力。**
<details>
<summary>答案</summary>
Spring 在啟動時掃描所有 Bean，找出有 @Transactional 的方法。然後用動態代理（JDK Proxy 或 CGLIB）建立一個「包裝類別」，這個包裝類別在每次呼叫標記方法時，先 BEGIN TRANSACTION，呼叫你的原始方法，成功就 COMMIT，失敗就 ROLLBACK。你拿到的 Bean 其實是這個代理物件，不是你原始的類別實例。這個模式就是 AOP（面向切面程式設計），而 @Transactional 注解本身只是一個「標記」，真正的邏輯全在代理物件中。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 5 章 | Phase 1：Java 語言核心
> 下一章（第 6 章）：[第六章：遞迴與演算法複雜度](06_第六章_遞迴與演算法複雜度.md)
