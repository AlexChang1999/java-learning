# 第七章：現代 Java 語法（Java 8+）
> 課程原始碼使用舊式寫法，但工作上天天在用這些

---

## 前言

bradchao 的課程大約是 Java 7 以前的風格。從 Java 8（2014年）開始，Java 加入了很多新語法，讓程式碼變得更簡潔。

這章介紹你在公司程式碼裡一定會看到的四樣東西：
1. **Lambda 表達式**
2. **Stream API**
3. **Optional**
4. **var 型別推斷（Java 10+）**

---

## 一、Lambda 表達式

### 問題背景：匿名類別太囉嗦了

課程 Brad32 介紹了匿名類別，但語法非常冗長：

```java
// 舊式：匿名類別
List<String> names = new ArrayList<>(Arrays.asList("Brad", "Andy", "Peter"));
Collections.sort(names, new Comparator<String>() {
    @Override
    public int compare(String a, String b) {
        return a.compareTo(b);  // 按字母排序
    }
});
```

### Lambda 的解法

Lambda 是「只有一個方法的匿名類別」的簡寫：

```java
// 新式：Lambda
Collections.sort(names, (a, b) -> a.compareTo(b));

// 甚至更短：方法參考
Collections.sort(names, String::compareTo);
```

### Lambda 語法解析

```
(參數) -> 回傳值
(參數) -> { 多行程式碼; return 值; }

// 範例
(int x) -> x * 2          // 傳入 x，回傳 x*2
(x, y) -> x + y           // 型別可省略
() -> System.out.println("Hi")  // 無參數
(String s) -> {
    System.out.println(s);
    return s.length();
}
```

### Lambda 必須搭配「函數式介面」

函數式介面 = 只有一個抽象方法的介面（用 `@FunctionalInterface` 標記）

```java
// Java 內建的函數式介面
Runnable r = () -> System.out.println("執行！");  // 無參無回傳
Comparator<String> c = (a, b) -> a.compareTo(b);  // 兩個參數

// Java 8 新增的常用函數式介面
Consumer<String> consumer = s -> System.out.println(s);  // 吃一個，不回傳
Function<String, Integer> fn = s -> s.length();           // 吃一個，回傳一個
Predicate<String> pred = s -> s.startsWith("B");          // 回傳 boolean
Supplier<String> sup = () -> "Hello";                      // 不吃，回傳一個
```

---

## 二、Stream API

Stream 是對集合進行**流水線式操作**的 API，讓「篩選、轉換、統計」這類操作變得非常簡潔。

### 對比：傳統 vs Stream

```java
List<String> names = List.of("Brad", "Andy", "Peter", "Tony", "Ben");

// 傳統做法：找出 B 開頭的名字，轉大寫，排序，印出
List<String> result = new ArrayList<>();
for (String name : names) {
    if (name.startsWith("B")) {
        result.add(name.toUpperCase());
    }
}
Collections.sort(result);
for (String s : result) System.out.println(s);

// Stream 做法：一行搞定
names.stream()
     .filter(name -> name.startsWith("B"))   // 篩選
     .map(String::toUpperCase)                // 轉換
     .sorted()                                // 排序
     .forEach(System.out::println);           // 執行
```

### 常用 Stream 操作

```java
List<Integer> nums = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

// filter：篩選符合條件的元素
nums.stream()
    .filter(n -> n % 2 == 0)    // 只保留偶數
    .forEach(System.out::println);  // 2 4 6 8 10

// map：把每個元素轉換成另一個東西
nums.stream()
    .map(n -> n * n)             // 每個數平方
    .forEach(System.out::println);  // 1 4 9 16 25...

// reduce：把所有元素「折疊」成一個值
int sum = nums.stream()
              .reduce(0, (a, b) -> a + b);  // 總和
// 或更短：
int sum2 = nums.stream().mapToInt(Integer::intValue).sum();

// collect：收集結果成 List/Set/Map
List<Integer> evenList = nums.stream()
                              .filter(n -> n % 2 == 0)
                              .collect(Collectors.toList());

// anyMatch / allMatch / noneMatch：判斷
boolean hasEven = nums.stream().anyMatch(n -> n % 2 == 0);   // 有偶數嗎？
boolean allPos  = nums.stream().allMatch(n -> n > 0);         // 全是正數嗎？
boolean noNeg   = nums.stream().noneMatch(n -> n < 0);        // 沒有負數嗎？

// count / min / max
long count = nums.stream().filter(n -> n > 5).count();  // 大於5的有幾個
Optional<Integer> max = nums.stream().max(Integer::compareTo);

// sorted + limit：取前3名（最大值）
nums.stream()
    .sorted((a, b) -> b - a)    // 由大到小
    .limit(3)                    // 只取前3個
    .forEach(System.out::println);  // 10 9 8
```

### Stream 的兩種操作類型

| 類型 | 說明 | 範例 |
|------|------|------|
| 中間操作（Intermediate）| 回傳 Stream，可繼續串接，懶惰求值 | `filter`, `map`, `sorted` |
| 終止操作（Terminal）| 觸發計算，結束 Stream | `forEach`, `collect`, `count` |

**「懶惰求值」** 的意思：中間操作在沒有終止操作前**不會執行**。

---

## 三、Optional（避免 NullPointerException）

`NullPointerException` 是 Java 最常見的錯誤之一。`Optional` 是一個包裝器，明確表示「這個值可能不存在」。

### 傳統寫法 vs Optional

```java
// 傳統：容易忘記判斷 null
String name = getUserName();
System.out.println(name.toUpperCase());  // 如果 name 是 null → NullPointerException！

// 加防護（但很囉嗦）
if (name != null) {
    System.out.println(name.toUpperCase());
} else {
    System.out.println("未知使用者");
}

// Optional 寫法
Optional<String> optName = Optional.ofNullable(getUserName());
System.out.println(optName.map(String::toUpperCase).orElse("未知使用者"));
```

### Optional 常用方法

```java
Optional<String> opt1 = Optional.of("Brad");          // 一定有值（null 會拋錯）
Optional<String> opt2 = Optional.ofNullable(null);    // 可能是 null
Optional<String> opt3 = Optional.empty();              // 明確沒有值

// 取值
opt1.get();                         // "Brad"（空的話拋例外，少用）
opt2.orElse("預設值");              // 空的話回傳預設值
opt2.orElseGet(() -> "計算出的預設值");  // 空的話執行 Lambda
opt2.orElseThrow(() -> new RuntimeException("找不到！"));

// 判斷
opt1.isPresent();   // true（有值）
opt2.isEmpty();     // true（空的，Java 11+）

// 轉換（類似 Stream）
opt1.map(String::toUpperCase);      // Optional<"BRAD">
opt1.filter(s -> s.startsWith("B")); // Optional<"Brad">
opt1.ifPresent(System.out::println); // 有值才執行，不回傳
```

---

## 四、var 型別推斷（Java 10+）

Java 10 起可以用 `var` 讓編譯器自動推斷型別，讓程式碼更簡潔：

```java
// 傳統（型別重複寫兩次）
ArrayList<HashMap<String, List<Integer>>> data = new ArrayList<>();

// 用 var（只需寫一次）
var data = new ArrayList<HashMap<String, List<Integer>>>();

// 更多範例
var name = "Brad";              // 推斷為 String
var count = 42;                 // 推斷為 int
var bike = new Bike();          // 推斷為 Bike
var names = List.of("A", "B"); // 推斷為 List<String>
```

**⚠️ 限制：**
- 只能用在**局部變數**（方法內），不能用在欄位或參數
- 必須在宣告時**同時賦值**（`var x;` 不合法）
- 不代表動態型別，編譯後型別是固定的

---

## 五、綜合範例：從舊式到現代 Java

### 原始課程風格 vs 現代寫法

```java
// ─── 課程風格 ─────────────────────────────────
// 找出 List 中大於 10 的偶數，加總
List<Integer> nums = Arrays.asList(3, 7, 12, 18, 5, 22, 8, 15);
int total = 0;
for (Integer n : nums) {
    if (n > 10 && n % 2 == 0) {
        total += n;
    }
}
System.out.println(total);

// ─── 現代寫法 ─────────────────────────────────
int total = nums.stream()
                .filter(n -> n > 10 && n % 2 == 0)
                .mapToInt(Integer::intValue)
                .sum();
System.out.println(total);
```

```java
// ─── 課程風格：按長度排序字串 ────────────────
List<String> words = new ArrayList<>(Arrays.asList("banana", "apple", "kiwi", "cherry"));
Collections.sort(words, new Comparator<String>() {
    @Override
    public int compare(String a, String b) {
        return Integer.compare(a.length(), b.length());
    }
});

// ─── 現代寫法 ─────────────────────────────────
words.sort(Comparator.comparingInt(String::length));
```

---

## 五、Java 14–21 重要新語法

### Text Block（文字區塊，Java 15 正式）

以前寫 JSON/SQL/HTML 要拼接很多字串，現在用三個引號包起來：

```java
// 舊寫法（充滿 \n 和 +，難以閱讀）
String json = "{\n" +
              "  \"name\": \"王小明\",\n" +
              "  \"age\": 25\n" +
              "}";

// Text Block（Java 15+）
String json = """
        {
          "name": "王小明",
          "age": 25
        }
        """;   // 結束引號的縮排決定要去掉多少前置空白
```

### Switch Expressions（Java 14 正式）

```java
// 舊 switch：像瀑布（容易忘 break）
String result;
switch (day) {
    case MONDAY: case FRIDAY: case SUNDAY:
        result = "週末或週一"; break;
    default:
        result = "工作日";
}

// 新 switch expression（Java 14+）
String result = switch (day) {
    case MONDAY, FRIDAY, SUNDAY -> "週末或週一";
    case TUESDAY -> "週二";
    default -> "工作日";
};

// 配合 yield 回傳值（複雜邏輯）
int fee = switch (memberLevel) {
    case GOLD -> 0;
    case SILVER -> {
        int discount = calculateDiscount();
        yield 100 - discount;   // yield 代替 return
    }
    default -> 100;
};
```

### Records（Java 16 正式）

以前寫一個純資料類別（DTO）要寫一堆 getter/setter/equals/hashCode/toString：

```java
// 舊做法：十幾行樣板程式碼
public class OrderDTO {
    private final String orderId;
    private final double amount;
    public OrderDTO(String orderId, double amount) { ... }
    public String getOrderId() { return orderId; }
    public double getAmount() { return amount; }
    // equals, hashCode, toString...
}

// Record（Java 16+）：一行搞定
public record OrderDTO(String orderId, double amount) {}
// 自動生成：建構子、getters（orderId()、amount()）、equals、hashCode、toString

// 使用
OrderDTO dto = new OrderDTO("ORD-001", 999.0);
System.out.println(dto.orderId());    // ORD-001
System.out.println(dto);             // OrderDTO[orderId=ORD-001, amount=999.0]

// Record 是 final 的（不可繼承），欄位是 final 的（不可修改）
// 適合：DTO、Value Object、API 回應類別
```

### Pattern Matching for instanceof（Java 16 正式）

```java
// 舊寫法：先判斷再強轉（重複說兩次類型）
if (obj instanceof String) {
    String s = (String) obj;  // 多餘的強轉
    System.out.println(s.length());
}

// 新寫法（Java 16+）：判斷和轉型合為一步
if (obj instanceof String s) {
    System.out.println(s.length());  // s 直接可用
}

// 配合 switch（Java 21 正式的 Pattern Matching for switch）
Object shape = getShape();
String desc = switch (shape) {
    case Circle c -> "半徑 " + c.radius();
    case Rectangle r -> "寬 " + r.width() + " 高 " + r.height();
    case null -> "空";
    default -> "未知形狀";
};
```

### Sealed Classes（Java 17 正式）

限制哪些類別可以繼承，讓繼承關係可預測、可窮舉：

```java
// 宣告：Shape 只能被 Circle、Rectangle、Triangle 繼承
public sealed interface Shape permits Circle, Rectangle, Triangle {}

public record Circle(double radius) implements Shape {}
public record Rectangle(double width, double height) implements Shape {}
public record Triangle(double base, double height) implements Shape {}

// 好處：switch 可以窮舉，不需要 default
double area = switch (shape) {
    case Circle c -> Math.PI * c.radius() * c.radius();
    case Rectangle r -> r.width() * r.height();
    case Triangle t -> 0.5 * t.base() * t.height();
    // 不需要 default：編譯器知道 Shape 只有這三種
};
```

### Virtual Threads（虛擬執行緒，Java 21 正式）<!-- 🔴 資深 -->

這是 Java 21 最重要的特性，顛覆了「要高並發就要用 WebFlux」的認知。

```
傳統平台執行緒（Platform Thread）：
  每個 Thread 對應一個 OS Thread
  每個 OS Thread 佔 ~1MB Stack 記憶體
  100 萬請求 = 100 萬 OS Thread = 1TB 記憶體（不可能）

虛擬執行緒（Virtual Thread）：
  由 JVM 管理，不直接對應 OS Thread
  每個虛擬執行緒只佔幾 KB
  100 萬個虛擬執行緒完全可行！
  阻塞時 JVM 自動把 OS Thread 讓出給其他虛擬執行緒
```

```java
// 建立虛擬執行緒（極其簡單）
Thread vThread = Thread.ofVirtual().start(() -> {
    // 這裡的阻塞（JDBC、HTTP 呼叫）不會真正阻塞 OS Thread
    var result = database.query("SELECT ...");
    System.out.println(result);
});

// Spring Boot 3.2+ 開啟虛擬執行緒（一行設定）
// application.yml：
// spring.threads.virtual.enabled: true
// 效果：所有 HTTP 請求處理改用虛擬執行緒，不再需要 WebFlux！

// 傳統寫法（阻塞 JDBC），配上虛擬執行緒，就能達到 WebFlux 的高並發
@RestController
public class OrderController {
    // 虛擬執行緒開啟後，即使這裡阻塞，也不會浪費 OS Thread
    @GetMapping("/orders/{id}")
    public Order getOrder(@PathVariable Long id) {
        return orderRepository.findById(id).orElseThrow(); // 阻塞 OK！
    }
}
```

**虛擬執行緒 vs WebFlux 選型（Java 21 時代）：**

| | 虛擬執行緒 | WebFlux |
|---|---|---|
| 程式碼風格 | 傳統同步（易學易懂）| 響應式（學習曲線高）|
| 效能上限 | 高（對 I/O 密集場景）| 更高（CPU 密集也優化）|
| 適合場景 | 大部分 Web 服務 | 需要背壓控制的 Streaming |
| Spring Boot | 3.2+ 一行開啟 | 需要換 webflux 依賴 |

> 💡 建議：新專案優先考慮虛擬執行緒（簡單），只有確定需要背壓/SSE 才用 WebFlux。

---

## 六、Java 日期時間 API（Java 8+）<!-- 💡 進階 -->

Java 8 之前，`java.util.Date` 和 `Calendar` 設計混亂（月份從 0 開始！）、執行緒不安全。  
Java 8 引入了全新的 `java.time` 套件，徹底解決這些問題。

### 核心類別與選型

```
什麼時候用哪個？

LocalDate          → 只有日期，無時間，無時區（生日、節假日）
                     例：2024-01-15

LocalTime          → 只有時間，無日期，無時區（每天幾點開盤）
                     例：09:30:00

LocalDateTime      → 日期 + 時間，無時區（本地時間、DB 存儲）
                     例：2024-01-15T09:30:00

ZonedDateTime      → 日期 + 時間 + 時區（跨時區系統、顯示給用戶）
                     例：2024-01-15T09:30:00+08:00[Asia/Taipei]

Instant            → Unix 毫秒時間戳（系統間傳遞時間、記錄事件順序）
                     例：1705275000000（從 UTC 1970-01-01 開始計算）

Duration           → 表示「時間長度」（兩個時刻之差）
Period             → 表示「日期長度」（幾年幾月幾日）
```

### 常用操作

```java
// --- LocalDate 操作 ---
LocalDate today = LocalDate.now();                     // 今天
LocalDate birthday = LocalDate.of(1990, 3, 15);        // 指定日期
LocalDate nextMonth = today.plusMonths(1);             // 加一個月
LocalDate lastYear = today.minusYears(1);              // 減一年
boolean isLeap = today.isLeapYear();                   // 是否閏年

// --- LocalDateTime 操作 ---
LocalDateTime now = LocalDateTime.now();
LocalDateTime orderTime = LocalDateTime.of(2024, 1, 15, 9, 30, 0);
// 格式化輸出
String formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
// 解析字串
LocalDateTime parsed = LocalDateTime.parse("2024-01-15 09:30:00",
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

// --- ZonedDateTime 操作 ---
ZoneId taipei = ZoneId.of("Asia/Taipei");
ZoneId tokyo  = ZoneId.of("Asia/Tokyo");
ZonedDateTime taipeiTime = ZonedDateTime.now(taipei);
ZonedDateTime tokyoTime  = taipeiTime.withZoneSameInstant(tokyo);  // 轉換時區
// 台北 09:00 → 東京 10:00（東京比台北快一小時）

// --- Instant 操作 ---
Instant now2 = Instant.now();
long epochMilli = now2.toEpochMilli();  // 取得毫秒時間戳
Instant fromMilli = Instant.ofEpochMilli(1705275000000L);  // 從時間戳還原

// --- Duration / Period ---
LocalDateTime start = LocalDateTime.of(2024, 1, 1, 9, 0);
LocalDateTime end   = LocalDateTime.of(2024, 1, 1, 11, 30);
Duration duration = Duration.between(start, end);
long hours = duration.toHours();    // 2
long mins  = duration.toMinutes();  // 150

LocalDate from = LocalDate.of(2023, 1, 1);
LocalDate to   = LocalDate.of(2024, 3, 15);
Period period = Period.between(from, to);
// period.getYears() = 1, period.getMonths() = 2, period.getDays() = 14
```

### 時區陷阱（面試常考）

```java
// ❌ 陷阱 1：LocalDateTime 沒有時區，存到 DB 可能被解讀錯
LocalDateTime orderCreated = LocalDateTime.now();
// 台北機器存 "2024-01-15 09:30:00"
// 日本機器讀出來也是 "2024-01-15 09:30:00"，但實際上差了 1 小時！

// ✅ 解法：跨系統傳遞時間一律用 Instant（UTC 毫秒，無歧義）
Instant orderCreated2 = Instant.now();  // UTC 的絕對時間點
// 或者存 ZonedDateTime，攜帶時區資訊

// ❌ 陷阱 2：夏令時（Daylight Saving Time）
// 美國/歐洲有夏令時，某些時間點不存在或重複出現
// 2024-03-10 02:00 美東時間不存在（直接跳到 03:00）
ZonedDateTime dst = ZonedDateTime.of(2024, 3, 10, 2, 30, 0, 0,
    ZoneId.of("America/New_York"));
// ZonedDateTime 會自動調整，不會報錯，但初學者可能不知道這個行為

// ❌ 陷阱 3：MySQL DATETIME vs TIMESTAMP
// DATETIME：存什麼讀什麼，無時區轉換（安全，但要自己處理時區）
// TIMESTAMP：存時自動轉 UTC，讀時自動轉 DB 所在時區（可能造成混亂）
// 推薦：用 DATETIME 配合應用層統一用 UTC 存儲
```

### 與舊 API 互轉

```java
// 舊 Date → 新 API
Date oldDate = new Date();
Instant instant       = oldDate.toInstant();
LocalDateTime newDate = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());

// 新 API → 舊 Date（與舊框架整合時需要）
LocalDateTime localDt = LocalDateTime.now();
Date backToOld = Date.from(localDt.atZone(ZoneId.systemDefault()).toInstant());

// Calendar → LocalDate
Calendar cal = Calendar.getInstance();
LocalDate fromCal = cal.toInstant()
    .atZone(ZoneId.systemDefault())
    .toLocalDate();
```

### DateTimeFormatter 格式化

```java
// 預定義格式
DateTimeFormatter.ISO_LOCAL_DATE        // "2024-01-15"
DateTimeFormatter.ISO_LOCAL_DATE_TIME   // "2024-01-15T09:30:00"

// 自訂格式（執行緒安全！DateTimeFormatter 是 Immutable）
DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");
String s = LocalDateTime.now().format(fmt);         // "2024/01/15 09:30"
LocalDateTime dt = LocalDateTime.parse("2024/01/15 09:30", fmt);

// ⚠️ 注意：SimpleDateFormat 不是執行緒安全的！多執行緒環境必改用 DateTimeFormatter
// 舊寫法（危險）：static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
// 新寫法（安全）：static DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
```

---

## 本章重點整理

| 特性 | 說明 | Java 版本 |
|------|------|----------|
| Lambda | 簡化匿名類別，`(參數) -> 回傳值` | Java 8 |
| Stream | 流水線處理集合 | Java 8 |
| Optional | 安全處理可能為 null 的值 | Java 8 |
| var | 局部變數型別推斷 | Java 10 |

---

## 延伸問題

**Q1：將以下傳統寫法改寫成 Stream 版本：**
```java
// 傳統：找出 names 中長度超過 4 的名字，按字母排序後收集成 List
List<String> names = Arrays.asList("Brad", "Andy", "Peter", "Tony", "Alexander");
List<String> result = new ArrayList<>();
for (String name : names) {
    if (name.length() > 4) result.add(name);
}
Collections.sort(result);
```
<details>
<summary>參考答案</summary>

```java
List<String> result = names.stream()
    .filter(name -> name.length() > 4)
    .sorted()
    .collect(Collectors.toList());
```
</details>

**Q2：以下 Lambda 的完整型別應該是什麼？**
```java
var fn = (String s) -> s.length() > 3;
```
<details>
<summary>答案</summary>
`Predicate<String>`，因為它接受一個 String，回傳 boolean。
</details>

**Q3：以下程式碼有什麼問題？**
```java
Optional<String> opt = Optional.of(null);
String result = opt.get();
```
<details>
<summary>答案</summary>
`Optional.of(null)` 會立刻拋出 NullPointerException。應改為 `Optional.ofNullable(null)`。另外，`opt.get()` 在 Optional 為空時也會拋出例外，應改用 `orElse()` 或 `orElseThrow()`。
</details>
