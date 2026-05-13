# 第二十九章：Redis 快取

> **前置知識**：第十九章（資料庫與 JDBC）、第二十一章（Spring Boot 基礎）
> **核心專案連結**：本章以撮合引擎的訂單簿快取作為實戰範例

---

## 一、為什麼需要 Redis？從硬體速度說起

### 1.1 硬體速度對比

在學習 Redis 之前，我們先來理解一個根本問題：**資料從哪裡讀，速度差多少？**

```
硬體層級速度對比表
══════════════════════════════════════════════════════════════
  存取位置              延遲         說明
──────────────────────────────────────────────────────────────
  L1 Cache（CPU）       ~1 ns        最快，但容量極小（幾十 KB）
  L2 Cache（CPU）       ~4 ns        稍大，仍在 CPU 內部
  L3 Cache（CPU）       ~10 ns       多核心共用
  RAM（記憶體）         ~100 ns      比 L1 慢 100 倍
  SSD（固態硬碟）       ~100 μs      比 RAM 慢 1,000 倍  ⚠️
  HDD（機械硬碟）       ~10 ms       比 RAM 慢 100,000 倍 ⚠️
  資料庫查詢（含I/O）   ~1~10 ms     需要讀硬碟、執行SQL
  Redis（存在 RAM）     ~0.1 ms      比 DB 快 10~100 倍  ✅
══════════════════════════════════════════════════════════════
```

**關鍵觀念**：每次使用者查詢訂單簿，後端如果每次都打 DB，需要 1~10 ms。如果改用 Redis，只需 0.1 ms。對撮合引擎這種要求超低延遲的系統，這個差距是決定性的。

### 1.2 Redis 在系統架構中的位置

```
使用者請求流程（未加快取）
───────────────────────────────────
Client  →  Spring Boot  →  MySQL
                              ↑
                         disk I/O（慢！）
                         ~1~10 ms

使用者請求流程（加了 Redis 快取）
───────────────────────────────────
Client  →  Spring Boot  →  Redis（快取命中）→ 回傳
                              ↓ 快取未命中
                            MySQL（寫回 Redis）
                         Redis 命中：~0.1 ms ✅
                         Redis 未命中：~1~10 ms（但之後命中）
```

### 1.3 撮合引擎的實際需求

撮合引擎的訂單簿（Order Book）需要頻繁被查詢：
- 每秒可能有數千次「查詢目前最佳買賣價」的請求
- 訂單簿的內容（最新掛單）變動頻率很高，但查詢頻率更高
- **解法**：把訂單簿快照存進 Redis，查詢直接走 Redis，只有新訂單進來才更新

---

## 二、Redis 資料結構（含底層原理）

Redis 不只是一個簡單的「鍵值對」，它提供六種資料結構，每一種都有特定的底層實作。

### 2.1 String（字串）

**使用場景**：存單一值、計數器、快取 JSON 字串

**底層原理**：Redis 使用 **SDS（Simple Dynamic String）** 而不是 C 語言的 `char*`。

為什麼不用 C 的 `char*`？

```
C 語言 char* 的問題：
  "hello\0"  →  strlen() 需要掃描到 \0 才知道長度，O(n)
               如果資料含 \0（二進制），strlen 會提早截斷 ❌

SDS 的解法：
  struct sdshdr {
      int len;     // 直接記錄長度，O(1) 取得 ✅
      int free;    // 預留空間，減少記憶體重分配
      char buf[];  // 實際內容（允許二進制）
  }
```

**常用指令**：

```bash
# 設定值（SET 可同時設定 TTL）
SET user:1001 "Alex"
SET counter 0

# 取得值
GET user:1001         # 回傳 "Alex"

# 原子性加 1（適合計數器，執行緒安全）
INCR counter          # counter 變成 1
INCR counter          # counter 變成 2

# 設定過期時間（秒）
EXPIRE user:1001 3600  # 1 小時後自動刪除

# 查詢剩餘時間
TTL user:1001          # 回傳剩餘秒數，-1 = 永不過期，-2 = 已刪除
```

---

### 2.2 Hash（雜湊）

**使用場景**：存物件的多個欄位，例如使用者資料、商品資訊

**底層原理**：
- **小資料（欄位數 ≤ 128，值長度 ≤ 64 bytes）**：使用 **ziplist**（壓縮列表），連續記憶體，節省空間
- **大資料**：轉成 **hashtable**（和 Java 的 HashMap 相似）

```
Hash 的 ziplist 儲存示意（小資料時）：
  [zlbytes][zltail][zllen][entry1][entry2]...[zlend]
   ↑連續記憶體，快速遍歷，但修改成本高

Hash 的 hashtable 儲存示意（大資料時）：
  bucket[0] → NULL
  bucket[1] → "name":"Alex" → NULL
  bucket[2] → "age":"25" → NULL
  ...
```

**常用指令**：

```bash
# 設定單一欄位
HSET user:1001 name "Alex"
HSET user:1001 age 25

# 取得單一欄位
HGET user:1001 name       # 回傳 "Alex"

# 批次取得多個欄位
HMGET user:1001 name age  # 回傳 ["Alex", "25"]

# 取得所有欄位和值
HGETALL user:1001         # 回傳 {name: Alex, age: 25}
```

---

### 2.3 List（列表）

**使用場景**：消息佇列、最新動態（取最新 N 筆）

**底層原理**：**雙向鏈結串列（Doubly Linked List）**

```
雙向鏈結串列結構：
  NULL ← [A] ⇄ [B] ⇄ [C] ⇄ [D] → NULL
          ↑頭                 ↑尾

  LPUSH：從頭插入，O(1)  ← 非常快
  RPUSH：從尾插入，O(1)  ← 非常快
  LINDEX：按索引取值，O(n) ← 較慢（需從頭走）
```

**常用指令**：

```bash
# 從左邊（頭部）插入
LPUSH messages "msg1" "msg2"

# 從右邊（尾部）彈出（模擬 Queue）
RPOP messages   # 取出 "msg1"

# 取得範圍（0 = 第一個，-1 = 最後一個）
LRANGE messages 0 -1   # 取得全部
LRANGE messages 0 4    # 取最新 5 筆
```

---

### 2.4 Set（集合）

**使用場景**：去重、標籤系統、共同好友計算

**底層原理**：
- **純整數且數量少（≤ 512）**：**intset**（有序整數陣列，二元搜尋）
- **否則**：**hashtable**

**常用指令**：

```bash
# 加入元素（自動去重）
SADD tags "java" "redis" "spring"

# 判斷是否存在
SISMEMBER tags "redis"   # 回傳 1（存在）

# 取得所有元素
SMEMBERS tags

# 兩個 Set 的交集（共同好友）
SINTERSTORE result:friends friends:alex friends:bob
```

---

### 2.5 Sorted Set（有序集合，ZSet）— 撮合引擎核心

這是本章最重要的資料結構，也是撮合引擎訂單簿快取的關鍵。

**使用場景**：排行榜、優先佇列、**訂單簿（Order Book）**

**底層原理：跳表（Skip List）**

跳表解決的問題：一般鏈結串列查找是 O(n)，跳表透過「多層指標」達到 O(log n)。

```
跳表結構 ASCII 圖解（儲存價格：10, 20, 30, 40, 50）：

第 3 層：  HEAD ──────────────────────→ [30] ──────────→ NULL
第 2 層：  HEAD ──────→ [20] ──────────→ [30] → [40] → NULL
第 1 層：  HEAD → [10] → [20] → [30] → [40] → [50] → NULL
                   ↑                    ↑
              每個節點有多層指標      隨機決定此節點出現在幾層

查詢 40 的過程：
  從第 3 層開始 → HEAD → 30（比 40 小，繼續）
  降到第 2 層    → 30 → 40 ✅ 找到！
  只走了 3 步，而不是 4 步（一層的話要走 1→2→3→4）
```

**為什麼 Redis 用跳表而不是紅黑樹？**

| 比較點 | 跳表 | 紅黑樹 |
|--------|------|--------|
| 範圍查詢 | O(log n) + 連續遍歷，直覺 | 中序遍歷，較複雜 |
| 並發讀取 | 多執行緒讀取友好（無需鎖全樹）| 旋轉操作影響並發 |
| 實作複雜度 | 相對簡單 | 旋轉邏輯複雜 |
| 記憶體 | 稍多（多層指標）| 較少 |

**ZSet 指令**：

```bash
# 加入元素（score=價格, member=訂單ID）
ZADD orderbook:BTC:buy 45000.0 "order:001"
ZADD orderbook:BTC:buy 44500.0 "order:002"
ZADD orderbook:BTC:buy 45100.0 "order:003"

# 依分數從低到高取範圍（買單要從高到低）
ZRANGE orderbook:BTC:buy 0 -1 WITHSCORES     # 低→高
ZREVRANGE orderbook:BTC:buy 0 4 WITHSCORES   # 高→低（取最佳前 5 買價）

# 依分數範圍查詢（例如取 44000~46000 之間的訂單）
ZRANGEBYSCORE orderbook:BTC:buy 44000 46000 WITHSCORES

# 查詢某訂單的排名（0-based）
ZRANK orderbook:BTC:buy "order:002"    # 回傳排名

# 刪除已成交訂單
ZREM orderbook:BTC:buy "order:001"
```

**撮合引擎用 ZSet 儲存訂單簿的邏輯**：
- Key：`orderbook:{symbol}:{side}`（例如 `orderbook:BTC:buy`）
- Score：掛單價格（買單取最高價，用 ZREVRANGE；賣單取最低價，用 ZRANGE）
- Member：訂單 ID

---

## 三、常用指令速查表

```
╔══════════════════════════════════════════════════════════════════╗
║  資料類型    指令           說明                                  ║
╠══════════════════════════════════════════════════════════════════╣
║  String    GET key         取得值                                ║
║            SET key val     設定值                                ║
║            INCR key        原子加 1                              ║
║            EXPIRE key sec  設定 TTL（秒）                        ║
║            TTL key         查剩餘時間                            ║
╠══════════════════════════════════════════════════════════════════╣
║  Hash      HGET k field    取單一欄位                            ║
║            HSET k field v  設單一欄位                            ║
║            HMGET k f1 f2   批次取多欄位                          ║
║            HGETALL key     取所有欄位                            ║
╠══════════════════════════════════════════════════════════════════╣
║  ZSet      ZADD k sc mb    加入（score, member）                 ║
║            ZRANGE k 0 -1   依分數低到高取全部                    ║
║            ZREVRANGE k 0 4 依分數高到低取前5                     ║
║            ZRANGEBYSCORE   依分數範圍查詢                        ║
║            ZRANK k mb      查詢排名                              ║
║            ZREM k mb       刪除指定 member                       ║
╠══════════════════════════════════════════════════════════════════╣
║  通用      DEL key         刪除 key                              ║
║            EXISTS key      是否存在（1/0）                       ║
║            KEYS pattern    ⛔ 生產環境禁用！會阻塞 Redis         ║
║            SCAN cursor     ✅ 代替 KEYS，漸進式掃描              ║
║            TYPE key        查詢 key 的資料類型                   ║
╚══════════════════════════════════════════════════════════════════╝
```

> **新手注意**：生產環境千萬不要用 `KEYS *`，因為 Redis 是單執行緒的，`KEYS` 會掃描所有 key，期間阻塞所有其他請求。改用 `SCAN` 做漸進式迭代。

---

## 四、Spring Boot 整合 Redis

### 4.1 加入依賴

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- 如果需要連線池（推薦），加入 lettuce 或 commons-pool2 -->
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
```

### 4.2 application.yml 設定

```yaml
spring:
  data:
    redis:
      host: localhost       # Redis 伺服器位址
      port: 6379            # 預設 Port
      password:             # 有設密碼才填
      database: 0           # 使用第 0 號資料庫（共 16 個，0~15）
      timeout: 2000ms       # 連線逾時
      lettuce:
        pool:
          max-active: 10    # 最大連線數
          max-idle: 5       # 最大閒置連線
          min-idle: 1       # 最小閒置連線
```

### 4.3 RedisTemplate vs StringRedisTemplate

```java
// RedisTemplate：通用版，可存任意 Java 物件（需序列化）
@Autowired
private RedisTemplate<String, Object> redisTemplate;

// StringRedisTemplate：String 專用版，key 和 value 都是字串
@Autowired
private StringRedisTemplate stringRedisTemplate;
```

**建議的設定方式**（解決序列化問題）：

```java
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Key 用字串序列化（可讀性好）
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value 用 JSON 序列化（可讀性好，跨語言相容）
        GenericJackson2JsonRedisSerializer jsonSerializer =
            new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }
}
```

### 4.4 @Cacheable / @CacheEvict 注解快取

Spring 提供注解讓你不用手動操作 Redis，就能自動快取：

```java
// 需要在啟動類別加上 @EnableCaching
@SpringBootApplication
@EnableCaching
public class MatchingEngineApplication { ... }
```

```java
@Service
public class ProductService {

    // 第一次呼叫時從 DB 查詢，並存入 Redis（key = "product::101"）
    // 之後呼叫直接從 Redis 取，不打 DB
    @Cacheable(value = "product", key = "#id")
    public Product getProduct(Long id) {
        // 這段程式碼只有快取未命中時才執行
        return productRepository.findById(id).orElseThrow();
    }

    // 更新資料後，清除對應的快取（避免讀到舊資料）
    @CacheEvict(value = "product", key = "#product.id")
    public void updateProduct(Product product) {
        productRepository.save(product);
    }

    // 清除某個 cacheNames 下的所有快取
    @CacheEvict(value = "product", allEntries = true)
    public void clearAllProductCache() { }
}
```

### 4.5 撮合引擎實戰：快取最新 5 檔訂單簿

```java
@Service
public class OrderBookCacheService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final int TOP_N = 5;             // 快取前 5 檔
    private static final long CACHE_TTL = 5L;       // 快取 5 秒

    /**
     * 將買單訂單簿前 5 檔存入 Redis ZSet
     * Key 格式：orderbook:{symbol}:buy
     * Score = 價格（買單取最高價，用 ZREVRANGE）
     */
    public void cacheBuyOrders(String symbol, List<Order> buyOrders) {
        String key = "orderbook:" + symbol + ":buy";

        // 清除舊快取
        redisTemplate.delete(key);

        // 批次寫入（使用 pipelining 減少網路往返次數）
        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Order order : buyOrders) {
                // ZSet member = 訂單ID，score = 價格
                redisTemplate.opsForZSet().add(
                    key,
                    order.getOrderId().toString(),
                    order.getPrice().doubleValue()
                );
            }
            return null;
        });

        // 設定 TTL，避免快取永久存在
        redisTemplate.expire(key, CACHE_TTL, TimeUnit.SECONDS);
    }

    /**
     * 從 Redis 取得最佳 5 檔買價（從高到低）
     */
    public Set<ZSetOperations.TypedTuple<String>> getTopBuyOrders(String symbol) {
        String key = "orderbook:" + symbol + ":buy";

        // ZREVRANGEBYSCORE：依分數從高到低取前 N 筆（買單取最高價）
        return redisTemplate.opsForZSet()
            .reverseRangeWithScores(key, 0, TOP_N - 1);
    }

    /**
     * 從 Redis 取得最佳 5 檔賣價（從低到高）
     */
    public Set<ZSetOperations.TypedTuple<String>> getTopSellOrders(String symbol) {
        String key = "orderbook:" + symbol + ":sell";

        // ZRANGEBYSCORE：依分數從低到高取前 N 筆（賣單取最低價）
        return redisTemplate.opsForZSet()
            .rangeWithScores(key, 0, TOP_N - 1);
    }

    /**
     * 訂單成交後，從快取中移除該訂單
     */
    public void removeFilledOrder(String symbol, String side, String orderId) {
        String key = "orderbook:" + symbol + ":" + side;
        redisTemplate.opsForZSet().remove(key, orderId);
    }
}
```

---

## 五、TTL 與快取失效

### 5.1 什麼是 TTL（Time To Live）？

TTL 是 key 的「存活時間」。設定後，Redis 會在時間到期時自動刪除該 key。

```bash
SET session:user123 "token_abc" EX 1800  # 設定 1800 秒（30 分鐘）後過期
TTL session:user123   # 查詢剩餘時間（秒）
PERSIST session:user123  # 取消過期設定（讓 key 永久存在）
```

沒有合理設定 TTL 是快取系統最常見的問題，會導致記憶體不斷累積，最終 OOM（Out of Memory）。

### 5.2 三大快取問題

#### 問題一：快取雪崩（Cache Avalanche）

```
情境：大量 key 在同一時間一起過期

正常情況：
  請求 → Redis（命中）→ 快速回傳

雪崩發生：
  ┌─ 00:00 大量 key 同時過期 ──────────────────────────┐
  │  請求1 → Redis（未命中）→ 打 DB → DB 壓力暴增     │
  │  請求2 → Redis（未命中）→ 打 DB ↑                  │
  │  請求3 → Redis（未命中）→ 打 DB ↑↑↑               │
  │  ...（千萬請求同時打 DB → DB 崩潰）               │
  └───────────────────────────────────────────────────┘

解法：
  1. TTL 加上隨機抖動：EXPIRE key (3600 + random(0, 300))
  2. 使用 Redis 叢集，不讓所有快取在同一節點
  3. 服務降級（DB 崩潰時回傳預設值，不拋例外）
```

#### 問題二：快取穿透（Cache Penetration）

```
情境：查詢一個根本不存在的資料（DB 也沒有）

流程：
  請求（查詢 id=-1）
       ↓
  Redis 查詢 → 未命中（key 不存在）
       ↓
  打 DB → 也查無資料
       ↓
  沒有資料可以寫進 Redis（下次還是打 DB）
       ↓
  惡意攻擊可以用大量不存在的 ID 打垮 DB ❌

解法：
  1. 快取空值：即使 DB 查無資料，也在 Redis 存 NULL（TTL 短一點）
     SET user:-1 "NULL" EX 60

  2. 布隆過濾器（Bloom Filter）：
     在 Redis 前面加一層 Bloom Filter
     先判斷 ID 是否「可能存在」，不存在就直接拒絕
     （Bloom Filter 可能有誤判，但不會漏判）
```

#### 問題三：快取擊穿（Cache Breakdown）

```
情境：一個「超熱門 key」剛好在高峰期過期

正常情況（key 存在時）：
  1000 個請求 → Redis（命中）→ 快速回傳 ✅

擊穿發生：
  熱門 key 過期的瞬間
  ↓
  1000 個並發請求 → Redis（未命中）→ 1000 個請求同時打 DB ❌
  （DB 瞬間收到 1000 倍壓力）

解法：
  1. 互斥鎖（Mutex Lock）：
     只讓一個請求去打 DB，其他請求等待
     // 偽代碼
     if (redis.get(key) == null) {
         if (lock.tryLock()) {        // 只有一個執行緒能拿到鎖
             result = db.query();
             redis.set(key, result);
             lock.unlock();
         } else {
             Thread.sleep(50);        // 其他執行緒稍等再查 Redis
             return redis.get(key);
         }
     }

  2. 永不過期（邏輯過期）：
     key 不設 TTL，而是在 value 裡存一個「邏輯過期時間」
     每次取出時判斷是否過期，過期則非同步更新，但仍回傳舊值
```

---

## 六、持久化：RDB vs AOF

Redis 是記憶體資料庫，如果突然斷電，資料就消失了。Redis 提供兩種持久化機制。

### 6.1 RDB（Redis DataBase）— 定時快照

**原理**：每隔一段時間，把記憶體中的資料快照（Snapshot）寫入磁碟，產生一個 `.rdb` 檔案。

```
RDB 備份流程：
  ┌─ Redis 主進程（持續服務請求）─────────────────────┐
  │                                                   │
  │  觸發條件：300秒內有1次寫操作 → 執行 BGSAVE        │
  │       ↓                                           │
  │  fork() 建立子進程 ─────────────────────────────┐ │
  │  （利用 COW 技術，初始不複製記憶體）              │ │
  │                                               子進程│
  │  主進程繼續處理請求                         寫入 .rdb│
  │  如果某頁記憶體被修改 ──→ COW：複製那一頁   掃描全部  │
  │  子進程讀到的還是快照版本                   記憶體    │
  └───────────────────────────────────────────────────┘

COW（Copy-On-Write）= 寫時複製：
  - fork 後，父子進程共享同一份記憶體頁（節省空間）
  - 只有在某頁被修改時，才真正複製那一頁
  - 大部分時候節省了大量記憶體複製成本
```

**優缺點**：

| | RDB |
|-|-----|
| 優點 | 檔案小、恢復速度快、對效能影響小 |
| 缺點 | 可能丟失兩次快照之間的資料（最多丟幾分鐘的資料）|

---

### 6.2 AOF（Append Only File）— 寫操作日誌

**原理**：每次寫操作（SET、ZADD 等）都追加一條命令到 `.aof` 檔案，概念和資料庫的 WAL（Write-Ahead Log）相同。

```
AOF 流程：
  執行 SET user:1 "Alex"
       ↓
  1. 更新記憶體
  2. 將 "SET user:1 Alex" 追加到 AOF 檔案
       ↓
  下次重啟時，重新執行 AOF 裡的所有命令 → 恢復資料
```

**AOF 同步策略**（在 redis.conf 設定）：

```
appendfsync always    # 每次寫操作都同步到磁碟（最安全，最慢）
appendfsync everysec  # 每秒同步一次（預設，平衡安全與效能）
appendfsync no        # 讓作業系統決定（最快，最不安全）
```

---

### 6.3 RDB vs AOF 對比

```
╔══════════════════════════════════════════════════════════════╗
║               RDB              │          AOF                ║
╠══════════════════════════════════════════════════════════════╣
║  資料安全性   低（最多丟幾分鐘） │  高（最多丟 1 秒）          ║
║  檔案大小     小（二進制快照）   │  大（文字命令日誌）          ║
║  恢復速度     快                │  慢（要重新執行所有命令）     ║
║  寫入效能     影響小            │  每秒 fsync 有輕微影響       ║
║  適用場景     可接受少量丟失     │  要求高資料安全性            ║
╚══════════════════════════════════════════════════════════════╝

建議：兩者同時開啟（RDB 用來快速恢復，AOF 用來補全最近資料）
```

---

## 七、Redis 單執行緒為什麼這麼快？

### 7.1 單執行緒的優勢

Redis 主執行緒是**單執行緒**，這聽起來很奇怪——不是多執行緒才比較快嗎？

```
多執行緒的問題：
  Thread-1: SET user:1 "Alex"
  Thread-2: SET user:1 "Bob"   ← 同時寫同一個 key！
  需要鎖（Mutex）保護 → 鎖競爭 → Context Switch → 效能下降

Redis 單執行緒的優勢：
  所有命令按照進來的順序，一個一個執行
  完全不需要鎖 → 沒有鎖競爭 → 沒有 Context Switch
  每個命令執行速度極快（記憶體操作），所以排隊等待成本極低
```

### 7.2 I/O 多路復用（epoll）

單執行緒處理一個請求的時候，其他連線怎麼辦？Redis 用 **epoll**（Linux）解決這個問題：

```
傳統阻塞 I/O（一對一）：
  Thread-1 服務 Client-1（阻塞等待資料）
  Thread-2 服務 Client-2（阻塞等待資料）
  → 1000 個連線需要 1000 個執行緒 ❌

epoll（多路復用）：
  單執行緒  ←─ epoll 監聽 ─→  Client-1（有資料才通知）
                              Client-2（有資料才通知）
                              Client-3（有資料才通知）
                              ...（可同時監聽數萬連線）

  只有「真正有資料要讀/寫」的連線才佔用執行緒時間
  → 1 個執行緒服務數萬個連線 ✅
```

### 7.3 和撮合引擎 LMAX Disruptor 的共同哲學

這個設計和撮合引擎的 LMAX Disruptor 有一樣的核心思想：

```
相同哲學：單執行緒 + 避免鎖競爭

  LMAX Disruptor：
    單一 Event Handler 執行緒處理所有訂單撮合邏輯
    Ring Buffer 作為無鎖佇列（Lock-free）
    → 吞吐量達到數百萬 TPS

  Redis 主執行緒：
    單一執行緒處理所有命令
    epoll 非阻塞 I/O 處理大量連線
    → 每秒可處理 10 萬+ 操作

核心洞見：
  鎖競爭的代價 > 單執行緒排隊等待的代價（當每個任務夠快時）
```

---

## 八、完整架構回顧

```
撮合引擎 + Redis 完整快取架構：

  ┌─────────────────────────────────────────────────────────┐
  │                   Client（下單 / 查詢）                  │
  └─────────────────────────┬───────────────────────────────┘
                            │
  ┌─────────────────────────▼───────────────────────────────┐
  │              Spring Boot API Layer                       │
  │  OrderController / OrderBookController                   │
  └─────────┬────────────────────────────┬──────────────────┘
            │ 寫入新訂單                  │ 查詢訂單簿
            ▼                            ▼
  ┌─────────────────────┐     ┌──────────────────────────────┐
  │  LMAX Disruptor     │     │  Redis（ZSet 訂單簿快取）    │
  │  撮合引擎核心       │────→│  orderbook:BTC:buy           │
  │  單執行緒撮合       │     │  orderbook:BTC:sell          │
  └────────┬────────────┘     │  score = 價格, member = 訂單ID│
           │ 成交後            └──────────────────────────────┘
           ▼                            ↑ 快取未命中才查 DB
  ┌─────────────────────┐     ┌──────────────────────────────┐
  │  MySQL（持久化）    │────→│  OrderBookCacheService       │
  │  trades 表          │     │  （負責更新 Redis 快取）      │
  │  orders 表          │     └──────────────────────────────┘
  └─────────────────────┘
```

---

## 練習題

<details>
<summary>練習一：解釋為什麼 Redis 不用 C 語言的 char* 存字串？SDS 解決了什麼問題？</summary>

**參考答案**：

C 語言的 `char*` 有兩個問題：
1. **取長度是 O(n)**：需要掃描到 `\0` 才知道字串長度，字串越長越慢。
2. **二進制不安全**：如果資料本身含有 `\0`（例如圖片、序列化後的 Java 物件），`strlen` 會提早截斷，讀到錯誤長度。

SDS（Simple Dynamic String）解法：
- 在結構體中直接記錄 `len` 欄位，O(1) 取長度。
- `buf[]` 可以存任意二進制資料，不以 `\0` 作為終止判斷。
- 額外記錄 `free` 欄位（預留空間），避免每次 append 都重新分配記憶體。

</details>

---

<details>
<summary>練習二：撮合引擎的訂單簿應該用 ZSet 的哪個指令取「最佳賣價（最低賣價）」前 5 筆？請寫出完整的 Redis CLI 指令。</summary>

**參考答案**：

賣單（ask side）的最佳賣價 = **最低的賣價**，ZSet 依 score（價格）從低到高排列，所以用 `ZRANGE` 取前 5 筆：

```bash
# 取賣單訂單簿前 5 筆（score 最小 = 最低賣價 = 最佳賣價）
ZRANGE orderbook:BTC:sell 0 4 WITHSCORES
```

對應的 Spring Boot 程式碼：
```java
Set<ZSetOperations.TypedTuple<String>> topSell =
    redisTemplate.opsForZSet().rangeWithScores("orderbook:BTC:sell", 0, 4);
```

補充說明：買單（bid side）的最佳買價 = **最高的買價**，要用 `ZREVRANGE`：
```bash
ZREVRANGE orderbook:BTC:buy 0 4 WITHSCORES
```

</details>

---

<details>
<summary>練習三：請解釋「快取穿透」和「快取擊穿」的差異，並各說明一種解法。</summary>

**參考答案**：

**快取穿透（Penetration）**：
- **定義**：查詢的資料在快取和 DB 中**都不存在**（通常是惡意攻擊，用無效 ID 打 API）。
- **後果**：每次請求都穿透快取，直接打 DB，因為 DB 也查無資料，無法寫進快取，下次請求仍然打 DB。
- **解法一**：快取空值——DB 查無資料時，依然寫入 `SET key "NULL" EX 60`，下次請求就能從快取得到「這筆資料不存在」的答案，不再打 DB。
- **解法二**：布隆過濾器（Bloom Filter）——在 Redis 前加一層判斷，過濾掉「不可能存在」的 key。

**快取擊穿（Breakdown）**：
- **定義**：一個**超熱門 key 的 TTL 剛好過期**，此時有大量並發請求同時衝進來，全部打 DB。
- **後果**：DB 瞬間收到超大流量，可能崩潰。
- **解法一**：互斥鎖（Mutex）——只讓第一個請求去打 DB 並更新快取，其他請求等待 50ms 再重查快取。
- **解法二**：邏輯過期——key 不設 TTL，在 value 裡記錄邏輯過期時間；過期時，非同步更新快取，但當下先回傳舊值，不讓請求打 DB。

**一句話分辨兩者**：
- 穿透 = 資料根本不存在，快取永遠無法擋住。
- 擊穿 = 資料存在，只是快取暫時失效，瞬間湧入大量請求。

</details>

---

> **本章總結**：Redis 的核心價值在於把資料存在 RAM（~0.1 ms）而非磁碟（~1~10 ms）。ZSet 的跳表結構讓它天然適合撮合引擎的訂單簿場景——依價格排序、O(log n) 查找最佳價位。它的單執行緒 + epoll 設計和 LMAX Disruptor 有共同的哲學：**消滅鎖競爭，讓每個操作足夠快，比多執行緒競爭更高效**。
