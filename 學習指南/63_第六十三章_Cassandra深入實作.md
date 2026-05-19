# 第六十三章：Cassandra 深入實作

## 前言：Cassandra 解決了什麼問題？

```
場景：IoT 平台，每秒接收 100 萬個感測器數據點
     每天產生 864 億筆記錄
     需要查詢「某設備最近 24 小時的溫度變化」

MySQL 的問題：
  ❌ 單表 100 億行，即使有索引也慢
  ❌ 水平分片複雜，跨片查詢性能差
  ❌ 大量寫入時，B+Tree 索引維護開銷巨大

Cassandra 的答案：
  ✅ 寫入吞吐量可以線性擴展（加節點就加容量）
  ✅ 寫入操作是 Append-Only（LSM-Tree），極快
  ✅ 按 Partition Key 自動分片，查詢不跨節點
  ✅ 無單點故障（P2P 架構，沒有 Master）
```

---

## 一、核心概念：資料模型

### Wide-Column 的結構

```
Keyspace（= MySQL 的 Database）
  └── Table
       └── Row（由 Partition Key 唯一標識）
            └── Column（可以動態增加，不同行可以有不同列）

sensor_data 表的概念：

Partition Key        Clustering Key    Column
(設備ID)            (時間戳)           (值)
─────────────────────────────────────────────
device_001          2024-01-15 09:00   temp=25.3, humidity=65
device_001          2024-01-15 09:01   temp=25.5, humidity=64
device_001          2024-01-15 09:02   temp=25.4, humidity=66
device_002          2024-01-15 09:00   temp=18.2, humidity=55
device_002          2024-01-15 09:01   temp=18.5, humidity=54
```

**關鍵概念：**
- **Partition Key**：決定資料存在哪個節點（Hash 取模），同一 Partition Key 的資料在同一節點
- **Clustering Key**：Partition 內部的排序方式（預設升序）
- 查詢**必須**指定 Partition Key（否則是全表掃描，效能極差）

---

## 二、CQL 語法：像 SQL 但不是 SQL

```sql
-- 建立 Keyspace（指定複製策略）
CREATE KEYSPACE iot_db
WITH replication = {
    'class': 'NetworkTopologyStrategy',
    'datacenter1': 3    -- 每個資料中心保留 3 份副本
};

USE iot_db;

-- 建立時序資料表
-- 設計原則：Partition Key = 查詢的主要維度，Clustering Key = 排序維度
CREATE TABLE sensor_readings (
    device_id    TEXT,          -- Partition Key：按設備分片
    reading_time TIMESTAMP,     -- Clustering Key：按時間排序
    temperature  DECIMAL,
    humidity     INT,
    location     TEXT,
    PRIMARY KEY (device_id, reading_time)    -- (Partition Key, Clustering Key)
) WITH CLUSTERING ORDER BY (reading_time DESC);  -- 預設降序（最新的先讀）
-- 時序數據幾乎都是查「最近的」，降序讀取效率更高

-- 插入資料（INSERT 本質是 UPSERT，不存在才插入沒有意義）
INSERT INTO sensor_readings (device_id, reading_time, temperature, humidity, location)
VALUES ('device_001', toTimestamp(now()), 25.3, 65, 'taipei');

-- ✅ 高效查詢：指定 Partition Key（不跨節點）
SELECT * FROM sensor_readings
WHERE device_id = 'device_001'
  AND reading_time >= '2024-01-15 00:00:00'
  AND reading_time <= '2024-01-15 23:59:59';

-- ❌ 低效查詢（全表掃描，Cassandra 預設禁止）
-- SELECT * FROM sensor_readings WHERE temperature > 30;
-- 必須加 ALLOW FILTERING，但生產環境禁止這樣用

-- 更新（實際上是新增一個 Tombstone 標記）
UPDATE sensor_readings
SET temperature = 26.0
WHERE device_id = 'device_001' AND reading_time = '2024-01-15 09:00:00';

-- 刪除（加 TTL 自動過期更常用）
DELETE FROM sensor_readings
WHERE device_id = 'device_001' AND reading_time = '2024-01-15 09:00:00';

-- 設定資料 TTL（30天後自動刪除）
INSERT INTO sensor_readings (device_id, reading_time, temperature)
VALUES ('device_001', toTimestamp(now()), 25.3)
USING TTL 2592000;  -- 秒數 = 30天
```

---

## 三、資料建模：一個查詢，一張表

Cassandra 建模的核心思想與 MySQL **完全相反**：

```
MySQL 思路：
  先正規化（消除冗余）→ 查詢時 JOIN 組合
  一張 orders 表，用各種 WHERE 條件查

Cassandra 思路：
  先定義查詢需求 → 為每個查詢設計一張表
  允許冗余（反正規化），換取查詢速度
```

### 實作範例：訊息系統

```sql
-- 需求 1：查詢某個對話的最近 100 條訊息（按時間倒序）
CREATE TABLE messages_by_conversation (
    conversation_id UUID,       -- Partition Key
    message_time    TIMESTAMP,  -- Clustering Key（倒序）
    message_id      UUID,
    sender_id       TEXT,
    content         TEXT,
    PRIMARY KEY (conversation_id, message_time, message_id)
) WITH CLUSTERING ORDER BY (message_time DESC, message_id DESC);

-- 需求 2：查詢某個用戶發出的所有訊息（按時間倒序）
-- ❌ 不能複用上面的表！Partition Key 是 conversation_id，無法按 sender_id 查
-- ✅ 建新表（資料冗余是可以接受的）
CREATE TABLE messages_by_sender (
    sender_id       TEXT,       -- Partition Key
    message_time    TIMESTAMP,  -- Clustering Key
    conversation_id UUID,
    message_id      UUID,
    content         TEXT,
    PRIMARY KEY (sender_id, message_time, message_id)
) WITH CLUSTERING ORDER BY (message_time DESC, message_id DESC);

-- 插入時，同時寫入兩張表（應用層負責保持一致）
-- 用 BATCH 保證原子性（僅限同一 Partition，跨 Partition 不保證）
BEGIN BATCH
  INSERT INTO messages_by_conversation (...) VALUES (...);
  INSERT INTO messages_by_sender (...) VALUES (...);
APPLY BATCH;
```

### Partition 大小限制

```
⚠️ 一個 Partition 的大小建議不超過 100MB
   如果 Partition 太大（Hot Partition），會導致：
   - 那個節點成為熱點，負載不均
   - 讀寫延遲增加

解決方案：加入「Bucket」維度縮小 Partition

-- 問題：如果某個熱門聊天室每天有 100 萬條訊息，Partition 會爆炸
CREATE TABLE messages_by_day (
    conversation_id UUID,
    day             DATE,       -- Bucket：加入日期，每天一個 Partition
    message_time    TIMESTAMP,
    content         TEXT,
    PRIMARY KEY ((conversation_id, day), message_time)  -- 複合 Partition Key
) WITH CLUSTERING ORDER BY (message_time DESC);

-- 查詢最近 7 天的訊息（需要查 7 個 Partition）
SELECT * FROM messages_by_day
WHERE conversation_id = ? AND day IN ('2024-01-15', '2024-01-14', ...);
```

---

## 四、一致性等級（Consistency Level）

Cassandra 預設 3 份副本，每次讀寫可以指定「需要幾個副本確認」：

```
一致性等級：
  ONE    → 只要 1 個副本確認（最快，可能讀到舊資料）
  TWO    → 2 個副本確認
  QUORUM → 多數副本確認（3 副本中要 2 個）
  ALL    → 全部副本確認（最慢，一個節點掛就失敗）
  LOCAL_QUORUM → 本地 datacenter 的多數副本（跨 IDC 常用）

黃金組合（寫 QUORUM + 讀 QUORUM）：
  寫：需要 2/3 副本確認 → 保證最新資料在多數節點
  讀：需要 2/3 副本確認 → 至少有一個節點有最新資料
  → 保證「讀到最新寫入的資料」（強一致性）

效能組合（寫 ONE + 讀 ONE）：
  → 最快，但可能讀到 Stale 資料（最終一致性）
  → 適合：日誌收集、IoT 數據（允許短暫不一致）
```

---

## 五、Spring Boot 整合

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-cassandra</artifactId>
</dependency>
```

```yaml
# application.yml
spring:
  cassandra:
    contact-points: localhost
    port: 9042
    keyspace-name: iot_db
    local-datacenter: datacenter1
    schema-action: CREATE_IF_NOT_EXISTS   # 開發環境自動建表
    request:
      consistency: LOCAL_QUORUM           # 預設一致性等級
      timeout: 10s
```

```java
// Entity 對應 Cassandra Table
@Table("sensor_readings")
public class SensorReading {

    @PrimaryKeyColumn(name = "device_id", type = PrimaryKeyType.PARTITIONED)
    private String deviceId;

    @PrimaryKeyColumn(name = "reading_time", type = PrimaryKeyType.CLUSTERED,
                      ordering = Ordering.DESCENDING)
    private Instant readingTime;

    private Double temperature;
    private Integer humidity;
    private String location;
}

// Repository（Spring Data 自動實作）
public interface SensorReadingRepository
        extends CassandraRepository<SensorReading, SensorReadingKey> {

    // 查詢某設備在時間範圍內的數據
    List<SensorReading> findByDeviceIdAndReadingTimeBetween(
        String deviceId, Instant start, Instant end);

    // 查詢最新 N 筆
    List<SensorReading> findTop100ByDeviceIdOrderByReadingTimeDesc(String deviceId);
}

// Service：批次寫入優化
@Service
public class SensorDataService {

    private final SensorReadingRepository repository;
    private final CassandraOperations cassandraOperations;

    // 批次插入（效能比單筆 INSERT 好）
    public void saveBatch(List<SensorReading> readings) {
        // Spring Data Cassandra 的 saveAll 內部使用批次
        repository.saveAll(readings);
    }

    // 使用 CassandraTemplate 執行原生 CQL（更靈活）
    public List<SensorReading> getRecentReadings(String deviceId, int limit) {
        String cql = "SELECT * FROM sensor_readings WHERE device_id = ? LIMIT ?";
        return cassandraOperations.select(cql, SensorReading.class, deviceId, limit);
    }
}
```

---

## 六、Cassandra vs MongoDB 選型 <!-- 💡 進階 -->

| 維度 | Cassandra | MongoDB |
|------|-----------|---------|
| **資料模型** | Wide-Column（固定 Schema）| Document（Schema-free）|
| **查詢靈活性** | 低（必須靠 Partition Key）| 高（任意欄位可建索引）|
| **寫入吞吐** | 極高（LSM-Tree，順序追加）| 高（WiredTiger 引擎）|
| **事務支援** | 單行原子，跨行無事務 | 支援多文件事務（4.0+）|
| **一致性** | 可調（ONE/QUORUM/ALL）| 強一致（主節點）|
| **水平擴展** | 天生（P2P 架構）| 需要設定 Sharding |
| **最適場景** | 時序、日誌、訊息歷史 | 商品目錄、CMS、靈活 Schema |
| **學習曲線** | 高（建模反直覺）| 低（JSON 很直覺）|

**選 Cassandra 的情況：**
- 寫入量超大（> 10 萬 TPS）
- 資料是時序型（有明確的時間排序維度）
- 查詢模式固定，不需要 ad-hoc 查詢
- 需要多資料中心部署和高可用

**選 MongoDB 的情況：**
- 資料 Schema 不固定
- 需要複雜查詢（多條件過濾、聚合）
- 需要事務支援
- 快速開發原型

---

## 本章練習題

**Q1：為什麼 Cassandra 的寫入速度比 MySQL 快這麼多？**
<details>
<summary>答案</summary>
Cassandra 使用 LSM-Tree（Log-Structured Merge-Tree）存儲引擎，寫入操作是：(1) 寫入 Commit Log（順序 IO，極快）；(2) 寫入記憶體 MemTable；(3) MemTable 滿了才批次刷到磁碟（SSTable）。整個過程都是「追加寫入」（Append-Only），不需要隨機定位磁碟位置。MySQL 使用 B+Tree 索引，每次插入都要找到正確的葉節點位置，可能觸發頁分裂（隨機 IO）。順序 IO 比隨機 IO 快幾十到幾百倍，這就是 Cassandra 寫入快的根本原因。
</details>

**Q2：Cassandra 的 Tombstone 是什麼？為什麼大量刪除操作是危險的？**
<details>
<summary>答案</summary>
Cassandra 的刪除不是真的刪除，而是寫入一個「Tombstone（墓碑）」標記，告訴讀取操作「這筆資料已被刪除」。真正的資料清理發生在 Compaction（合併 SSTable 的後台進程）時。危險的地方：(1) Tombstone 累積太多，讀取時要掃過大量 Tombstone 才能找到有效資料，讀取延遲上升；(2) 如果 Tombstone 比讀取查詢的 GC Grace Period（預設 10 天）舊，才會被清理，這期間讀取效能持續下降。最佳實踐：用 TTL 替代顯式刪除（設定資料過期時間，讓 Cassandra 自動處理），避免大量手動 DELETE。
</details>

**Q3：設計一個「外送平台訂單歷史」的 Cassandra Table，需要支援：(1) 查詢某個用戶的歷史訂單；(2) 查詢某個外送員的歷史訂單。**
<details>
<summary>答案</summary>
Cassandra 的原則是「一個查詢一張表」，需要建兩張表：

表1（按用戶查）：
CREATE TABLE orders_by_customer (
  customer_id TEXT,
  order_time TIMESTAMP,
  order_id UUID,
  restaurant_name TEXT,
  total_amount DECIMAL,
  status TEXT,
  PRIMARY KEY (customer_id, order_time, order_id)
) WITH CLUSTERING ORDER BY (order_time DESC, order_id DESC);

表2（按外送員查）：
CREATE TABLE orders_by_rider (
  rider_id TEXT,
  order_time TIMESTAMP,
  order_id UUID,
  customer_id TEXT,
  delivery_address TEXT,
  PRIMARY KEY (rider_id, order_time, order_id)
) WITH CLUSTERING ORDER BY (order_time DESC, order_id DESC);

兩張表都存了 order_id，應用層在建立訂單時同時寫入兩張表（可用 BATCH 保證原子性）。
</details>
