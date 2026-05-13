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
