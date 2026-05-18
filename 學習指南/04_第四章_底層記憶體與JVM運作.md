# 第四章：底層記憶體與 JVM 運作原理
> 這章沒有對應的原始碼課號，但它是理解所有「奇怪行為」的根本

---

## 為什麼要學這章？

你可能遇過這些問題：
- 「為什麼 `String` 用 `==` 比較會是 `false`？」
- 「為什麼傳物件進方法可以改，但重新賦值改不了？」
- 「`new` 到底做了什麼？」
- 「為什麼 Java 不需要手動釋放記憶體？」

這些問題的答案都在這章。

---

## 一、Java 程式的執行流程（JVM 概觀）

你寫的 `.java` 檔案不是直接被電腦執行的，中間有一層翻譯：

```
你寫的程式碼          編譯器              JVM 執行
HelloWorld.java  →  HelloWorld.class  →  電腦真正執行
（人類看得懂）       （Bytecode，         （JVM 把 Bytecode
                    JVM 看得懂）          翻譯成機器碼）
```

**這個設計的好處：**  
`.class` 檔在 Windows、Mac、Linux 的 JVM 上都能跑，這就是 Java 的「一次編寫，到處執行（Write Once, Run Anywhere）」。

```
javac HelloWorld.java   ← 編譯：產生 HelloWorld.class
java HelloWorld         ← 執行：JVM 讀取 .class 執行
```

---

## 二、記憶體的兩個主要區域

JVM 執行程式時，記憶體分成好幾個區域，最重要的是這兩個：

```
┌─────────────────────────────────────────────────────┐
│                    JVM 記憶體                        │
│                                                     │
│  ┌──────────────────┐   ┌───────────────────────┐  │
│  │   Stack（堆疊）   │   │     Heap（堆積）       │  │
│  │                  │   │                       │  │
│  │ • 方法呼叫記錄    │   │ • 所有 new 出來的物件  │  │
│  │ • 區域變數        │   │ • String 物件          │  │
│  │ • 原始型別的值    │   │ • 陣列                 │  │
│  │                  │   │                       │  │
│  │ 快速、自動管理    │   │ 較慢、GC 管理          │  │
│  └──────────────────┘   └───────────────────────┘  │
└─────────────────────────────────────────────────────┘
```

### Stack（堆疊）的特性
- **後進先出（LIFO）**，像一疊盤子
- 每呼叫一個方法，就在最上面放一層（稱為 Stack Frame）
- 方法結束後，那層自動消失，裡面的區域變數跟著消失
- **原始型別**（`int`, `double`, `boolean` 等）的值直接存在 Stack 裡

### Heap（堆積）的特性
- 所有 `new` 出來的物件都放在這裡
- 不會自動消失，需要 GC（垃圾回收器）來清理
- Stack 裡的變數只是**記住物件在 Heap 的位址**（參考/Reference）

---

## 三、原始型別 vs 參考型別

### 原始型別（Primitive Type）
`byte`, `short`, `int`, `long`, `float`, `double`, `char`, `boolean`

```java
int a = 10;
int b = a;   // 直接複製值
b = 99;
System.out.println(a); // 10，a 不受影響
```

```
Stack
┌──────┐
│ a=10 │  ← a 自己存著數值 10
│ b=99 │  ← b 是 a 的複製，改 b 不影響 a
└──────┘
```

### 參考型別（Reference Type）
所有 `class`、`String`、陣列 都是參考型別

```java
Bike b1 = new Bike();  // new 在 Heap 建立物件，b1 存的是「位址」
Bike b2 = b1;          // b2 複製的是「位址」，不是物件本身！
b2.upSpeed();          // 透過位址找到物件改速度
System.out.println(b1.getSpeed()); // b1 看到速度也變了！
```

```
Stack                    Heap
┌──────────┐            ┌──────────────────┐
│ b1 = @A1 │─────────▶ │ Bike 物件 @A1    │
│ b2 = @A1 │─────────▶ │   speed = 1.0    │
└──────────┘            └──────────────────┘
b1 和 b2 指向同一個物件！
```

---

## 四、Java 的參數傳遞：永遠是「傳值」

這是 Java 最常被誤解的觀念。**Java 只有傳值（Pass by Value），沒有傳參考。**

關鍵在於：傳進方法的是「值的複製」。
- 傳原始型別 → 複製數值
- 傳物件 → 複製**位址**（所以可以改物件內容，但不能換掉物件本身）

```java
// 案例一：傳原始型別 — 改不了外面的值
void addTen(int x) {
    x = x + 10;  // 只改了 x 的副本
}

int num = 5;
addTen(num);
System.out.println(num); // 5，沒有改變

// 案例二：傳物件 — 可以改物件的內容
void speedUp(Bike b) {
    b.upSpeed();  // 透過位址找到物件，改它的內容
}

Bike myBike = new Bike();
speedUp(myBike);
System.out.println(myBike.getSpeed()); // 速度改變了！

// 案例三：傳物件 — 無法換掉物件本身
void replace(Bike b) {
    b = new Bike();  // 只改了 b 這個「位址副本」，外面的 myBike 不受影響
}

replace(myBike);
// myBike 還是原來那個物件
```

```
案例二的記憶體示意圖：

呼叫前：  Stack              Heap
         myBike=@A1 ────▶  [Bike @A1, speed=0]

方法內：  Stack              Heap
         myBike=@A1 ────▶  [Bike @A1, speed=0]
         b=@A1      ────▶  （同一個物件）

b.upSpeed() 後：
         [Bike @A1, speed=1.0]  ← 物件內容被改了

方法結束後，b 消失，但物件還在 Heap，myBike 看得到改變
```

---

## 五、String 的記憶體特殊性

### 字串池（String Pool）

```java
String s1 = "Brad";    // 從字串池取（或新增到池中）
String s2 = "Brad";    // 池中已有，直接拿同一個
String s3 = new String("Brad");  // 強制在 Heap 建立新物件

System.out.println(s1 == s2);  // true（同一個池中的物件）
System.out.println(s1 == s3);  // false（s3 是 Heap 中的新物件）
```

```
Heap
┌──────────────────────────────────────┐
│  String Pool（字串池）               │
│  ┌──────────────┐                   │
│  │ "Brad" @Pool1 │ ◀── s1, s2 都指這裡 │
│  └──────────────┘                   │
│                                      │
│  一般 Heap 區域                       │
│  ┌──────────────┐                   │
│  │ "Brad" @Heap2 │ ◀── s3 指這裡      │
│  └──────────────┘                   │
└──────────────────────────────────────┘
```

### String 的不可變性（Immutability）

```java
String s = "Hello";
s = s + " World";  // 這行不是修改原字串！
```

實際發生的事：
```
1. 在 Heap 建立新的 "Hello World" 物件
2. s 的位址改成指向新物件
3. 舊的 "Hello" 物件變成垃圾，等待 GC 回收
```

**大量字串拼接要用 StringBuilder：**
```java
// 慢：每次 + 都建一個新物件
String result = "";
for (int i = 0; i < 10000; i++) {
    result = result + i;  // 建了 10000 個暫時物件！
}

// 快：StringBuilder 在原地修改
StringBuilder sb = new StringBuilder();
for (int i = 0; i < 10000; i++) {
    sb.append(i);  // 不建新物件
}
String result = sb.toString();
```

---

## 六、垃圾回收（Garbage Collection，GC）

Java 不需要手動 `free()` 記憶體，因為 JVM 有垃圾回收器。

### GC 的判斷標準：沒有任何變數指向的物件就是垃圾

```java
Bike b1 = new Bike();  // 建立物件 @A1，b1 指著它
Bike b2 = new Bike();  // 建立物件 @B2，b2 指著它
b1 = b2;               // b1 改指向 @B2，@A1 沒人指了！
                        // @A1 變成垃圾，等待 GC 回收
```

```
操作前：  b1 ─▶ [Bike @A1]    b2 ─▶ [Bike @B2]
操作後：  b1 ─▶ [Bike @B2] ◀─ b2    [Bike @A1] ← 垃圾！
```

### GC 什麼時候執行？

- JVM 自動決定（通常是 Heap 快滿時）
- 你無法精確控制，但可以建議：`System.gc()`（JVM 不一定理你）
- **這就是為什麼 Java 程式有時會突然停頓一下**（GC 在清理）

---

## 七、Stack Overflow（堆疊溢位）

```java
// 這段程式碼會讓 Stack 爆掉
void infinite() {
    infinite();  // 不斷呼叫自己，Stack 一直疊加
}
```

每次呼叫方法都在 Stack 加一層，無限遞迴會讓 Stack 超出限制，拋出 `StackOverflowError`。

---

## 八、Metaspace 與類別載入機制

### Metaspace：取代了 PermGen

Java 8 之前有一塊叫 **PermGen（永久代）** 的記憶體，用來存放類別的 Metadata（方法名稱、字節碼等）。它有固定大小，類別載入太多就會 `OutOfMemoryError: PermGen space`。

Java 8 把 PermGen 徹底廢除，改為 **Metaspace**：

```
Java 7 以前：
  Heap = [Young Gen] + [Old Gen] + [PermGen（固定大小，預設64MB）]

Java 8 以後：
  Heap = [Young Gen] + [Old Gen]
  + Metaspace（在 Native Memory，不受 Heap 限制，預設隨需成長）
```

| 比較 | PermGen | Metaspace |
|------|---------|-----------|
| 位置 | JVM Heap | Native Memory（作業系統記憶體）|
| 大小 | 固定（預設 64MB） | 動態成長（可設上限）|
| OOM 原因 | 類別太多或 String intern 太多 | 類別洩漏（ClassLoader 沒被回收）|
| JVM 參數 | `-XX:MaxPermSize=256m` | `-XX:MaxMetaspaceSize=256m` |

```java
// 什麼情況會讓 Metaspace 快速增長？
// 動態產生大量類別 —— 如 Spring 的 CGLIB Proxy、反射代理、JSP 編譯
// 每個 Proxy 類別都會佔用 Metaspace
```

### ClassLoader：類別如何被載入到 JVM？

JVM 用 **雙親委派模型（Parent Delegation Model）** 載入類別：

```
Bootstrap ClassLoader（核心，C++ 寫的）
    載入 rt.jar / java.lang.* / java.util.* 等 JDK 核心類別
    ↑（委派）
Extension ClassLoader（擴充）
    載入 jre/lib/ext/*.jar
    ↑（委派）
Application ClassLoader（應用程式）
    載入你寫的程式碼和 Maven 依賴
    ↑（委派）
自訂 ClassLoader（如 Tomcat、Spring Boot）
    載入 WAR 裡的 WEB-INF/classes
```

**雙親委派的運作方式：**

```java
// 當你呼叫 Class.forName("com.example.Order")
// 1. Application ClassLoader 先問：Bootstrap，你認識這個類別嗎？
// 2. Bootstrap 往上（自己是最頂層）→ 自己找，找不到 → 回報「不認識」
// 3. Application ClassLoader 自己才去 classpath 找

// 核心原因：防止你寫一個自己的 java.lang.String 來替換 JDK 的
// 因為 bootstrap 永遠先被問，所以你的假 String 永遠載入不進去
```

**Spring Boot 的 fat JAR 為何需要自訂 ClassLoader？**

```
spring-boot-app.jar
  └── BOOT-INF/lib/*.jar     ← 全部依賴打包在裡面
  └── BOOT-INF/classes/      ← 你的程式碼

問題：標準 ClassLoader 不知道怎麼從 JAR 裡的 JAR 載入類別
解法：Spring Boot 實作了 LaunchedURLClassLoader，
     能夠遞迴讀取嵌套的 JAR 結構
```

---

## 本章重點整理

| 概念 | 說明 |
|------|------|
| Stack | 存方法呼叫和區域變數，自動管理，快 |
| Heap | 存所有物件，GC 管理，較慢 |
| 原始型別 | 值直接在 Stack，複製是複製值 |
| 參考型別 | Stack 存位址，Heap 存物件 |
| 傳值 | Java 永遠傳「值的副本」（位址也是一種值）|
| 字串池 | `"字面值"` 放池中，`new String()` 放 Heap |
| GC | 自動回收沒人指的物件 |

---

## 底層延伸問題

**Q1：以下程式碼執行完，Heap 上有幾個 Bike 物件存在？**
```java
Bike a = new Bike();
Bike b = new Bike();
Bike c = a;
a = new Bike();
b = c;
```
<details>
<summary>答案</summary>
有 2 個存活的物件（a 指的新 Bike、c 和 b 共同指的原始 a 的 Bike）。
第二個 new Bike()（原本 b 指的）沒有任何變數指向，已成為 GC 垃圾。
</details>

**Q2：為什麼下面這段程式碼的 swap 沒有作用？**
```java
void swap(String a, String b) {
    String temp = a;
    a = b;
    b = temp;
}

String x = "Hello";
String y = "World";
swap(x, y);
System.out.println(x); // 還是 "Hello"
```
<details>
<summary>答案</summary>
因為 Java 傳的是「位址的副本」。方法內的 a、b 是 x、y 位址的複製，改變 a、b 指向不影響外面的 x、y。
</details>

**Q3：以下兩段程式碼哪個更省記憶體？為什麼？**
```java
// 版本 A
for (int i = 0; i < 5; i++) {
    String s = "Hello";
}

// 版本 B
for (int i = 0; i < 5; i++) {
    String s = new String("Hello");
}
```
<details>
<summary>答案</summary>
版本 A 更省。因為 "Hello" 是字面值，5 次迴圈都從字串池取同一個物件。
版本 B 每次都在 Heap 建立新的 String 物件，產生 5 個物件。
</details>
