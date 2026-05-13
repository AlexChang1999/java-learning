# 第九章：C++ 與 Java 的核心差異
> 理解為什麼撮合引擎偏好 C++，以及如何在 Java 中盡量縮小差距

---

## 前言：為什麼要學 C++ 視角？

即使你以後用 Java 寫撮合引擎，理解 C++ 的設計哲學也能讓你：
1. 寫出更接近底層、效能更好的 Java 程式
2. 看懂 C++ 寫的高頻交易（HFT）開源程式碼
3. 理解為什麼某些 Java 的設計決策（GC、JIT）是優點也是缺點

---

## 一、最根本的差異：誰管記憶體？

```
Java：  你分配記憶體（new），GC 自動回收
C++：   你分配記憶體（new），你手動回收（delete）
```

這個差異引發了所有其他差異。

### Java GC 的隱藏成本

```java
// Java：這行看似無害
Bike b = new Bike();

// 背後：
// 1. JVM 在 Heap 找一塊空間
// 2. 初始化物件
// 3. GC 某個時刻會停下來掃描（Stop-the-World）
//    → 可能造成毫秒級的停頓
//    → 對撮合引擎來說，這是災難
```

**Stop-the-World（STW）停頓：**
- 某些 GC 演算法需要暫停所有執行緒來清理記憶體
- 停頓可能從 1ms 到幾百 ms
- 在這段時間內，你的撮合引擎完全無法處理訂單
- 對手（HFT 機構）可能在這段時間完成數百筆交易

### C++ 的解法

```cpp
// C++：完全掌控記憶體，GC 停頓 = 0
class Bike {
    double speed;
public:
    Bike() : speed(0.0) {}
    void upSpeed() { speed = speed < 1 ? 1 : speed * 1.4; }
};

// 在 Stack 上分配（自動釋放，零開銷）
Bike bike;  // 方法結束就消失

// 在 Heap 上分配（手動管理）
Bike* pBike = new Bike();
// ... 使用 ...
delete pBike;  // 你必須手動刪除，否則記憶體洩漏！

// 現代 C++：智慧指標（自動管理 Heap 物件）
#include <memory>
std::unique_ptr<Bike> smartBike = std::make_unique<Bike>();
// 超出作用域自動 delete，但沒有 GC 停頓
```

---

## 二、記憶體佈局：物件如何存放在記憶體中

### Java：所有物件都在 Heap，透過參考存取

```
Java 的 Bike 陣列：

Stack              Heap
bikes[0] → @A1    [Bike @A1: speed=1.0]
bikes[1] → @B2    [Bike @B2: speed=2.0]  ← 記憶體位址可能很分散
bikes[2] → @C3    [Bike @C3: speed=3.0]

CPU 存取 bikes[0], bikes[1], bikes[2]：
→ 三次指標追蹤（pointer chasing）
→ 三次可能的 cache miss
```

### C++：物件可以直接放在陣列裡（連續記憶體）

```cpp
// C++：物件直接嵌在陣列裡，記憶體連續
Bike bikes[3];  // [Bike1][Bike2][Bike3] 連續排列

// 或用 vector
std::vector<Bike> bikes(3);
// [speed=0][speed=0][speed=0] ← 直接排在一起！
```

```
C++ 的 Bike 陣列：

記憶體位址：1000   1008   1016
            [Bike1][Bike2][Bike3]  ← 連續！

CPU 存取 bikes[0]：載入到 cache line（通常 64 bytes）
→ bikes[1], bikes[2] 也順帶進入 cache
→ 後續存取幾乎是「免費的」（cache hit）
```

### 為什麼這很重要：CPU Cache

```
CPU 存取速度對比：
暫存器 (Register) →  1 個 cycle  ≈  0.3 ns
L1 Cache          →  4 個 cycle  ≈  1 ns
L2 Cache          → 12 個 cycle  ≈  4 ns
L3 Cache          → 40 個 cycle  ≈ 14 ns
主記憶體 (RAM)    → 200+ cycles  ≈ 70 ns
                               ↑
                    比 L1 慢 70 倍！
```

C++ 的連續記憶體佈局讓 CPU cache 命中率高，Java 的指標追蹤容易造成 cache miss，這是效能差異的根本原因之一。

---

## 三、C++ 的核心語言特性（對比 Java）

### 指標（Pointer）vs 參考（Reference）

```cpp
// C++：指標 = 存放記憶體位址的變數
int x = 10;
int* ptr = &x;     // ptr 存著 x 的記憶體位址（例如 0x7fff...）
*ptr = 20;         // 透過指標修改 x 的值（解參考）
std::cout << x;    // 印出 20

// 指標可以重新指向其他變數
int y = 30;
ptr = &y;          // ptr 現在指向 y

// C++：參考 = 別名（不可重新綁定）
int& ref = x;      // ref 是 x 的另一個名字
ref = 50;          // x 變成 50
```

```java
// Java：沒有指標，只有參考（但你無法直接操作記憶體位址）
Bike b1 = new Bike();  // b1 是一個「參考」，但你看不到實際位址
// 在 Java 你不能做 &b1 或 *b1
```

### 模板（Template）vs 泛型（Generics）

```cpp
// C++：Template（編譯時展開，零額外開銷）
template<typename T>
T max(T a, T b) { return a > b ? a : b; }

// 呼叫時，編譯器生成「真正的 int 版本」和「真正的 double 版本」
int r1 = max<int>(3, 5);       // 產生 int 版 max
double r2 = max<double>(3.14, 2.71);  // 產生 double 版 max
// 完全沒有型別擦除，效能與手寫一樣
```

```java
// Java：Generics（型別擦除，執行時只有 Object）
<T> T max(T a, T b) { ... }

// 編譯後等同：
Object max(Object a, Object b) { ... }
// 呼叫時需要裝箱（int → Integer）和型別轉換，有開銷
```

### RAII（資源獲取即初始化）

C++ 最重要的設計模式，Java 的 try-with-resources 就是在模仿它：

```cpp
// C++：當 Lock 物件超出作用域，解構子自動呼叫，釋放鎖
{
    std::lock_guard<std::mutex> lock(myMutex);  // 進入時加鎖
    // ... 安全地操作共享資料 ...
}  // ← 離開 {} 時，lock 解構子自動呼叫，解鎖
   //   即使中途拋出例外也保證解鎖！
```

```java
// Java 的對應：try-with-resources
try (var lock = myLock.lock()) {  // 進入時加鎖
    // ... 操作 ...
}  // ← 自動呼叫 close()，解鎖
```

---

## 四、效能關鍵差異總整理

| 面向 | Java | C++ | 影響 |
|------|------|-----|------|
| 記憶體管理 | GC 自動回收，有 STW 停頓 | 手動/智慧指標，零停頓 | 延遲可預測性 |
| 物件佈局 | Heap + 指標，記憶體分散 | 可連續排列，cache 友好 | 吞吐量 |
| 型別系統 | 泛型型別擦除 | 模板在編譯期展開 | CPU 指令效率 |
| 呼叫開銷 | 虛擬方法有間接開銷 | 可以完全 inline | 函式呼叫延遲 |
| 啟動時間 | JIT 暖機需要時間 | 直接機器碼，立即最快 | 冷啟動效能 |
| 安全性 | 自動邊界檢查 | 不檢查（可關閉）| 微小開銷 |
| 開發速度 | 快（GC、豐富標準庫）| 慢（手動管理、複雜語法）| 開發成本 |
| 崩潰風險 | 較低 | 較高（記憶體錯誤）| 穩定性 |

---

## 五、Java 如何縮小與 C++ 的差距

即使用 Java，也有很多技巧可以提升效能：

### 技巧一：物件池（Object Pool）—— 避免 GC 壓力

```java
// 壞習慣：頻繁 new 物件
for (Order order : incomingOrders) {
    Trade trade = new Trade(...);  // 每次都在 Heap 分配，GC 壓力大
    process(trade);
}

// 好習慣：預先分配，重複使用
public class TradePool {
    private final Queue<Trade> pool = new ArrayDeque<>();
    
    public Trade acquire() {
        Trade t = pool.poll();
        return t != null ? t : new Trade();  // 池裡有就拿，沒有才 new
    }
    
    public void release(Trade t) {
        t.reset();      // 清空狀態
        pool.offer(t);  // 還回池中
    }
}
```

### 技巧二：使用原始型別陣列代替物件陣列（減少指標追蹤）

```java
// 壞：Double 是物件，有指標追蹤
Double[] prices = new Double[1000000];

// 好：double 是原始型別，記憶體連續
double[] prices = new double[1000000];
// 存取 prices[i] 不需要追蹤指標！
```

### 技巧三：避免自動裝箱（Auto-boxing）

```java
// 壞：List<Integer> 內部存的是 Integer 物件（Heap 上的參考）
List<Integer> prices = new ArrayList<>();
prices.add(100);  // int 裝箱成 Integer 物件！

// 好：使用原始型別集合（Eclipse Collections 或 Trove 函式庫）
// 或者就用原始型別陣列
int[] prices = new int[1000];

// 或使用 Java 的 IntStream
IntStream.range(0, 1000).map(i -> i * 2).sum();
```

### 技巧四：LMAX Disruptor —— 無鎖環形緩衝區

這是 Java 高頻交易領域最重要的資料結構，由 LMAX（英國外匯交易所）開源：

```java
// 概念：Ring Buffer（環形緩衝區）
// 預先分配固定大小的陣列，生產者/消費者用序號協調，不需要鎖

// 傳統做法：BlockingQueue（有鎖，有停頓）
BlockingQueue<Order> queue = new LinkedBlockingQueue<>();
// → 生產者/消費者都要競爭鎖，CAS 操作

// Disruptor 做法：
// → 環形緩衝區是固定大小的陣列（記憶體連續）
// → 序號（long）是唯一的共享狀態
// → 用 CPU 的 CAS 指令替代鎖，延遲更低更可預測
```

```
Ring Buffer 示意圖（大小 = 8）：

   [0][1][2][3][4][5][6][7]
            ↑           ↑
         消費者         生產者
        讀到這裡        寫到這裡

不需要移動元素，只需移動指針
生產者序號 = 14，對應位置 = 14 % 8 = 6
```

---

## 六、一個撮合引擎的語言選擇現實

| 場景 | 推薦語言 | 理由 |
|------|---------|------|
| 超高頻交易（HFT，< 1μs）| C++ | 完全掌控記憶體和 CPU |
| 交易所撮合引擎（1~100μs）| C++ 或 Java（調優後）| C++ 更穩，Java 開發快 |
| 中低頻策略（> 1ms）| Java / Python / Go | 開發效率重要性超過微秒 |
| 風控系統 / 後台 | Java / Python | 複雜業務邏輯，開發速度優先 |

**知名撮合引擎的語言：**
- NYSE（紐約證交所）：C++
- LMAX Exchange：Java（大量調優）
- Binance 部分系統：Java
- 很多加密貨幣交易所：Go / Rust（新興選擇）

---

## 七、如果你要用 Java 寫撮合引擎，這樣配置 JVM

```bash
# 減少 GC 停頓的 JVM 參數
java \
  -server \
  -Xms4g -Xmx4g \          # 固定堆積大小，避免動態擴展
  -XX:+UseZGC \             # 使用 ZGC（Java 15+，低停頓 GC）
  # 或 -XX:+UseShenandoahGC  # 另一個低停頓選項
  -XX:+AlwaysPreTouch \     # 啟動時就預先分配記憶體，避免執行中分配
  -XX:+DisableExplicitGC \  # 忽略 System.gc() 呼叫
  -XX:+PerfDisableSharedMem \ # 停用 JVM 效能監控共享記憶體
  -cp . MatchingEngine
```

---

## 本章重點整理

| 概念 | Java | C++ |
|------|------|-----|
| 記憶體 | GC 自動，有停頓風險 | 手動，零停頓但需謹慎 |
| 物件存放 | Heap，指標分散 | 可連續，cache 友好 |
| 泛型 | 型別擦除，執行時 Object | Template，編譯期展開 |
| 延遲 | 微秒~毫秒（依 GC 配置）| 奈秒級（調優後）|
| Java 優化方向 | 物件池、原始型別、Disruptor、ZGC | — |

---

## 延伸問題

**Q1：為什麼「固定 JVM heap 大小（-Xms = -Xmx）」能改善撮合引擎的延遲？**
<details>
<summary>答案</summary>
JVM 在 heap 不夠時會向 OS 申請更多記憶體，這個系統呼叫會造成不可預期的停頓。固定大小讓 JVM 啟動時就分配好全部記憶體，執行期間不再動態申請，消除這個不確定性。
</details>

**Q2：Java 的 ArrayList&lt;Integer&gt; 和 int[] 在記憶體佈局上有何不同？哪個更適合高頻交易的價格列表？**
<details>
<summary>答案</summary>
ArrayList&lt;Integer&gt; 內部存的是 Integer 物件的參考（指標），每個物件在 Heap 上獨立存放，存取時需要指標追蹤，容易 cache miss。int[] 是連續記憶體，存取下一個元素幾乎必然已在 CPU cache 中。高頻交易應優先用 int[] 或 double[]。
</details>

**Q3：CAS（Compare-And-Swap）是什麼？為什麼 Disruptor 使用它而不是 synchronized？**
<details>
<summary>答案</summary>
CAS 是 CPU 級別的原子操作：「如果記憶體位置的值等於期望值，就更新成新值，否則失敗」。這是一條硬體指令，比 synchronized（軟體鎖，需要 OS 介入）快很多。synchronized 在競爭激烈時會讓執行緒進入 blocked 狀態，造成 context switch；CAS 則是 spin（自旋等待），不切換執行緒，延遲更低更穩定。
</details>
