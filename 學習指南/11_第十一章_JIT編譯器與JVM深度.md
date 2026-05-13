# 第十一章：JIT 編譯器與 JVM 深度剖析
> 理解 Java 為什麼能接近 C++ 速度，以及「暖機」現象的根本原因

---

## 一、Java 程式碼怎麼真正跑起來的？

```
你寫的 .java
      ↓  javac 編譯
  .class（Bytecode）
      ↓  JVM 執行
      ↓
  ┌─ 解譯器（Interpreter）─── 逐行解讀，馬上執行，慢
  │
  └─ JIT 編譯器（Just-In-Time Compiler）
        ↓  偵測熱點程式碼（Hot Code）
     機器碼（Machine Code）── 直接在 CPU 跑，快
```

**關鍵概念：** Java 不是「解譯執行」也不是「預先編譯」，而是**邊跑邊編譯**。

---

## 二、Bytecode：JVM 看得懂的中間語言

### 看看 HelloWorld 的 Bytecode

```java
// 原始碼
public class HelloWorld {
    public static void main(String[] args) {
        int a = 10;
        int b = 20;
        int c = a + b;
        System.out.println(c);
    }
}
```

```bash
# 用工具反編譯查看 bytecode
javap -c HelloWorld.class
```

```
public static void main(java.lang.String[]);
  Code:
   0: bipush        10    ← 把整數 10 推入操作數棧
   2: istore_1           ← 從棧頂彈出，存入區域變數 1（a）
   3: bipush        20    ← 把整數 20 推入棧
   5: istore_2           ← 存入區域變數 2（b）
   6: iload_1            ← 載入變數 1（a=10）推入棧
   7: iload_2            ← 載入變數 2（b=20）推入棧
   8: iadd               ← 從棧取兩個值相加，結果推回棧（= 30）
   9: istore_3           ← 存入變數 3（c）
  10: getstatic     #7   ← 取 System.out
  13: iload_3            ← 載入 c（30）
  14: invokevirtual #13  ← 呼叫 println
  17: return
```

**JVM 是一個「堆疊式虛擬機器」（Stack-based VM）：**
- 所有計算都透過「操作數棧（Operand Stack）」進行
- 指令從棧頂取運算元，結果推回棧頂
- 比直接用 CPU 暫存器多了一層間接，這就是解譯時比機器碼慢的原因

---

## 三、JIT 編譯：偵測熱點，就地優化

### 分層編譯（Tiered Compilation，Java 8+）

```
程式碼第一次執行：解譯器（慢，但立刻啟動）
        ↓ 執行超過 2,000 次
    C1 編譯器（Client Compiler）
    → 快速編譯成機器碼，有基本優化
        ↓ 執行超過 15,000 次
    C2 編譯器（Server Compiler）
    → 深度優化，生成最快的機器碼
```

```
執行次數    JVM 行為          速度
────────────────────────────────────
1~100      解譯執行          慢
100~2000   收集 Profile 資訊  慢
2000~      C1 編譯           快
15000~     C2 優化編譯        最快
```

**這就是「暖機（Warmup）」現象的原因：**  
Java 程式剛啟動時很慢，跑一段時間後才變快。  
對撮合引擎來說，開市前需要先讓系統跑暖才能承受正式流量。

---

## 四、JIT 的五大優化技術

### 1. 方法內聯（Method Inlining）

```java
// 原始碼：呼叫一個小方法
int square(int x) { return x * x; }

for (int i = 0; i < 1000000; i++) {
    int result = square(i);  // 每次呼叫都有函式呼叫開銷
}
```

```
JIT 優化後（概念上等同於）：
for (int i = 0; i < 1000000; i++) {
    int result = i * i;  // 直接展開！消除了函式呼叫
}
```

**函式呼叫的開銷：**
- 建立新的 Stack Frame
- 傳遞參數
- 執行 return，恢復 Stack Frame

內聯消除了這些開銷，同時讓後續優化成為可能。

### 2. 逃逸分析（Escape Analysis）

```java
// JIT 分析：result 物件有沒有「逃逸」出這個方法？
int compute() {
    Point p = new Point(3, 4);  // p 有沒有被外部拿走？
    return p.x + p.y;           // 沒有！p 只在這個方法內用
}
```

```
沒有逃逸 → JIT 可以把物件分配在 Stack 而不是 Heap！
→ 方法結束後自動釋放，不需要 GC 介入
→ 這是 Java 能避免某些 GC 壓力的重要技術
```

### 3. 迴圈展開（Loop Unrolling）

```java
// 原始碼
for (int i = 0; i < 8; i++) {
    sum += arr[i];
}
```

```
JIT 展開後（減少迴圈控制的開銷）：
sum += arr[0];
sum += arr[1];
sum += arr[2];
sum += arr[3];
sum += arr[4];
sum += arr[5];
sum += arr[6];
sum += arr[7];
// 消除了 8 次條件判斷和跳轉指令
```

### 4. 分支預測輔助（Branch Profiling）

```java
// JIT 在執行時記錄這個 if 通常走哪條路
if (order.getSide() == BUY) {  // 假設 99% 的時候是 true
    // JIT 把這個分支編譯成「快速路徑」
    processBuy(order);
} else {
    processSell(order);  // 這條路被放到「慢速路徑」
}
```

這對應到 CPU 的**分支預測（Branch Prediction）**——下一章會詳細說明。

### 5. 向量化（Vectorization / SIMD）

```java
// 原始碼：逐個元素處理
for (int i = 0; i < prices.length; i++) {
    prices[i] *= 1.1;
}
```

```
JIT 優化後（使用 SIMD 指令，一次處理多個元素）：
AVX2 指令集可以一次處理 4 個 double：
[p0, p1, p2, p3] *= [1.1, 1.1, 1.1, 1.1]  ← 一條指令！

等效於原本 4 次乘法，速度提升 4 倍
```

---

## 五、如何觀察 JIT 在做什麼

```bash
# 印出 JIT 編譯的方法（哪些方法被編譯了）
java -XX:+PrintCompilation MyApp

# 輸出範例：
# 時間戳  編譯ID  標記  層級  類別::方法  大小
#  1234    42    %    4    tw/brad/tutor/OrderBook::matchBuyOrder  156 bytes
#  ↑                  ↑
#  毫秒             第4層 = C2 最高優化

# 印出 JIT 生成的機器碼（需要 hsdis 函式庫）
java -XX:+PrintAssembly -XX:+UnlockDiagnosticVMOptions MyApp
```

```bash
# 常用 JVM 調優標誌
-XX:+TieredCompilation          # 開啟分層編譯（預設開啟）
-XX:CompileThreshold=1000       # 方法執行幾次後觸發 C1 編譯
-XX:+OptimizeStringConcat       # 自動優化字串拼接
-XX:+DoEscapeAnalysis           # 開啟逃逸分析（預設開啟）
-server                         # 使用 Server JIT（更激進的優化）
```

---

## 六、JVM 的記憶體區域完整版

```
┌─────────────────────────────────────────────────────────┐
│                       JVM 記憶體                         │
│                                                         │
│  ┌──────────────────────────────────────────────────┐  │
│  │                    Heap（堆積）                   │  │
│  │  ┌──────────────┐  ┌─────────────────────────┐  │  │
│  │  │  Young Gen   │  │       Old Gen            │  │  │
│  │  │  ┌──┐ ┌──┐  │  │  （存活久的物件）         │  │  │
│  │  │  │E1│ │S0│  │  │                         │  │  │
│  │  │  │E2│ │S1│  │  │                         │  │  │
│  │  │  └──┘ └──┘  │  └─────────────────────────┘  │  │
│  │  │  Eden  Surv │  ←── Minor GC vs Major GC      │  │
│  │  └──────────────┘                               │  │
│  └──────────────────────────────────────────────────┘  │
│                                                         │
│  ┌──────────────────┐  ┌──────────────────────────┐    │
│  │  Method Area     │  │   Stack（每個執行緒一個） │    │
│  │  （Class 資訊、  │  │  Frame → Frame → Frame   │    │
│  │   static 欄位、  │  └──────────────────────────┘    │
│  │   常數池）       │                                   │
│  └──────────────────┘  ┌──────────────────────────┐    │
│                         │  PC Register（程式計數器）│    │
│                         └──────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

**GC 世代假說：** 大多數物件「朝生暮死」（短暫建立就被丟棄），少數物件存活很久。  
→ Young Gen 用快速的 Minor GC（只掃新生代）  
→ Old Gen 用較慢的 Major GC（Full GC，掃整個 Heap）

---

## 七、逃逸分析的實際影響：對撮合引擎意味著什麼？

### 逃逸分析的三種結果

```
JIT 看完一個物件的生命週期後，有三種判斷：

1. 不逃逸（No Escape）
   → 物件只在方法內使用，不傳給任何外部
   → JIT 可以：把物件欄位拆散放進 CPU 暫存器（Scalar Replacement）
   → 甚至完全不分配記憶體！
   → GC 壓力：零

2. 僅在方法的執行緒內使用（Thread-Local Escape）
   → 不需要同步機制（lock 消除）
   → 可以分配在 Stack
   → GC 壓力：低

3. 全域逃逸（Global Escape）
   → 物件被傳出去、被 static 欄位持有、或跨執行緒共享
   → 必須分配在 Heap
   → GC 壓力：正常
```

### 具體程式碼對比

```java
// ❌ 情境一：物件逃逸 → 必須放 Heap → GC 管理
public class OrderFactory {
    public Order createOrder(String symbol, double price) {
        return new Order(symbol, price);  // 物件被 return 出去 → 逃逸！
        // JIT 無法優化，只能在 Heap 分配，等 GC 回收
    }
}

// ✅ 情境二：物件不逃逸 → JIT 可能完全消除 Heap 分配
public class PriceCalculator {
    public double calcMidPrice(double bid, double ask) {
        // Point 只在這個方法內用，沒有傳出去
        Point midPoint = new Point(bid, ask);  // JIT 看出不逃逸！
        return (midPoint.x + midPoint.y) / 2.0;
        // JIT 優化後等同於：return (bid + ask) / 2.0;
        // Point 物件根本不會被建立，直接用暫存器計算
    }
}
```

### 撮合引擎的逃逸分析應用

```java
// 撮合過程中的中間計算物件——JIT 可以優化掉
public class MatchingEngine {
    public void match(Order buyOrder, Order sellOrder) {
        // TradeResult 只在這個方法內計算，然後立刻記錄
        // 如果 JIT 確認它不逃逸（例如只傳給私有方法），可以 Stack 分配
        TradeResult result = new TradeResult(
            Math.min(buyOrder.qty, sellOrder.qty),
            sellOrder.price  // 以賣方報價成交
        );
        logTrade(result);   // 如果 logTrade 被內聯，result 可能完全消失
    }
}

// 撮合引擎暖機的意義：
// - 第一批訂單（1~2000次）：解譯器跑，逃逸分析還沒開始，全部 Heap 分配
// - 2000~15000次：C1 編譯，基本逃逸分析，部分 Stack 分配
// - 15000次後：C2 深度優化，充分逃逸分析，GC 壓力降到最低
// → 這就是為什麼撮合引擎開市前要「預熱」：先丟假訂單讓 JIT 跑滿 C2
```

### 如何確認逃逸分析是否有效

```bash
# 開啟逃逸分析日誌（需要 debug 版 JVM）
java -XX:+DoEscapeAnalysis \
     -XX:+PrintEscapeAnalysis \
     -XX:+EliminateAllocations \   # 啟用 Scalar Replacement
     -XX:+PrintEliminateAllocations \
     MyApp

# 用 JMH 測試有無逃逸分析的差距
# （第十四章有完整的 JMH 用法）
```

---

## 八、撮合引擎的 JIT 暖機策略

### 為什麼第一批訂單特別慢？

```
時間軸（撮合引擎啟動）：

0ms      ├── JVM 啟動，所有方法由解譯器執行
         │   匹配延遲：~500μs（解譯器）
         │
500ms    ├── 熱點方法開始 C1 編譯（執行次數超過 2,000）
         │   匹配延遲：~50μs（C1 優化）
         │
2000ms   ├── C2 深度優化完成（執行次數超過 15,000）
         │   匹配延遲：~5μs（C2 最優化）
         │
         └── 系統穩定在最高效能

C2 優化後比解譯器快 100 倍！
這就是撮合引擎必須「暖機」的根本原因。
```

### 生產環境的暖機方式

```java
// 方式一：開市前跑假訂單（最常見）
public class MatchingEngineWarmer {

    private final MatchingEngine engine;

    public void warmUp(int iterations) {
        System.out.println("開始 JIT 暖機，跑 " + iterations + " 筆假訂單...");
        long start = System.nanoTime();

        for (int i = 0; i < iterations; i++) {
            // 建立有代表性的假訂單（要覆蓋真實場景的程式碼路徑）
            Order fakeBuy  = Order.limit("WARM", Side.BUY,  350.00, 100);
            Order fakeSell = Order.limit("WARM", Side.SELL, 350.00, 100);

            engine.submit(fakeBuy);
            engine.submit(fakeSell);  // 這次撮合觸發所有關鍵路徑
        }

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        System.out.println("暖機完成，耗時 " + elapsed + "ms，JIT 已達 C2 優化");
        // 通常 15,000 次足夠觸發 C2，約需 1~5 秒
    }
}

// 方式二：使用 -Xcomp（強制預先編譯，但啟動時間很長，不推薦）
// java -Xcomp MyApp

// 方式三：GraalVM AOT（提前編譯成機器碼，連暖機都省了）
// → 適合無狀態微服務，但失去 JIT 的自適應優化能力
```

### 各種 JVM 執行模式的延遲對比

```
（撮合單筆限價單的延遲，僅供參考）

解譯器模式（啟動初期）：   ~500  μs
C1 編譯後：               ~ 50  μs
C2 完整優化後：            ~  5  μs
C++ 撮合引擎（HFT）：      ~  1  μs

硬體參考：
  L1 Cache 讀取：   ~1   ns（比 C2 Java 快 5000 倍）
  RAM 讀取：      ~100   ns
  Context Switch：~5000  ns（~5 μs）

→ C2 優化後的 Java 延遲 (~5μs) 與一次 Context Switch 相當
→ 這就是為什麼撮合引擎要盡量避免鎖競爭（鎖競爭 → Context Switch → +5μs）
```

---

## 本章練習題

**Q1：為什麼 Java 程式剛啟動時效能較低，跑一段時間才穩定？用 JIT 機制解釋。**
<details>
<summary>答案</summary>
程式剛啟動時，所有方法都由解譯器執行（慢）。JIT 在方法執行超過閾值後才開始編譯（C1 約 2000 次，C2 約 15000 次）。在編譯完成前，程式處於解譯或低優化狀態。這就是「暖機」現象。對撮合引擎，通常會在開市前先跑一批假訂單讓 JIT 暖機。
</details>

**Q2：逃逸分析如何幫助減少 GC 壓力？**
<details>
<summary>答案</summary>
如果 JIT 分析出一個物件不會逃逸出它被建立的方法，它可以把這個物件分配在 Stack 上而非 Heap 上。Stack 上的物件隨著方法返回自動釋放，完全不需要 GC 介入。這對於「短暫建立、立刻使用、不傳出去」的物件（如計算中間結果的 DTO）效果最好。
</details>

**Q3：方法內聯對效能的影響是什麼？Java 中什麼情況可能阻止方法被內聯？**
<details>
<summary>答案</summary>
方法內聯消除了函式呼叫的 Stack Frame 建立、參數傳遞和返回開銷，同時讓後續優化（如常量折疊、死碼消除）更有效。阻止內聯的情況：(1) 方法太大（bytecode 超過約 35 bytes）；(2) 虛擬方法（virtual dispatch）且有多個實作，JIT 無法確定呼叫哪個；(3) 遞迴深度過深。
</details>
