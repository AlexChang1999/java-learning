# 第六十二章：NoSQL 資料庫選型總覽

## 前言：NoSQL 不是「不用 SQL」，是「Not Only SQL」

關聯式資料庫（MySQL、PostgreSQL）解決了大多數業務問題，但在某些場景下有天生的限制：

```
MySQL 的痛點：
  ❌ 水平擴展難：JOIN 跨節點代價極高
  ❌ Schema 固定：加欄位要 ALTER TABLE，可能鎖表
  ❌ 不適合特定資料形狀：圖關係、時序數列、全文搜尋
  ❌ 超大資料量下效能下降：億級資料的聚合查詢慢

NoSQL 的解法：
  ✅ 針對特定資料模型最佳化（文件、KV、Wide-Column、圖、時序）
  ✅ 天生水平擴展（分片設計從一開始就考慮）
  ✅ Schema-free（部分 NoSQL 允許動態欄位）
  ✅ 特定查詢模式下效能遠超 RDBMS
```

---

## 一、五大 NoSQL 類型速覽

### 1. Key-Value Store（鍵值存儲）

```
資料形狀：key → value（value 可以是任何東西）

代表技術：Redis、DynamoDB、Memcached

特性：
  ✅ 讀寫速度最快（O(1) hash 查找）
  ✅ 水平擴展簡單（key 做一致性 Hash 即可分片）
  ❌ 只能靠 key 查詢，無法搜尋 value 的內容
  ❌ 不適合複雜的關聯查詢

典型場景：
  - Session 儲存（user_session:abc123 → {userId, role, expiry}）
  - 分散式快取（product:123 → {name, price, stock}）
  - 排行榜（Redis ZSet）
  - 分散式鎖（Redis SETNX）
  - 計數器（Redis INCR）
```

### 2. Document Store（文件存儲）

```
資料形狀：每筆資料是一個「文件」（JSON/BSON），文件結構可以不同

代表技術：MongoDB、CouchDB、Firestore

特性：
  ✅ Schema 靈活（不同文件可以有不同欄位）
  ✅ 可以查詢文件內的任何欄位（建索引後）
  ✅ 嵌套結構天然對應物件導向（不需要 JOIN）
  ❌ 不支援跨文件的 JOIN（需應用層處理）
  ❌ 不適合需要強一致性的事務（雖然 MongoDB 4.x 已支援多文件事務）

典型場景：
  - 商品目錄（每種商品屬性不同：電子產品有規格，服飾有尺寸）
  - 用戶 Profile（每個用戶有不同的偏好設定）
  - 內容管理系統（文章、評論、標籤結構複雜）
  - 即時遊戲狀態（玩家背包物品結構多樣）
```

### 3. Wide-Column Store（寬列存儲）

```
資料形狀：每一行有固定的 Partition Key，其餘列可動態增加
          像是「稀疏的二維表」，不同行可以有不同的列

代表技術：Cassandra、HBase、Google Bigtable

特性：
  ✅ 極高的寫入吞吐量（LSM-Tree 結構，寫入直接追加）
  ✅ 天生水平擴展（按 Partition Key 自動分片）
  ✅ 時間序列資料的絕佳選擇
  ❌ 查詢必須靠 Partition Key（不能任意 WHERE）
  ❌ 資料建模反直覺（要先想查詢再設計 Schema）
  ❌ 不支援事務（單行操作是原子的，跨行不保證）

典型場景：
  - IoT 感測器數據（設備ID + 時間戳 → 溫度/壓力數值）
  - 用戶行為日誌（userId + timestamp → 事件）
  - 訊息記錄（chatId + messageId → 消息內容）
  - 金融交易歷史（accountId + txnDate → 交易紀錄）
```

### 4. Columnar Store / OLAP（列式存儲 / 分析型）

```
資料形狀：看起來像關聯式表，但按「列」儲存（而非按「行」）

代表技術：ClickHouse、Apache Parquet、Amazon Redshift、Google BigQuery

特性：
  ✅ 聚合查詢（SUM, AVG, COUNT GROUP BY）速度極快
  ✅ 壓縮率極高（同一列的資料類型相同，壓縮效果好）
  ✅ 向量化執行（CPU SIMD 指令批次處理）
  ❌ 單行插入/更新/刪除效能差（不適合 OLTP）
  ❌ 不適合查詢單一行的所有欄位（要讀取多個列文件）

典型場景：
  - 用戶行為分析（「過去 30 天各省的活躍用戶數」）
  - 訂單報表（「每個商品類別的月銷售額趨勢」）
  - 日誌分析（「過去一小時 ERROR 日誌的來源 IP 統計」）
  - 廣告效果分析（「每個廣告的 CTR、CVR 漏斗」）
```

### 5. Graph Database（圖資料庫）

```
資料形狀：節點（Node）+ 邊（Edge）+ 屬性

代表技術：Neo4j、Amazon Neptune、TigerGraph

特性：
  ✅ 圖遍歷查詢效率極高（「A 的朋友的朋友」）
  ✅ 關係本身可以有屬性（like: {since: 2024-01-01, weight: 0.8}）
  ✅ 複雜網路分析（最短路徑、社群發現、PageRank）
  ❌ 非圖結構的資料性能和 RDBMS 差不多甚至更差
  ❌ 學習成本高（Cypher 查詢語言）

典型場景：
  - 社交網路（好友推薦、六度分隔）
  - 知識圖譜（實體關係推理）
  - 欺詐偵測（異常交易關係鏈）
  - 推薦系統（協同過濾圖模型）
  - 供應鏈依賴分析
```

### 6. Time-Series Database（時序數據庫）

```
資料形狀：(timestamp, metric_name, tags) → value
          時間戳是第一等公民

代表技術：InfluxDB、TimescaleDB（PostgreSQL 擴展）、Prometheus（監控專用）

特性：
  ✅ 時間範圍查詢和降採樣（Down-sampling）極快
  ✅ 自動資料保留策略（超過 N 天自動刪除）
  ✅ 內建時間函數（time_bucket, moving_average...）
  ❌ 非時序查詢支援有限

典型場景：
  - 伺服器監控（CPU、記憶體、網路流量）
  - IoT 感測器（每秒溫度、濕度讀數）
  - 金融行情（每毫秒的股價 Tick 數據）
  - 應用效能監控（APM）
```

---

## 二、選型決策樹

```
我的資料長什麼樣子？
│
├── 需要高速讀寫，資料結構簡單（Key → Value）？
│     └── Redis（記憶體快取、鎖、計數）
│         DynamoDB（無限擴展的 KV，AWS 生態）
│
├── 資料是 JSON 文件，欄位不固定，需要靈活查詢？
│     └── MongoDB（最通用的文件型）
│
├── 海量時序數據，寫入比讀取多，按時間/ID 查詢？
│     ├── 需要毫秒級 Timestamp + 標籤（監控/IoT）
│     │     └── InfluxDB / TimescaleDB
│     └── 需要超高寫入吞吐（億級/天），查詢靠 Row Key
│           └── Cassandra / HBase
│
├── 需要做聚合分析（SUM/GROUP BY/趨勢報表），資料量大？
│     └── ClickHouse / BigQuery / Redshift（OLAP）
│
├── 需要全文搜尋（分詞、相關度排序、高亮）？
│     └── Elasticsearch / OpenSearch
│
├── 資料是「關係網路」（誰認識誰，誰影響誰）？
│     └── Neo4j / Amazon Neptune（圖資料庫）
│
└── 以上都不是，資料有關聯、需要事務
      └── MySQL / PostgreSQL（關聯式資料庫，首選）
```

---

## 三、Polyglot Persistence（多種資料庫混搭）

真實的生產系統通常**同時使用多種資料庫**，每種資料庫做自己最擅長的事：

```
電商系統的典型混搭：

                    ┌─────────────────────────────────┐
                    │         電商應用服務               │
                    └──┬──┬──┬──┬──┬──────────────────┘
                       │  │  │  │  │
          ┌────────────┘  │  │  │  └────────────────────┐
          │               │  │  │                        │
    MySQL/PostgreSQL    Redis  Elasticsearch  MongoDB  ClickHouse
          │               │        │             │         │
    訂單/用戶/支付    Session/快取  商品搜尋   商品目錄   銷售報表
    （強一致性交易）  （高速KV）   （全文搜尋）（靈活Schema）（聚合分析）
```

### 實際案例：「下單」這個動作會動到哪些資料庫？

```
① 用戶點「立即付款」
   → Redis：讀取 Session 驗證登入狀態
   → Redis SETNX：冪等鎖，防止重複下單

② 查詢商品庫存
   → MySQL：讀取 products 表（強一致性）

③ 建立訂單
   → MySQL：INSERT INTO orders（事務保證）

④ 扣庫存
   → MySQL：UPDATE products SET stock = stock - 1 WHERE id = ?

⑤ 更新快取
   → Redis：DEL product:123（使快取失效）

⑥ 發送「訂單建立」事件
   → Kafka → Elasticsearch 更新訂單搜尋索引
   → Kafka → ClickHouse 插入銷售分析數據
```

---

## 四、CAP 定理與 NoSQL 的選擇

```
CAP 定理：分散式系統只能同時保證以下三個中的兩個：
  C（Consistency）   一致性：所有節點同時看到相同的資料
  A（Availability）  可用性：每個請求都能收到回應（不管是否最新）
  P（Partition Tolerance）分區容忍：節點間通訊中斷時系統仍可運作

各資料庫的取捨：

CP（強一致性，犧牲可用性）：
  MySQL（主從），ZooKeeper，HBase，MongoDB（多文件事務）
  → 網路分區時，寧可拒絕服務也不返回錯誤資料
  → 適合：金融、訂單、庫存

AP（高可用，最終一致）：
  Cassandra，DynamoDB，CouchDB，Redis Cluster
  → 網路分區時，仍可服務，但不同節點可能暫時不一致
  → 適合：社群動態、推薦、購物車

CA（強一致 + 可用，無分區容忍）：
  傳統單機 RDBMS
  → 不是分散式系統，不存在分區問題
```

---

## 五、各資料庫一句話定位

| 資料庫 | 一句話定位 | 最適合場景 |
|--------|-----------|-----------|
| MySQL/PostgreSQL | 事務型業務的標準選擇 | 訂單、用戶、支付 |
| Redis | 記憶體速度的萬用工具 | 快取、鎖、計數、排行 |
| MongoDB | 靈活 Schema 的文件型 | 商品目錄、CMS |
| Cassandra | 超高寫入吞吐的時序王者 | IoT、日誌、訊息歷史 |
| Elasticsearch | 搜尋與分析的雙料選手 | 全文搜尋、日誌分析 |
| ClickHouse | OLAP 分析的速度王 | 報表、用戶行為分析 |
| Neo4j | 圖關係的專家 | 社交、欺詐偵測 |
| InfluxDB | 時間序列的專精 | 監控、APM、IoT |
| Kafka | 事件流的骨幹 | 解耦、削峰、事件溯源 |

---

## 本章練習題

**Q1：電商的「購物車」功能，用 MySQL 還是 Redis 存比較好？**
<details>
<summary>答案</summary>
兩種方案都可以，取決於需求。用 Redis（Hash 結構）的優點：讀寫速度極快（每次點「加入購物車」都是寫操作），購物車數據不需要強一致性，允許短暫不同步。用 MySQL 的優點：資料持久化更可靠，可以做訂單歷史關聯查詢。業界常見的混搭方案：用 Redis 存「活躍購物車」（TTL 7 天），定期 or 下單時同步到 MySQL 做持久化。如果是大型電商（億級用戶），Redis 的讀寫性能無可替代。
</details>

**Q2：為什麼說 Cassandra 「要先想查詢，再設計 Table」？**
<details>
<summary>答案</summary>
MySQL 的設計流程是：先把業務實體正規化（User 表、Order 表、Product 表），查詢時用 JOIN 組合。Cassandra 沒有 JOIN，且只能靠 Partition Key 做高效查詢。如果你先設計了表，再想查詢，很可能發現查詢需要全表掃描（Full Scan），這在 Cassandra 裡是災難性的性能問題。正確做法：先確定「我需要哪些查詢」，例如「根據 userId 查最近 100 條訊息」，再設計 Table 的 Partition Key = userId，Clustering Key = messageTime（降序）。這樣查詢完全走索引，速度飛快。
</details>

**Q3：OLTP 和 OLAP 的根本差異是什麼？為什麼需要分開用不同的資料庫？**
<details>
<summary>答案</summary>
OLTP（Online Transaction Processing）：每次處理少量行，高並發讀寫，需要事務保證（下一筆訂單、扣一次庫存）。OLAP（Online Analytical Processing）：每次掃描大量行，但只讀取少數幾列（「統計所有訂單的總金額按月份分組」）。行存儲（OLTP）的問題：聚合查詢要讀整行再過濾列，IO 浪費大。列存儲（OLAP）的優勢：只讀需要的列，壓縮率高，向量化 SIMD 批次計算，同一查詢可以快 10-100 倍。為什麼要分開：如果直接在 MySQL 上跑複雜 OLAP 查詢，會鎖表影響線上業務（OLTP）。通常用 ETL 或 CDC（如 Debezium）把 OLTP 資料同步到 OLAP 庫做分析，兩者完全隔離。
</details>
