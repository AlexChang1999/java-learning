# 第二十八章：Kafka 訊息佇列

> **前置知識**：第十章（多執行緒）、第二十一章（Spring Boot）
> **核心專案連結**：撮合引擎的成交通知、訂單事件流

---

## 本章目標

讀完本章，你將能夠：

1. 解釋為什麼撮合引擎需要訊息佇列
2. 說明 Kafka 的核心元件（Broker、Topic、Partition、Offset）
3. 分辨三種訊息傳遞保證，並選出適合撮合引擎的方案
4. 用 Spring Boot 整合 Kafka，實作生產者與消費者
5. 說明 Kafka 與 RabbitMQ 的差異，以及撮合引擎選 Kafka 的理由

---

## 一、為什麼需要訊息佇列？

### 1.1 先看沒有訊息佇列的世界

假設你的撮合引擎在完成一筆成交後，需要通知三個下游服務：

- **通知服務**：傳 Email/簡訊給買賣雙方
- **結算服務**：更新帳戶餘額
- **報表服務**：寫入成交紀錄

最直觀的做法是：撮合引擎直接呼叫這三個服務的 API。

```
【無 Kafka 的直連架構】

       ┌─────────────────────┐
       │     撮合引擎         │
       │  (Match Engine)     │
       └──────────┬──────────┘
                  │ 直接 HTTP 呼叫
        ┌─────────┼──────────┐
        ▼         ▼          ▼
  ┌──────────┐ ┌──────────┐ ┌──────────┐
  │  通知服務 │ │  結算服務 │ │  報表服務 │
  └──────────┘ └──────────┘ └──────────┘
      ❌ 掛掉       ✅ 正常      ✅ 正常
  → 這筆通知就永遠丟失了！
```

這個架構有三個致命問題：

**問題一：服務掛掉，訊息就丟了**
通知服務如果因為記憶體不足崩潰，撮合引擎的呼叫會失敗。這筆成交通知就永遠消失了——買家不知道自己的訂單成交，非常糟糕。

**問題二：下游服務拖慢上游**
訂單高峰期（比如台股開盤的 9:00），每秒可能有幾千筆成交。如果通知服務處理速度很慢（每筆要 50ms），撮合引擎就要等，整個系統會被最慢的那個服務拖垮。

**問題三：服務間緊密耦合**
每次新增一個下游服務，就要修改撮合引擎的程式碼，加一行 API 呼叫。這違反了開放/封閉原則（OCP）。

### 1.2 加入訊息佇列之後

```
【有 Kafka 的解耦架構】

       ┌─────────────────────┐
       │     撮合引擎         │
       │  (Match Engine)     │
       └──────────┬──────────┘
                  │ 發送事件（fire-and-forget）
                  ▼
       ┌─────────────────────┐
       │       Kafka         │  ← 訊息持久化存在磁碟
       │  Topic: trade-exec  │     即使消費者掛掉也不丟
       └──────────┬──────────┘
                  │ 各自讀取，各自處理
        ┌─────────┼──────────┐
        ▼         ▼          ▼
  ┌──────────┐ ┌──────────┐ ┌──────────┐
  │  通知服務 │ │  結算服務 │ │  報表服務 │
  └──────────┘ └──────────┘ └──────────┘
      ❌ 掛掉       ✅ 正常      ✅ 正常
  → 通知服務重啟後，從 Kafka 繼續讀取，不丟訊息！
```

三個好處立刻體現：

| 問題 | 解法 |
|------|------|
| 服務掛掉訊息丟失 | Kafka 把訊息存在磁碟，服務重啟後繼續消費 |
| 下游拖慢上游 | 撮合引擎只管丟訊息，消費者按自己速度處理（**削峰填谷**） |
| 緊密耦合 | 新增服務只要訂閱同一個 Topic，不需改撮合引擎程式碼（**解耦**） |

---

## 二、Kafka 核心概念（從硬體往上講）

理解 Kafka 為什麼快，要從硬體層開始。

### 2.1 為什麼 Kafka 要用磁碟卻還這麼快？

很多人以為「用磁碟就慢」，這是誤解。關鍵在於「**怎麼寫**」：

```
【磁碟讀寫速度比較（SSD）】

隨機寫入（Random Write）：
  每次寫入前要先「尋址」—— 找到磁碟上的位置
  速度約 ~100 MB/s，延遲 ~100μs

順序寫入（Sequential Write）：
  不需要尋址，直接接著上次寫到的位置往後寫
  速度約 ~500 MB/s，延遲 ~10μs

差距：順序寫比隨機寫快將近 5 倍（吞吐量），延遲差 10 倍
```

Kafka 的秘訣就是：**所有寫入都是順序的 Append-Only（只追加到尾端）**。
就像在作業本上只往最後一頁接著寫，而不是隨機翻到某頁修改。

### 2.2 Broker：Kafka 的伺服器

**Broker** 就是 Kafka 的伺服器節點，負責：

- 接收 Producer 發來的訊息，**寫入磁碟**
- 提供 Consumer 讀取訊息
- 一個 Kafka 叢集通常有 3～5 個 Broker，互相備援

```
【Kafka 叢集示意】

  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
  │  Broker  1  │  │  Broker  2  │  │  Broker  3  │
  │  (Leader)   │  │  (Follower) │  │  (Follower) │
  │  [磁碟]     │  │  [磁碟]     │  │  [磁碟]     │
  └─────────────┘  └─────────────┘  └─────────────┘
        ↑ 如果 Broker 1 掛掉，Broker 2 自動成為 Leader
```

**撮合引擎連結**：生產環境中，撮合引擎發送的每筆成交事件都會被複製到多個 Broker，確保不因單點故障而丟失。

### 2.3 Topic：訊息的分類

**Topic** 就是訊息的「頻道」或「主題」，類似於資料庫的 Table 名稱。

撮合引擎可能有這些 Topic：

```
Kafka Topics（訊息主題）

  "order-placed"       → 有新訂單進來
  "trade-executed"     → 撮合成功，成交了
  "order-cancelled"    → 訂單被取消
  "account-updated"    → 帳戶餘額更新
```

Producer 把訊息發到特定 Topic，Consumer 訂閱自己需要的 Topic。

### 2.4 Partition：Topic 的分片

**Partition** 是 Topic 的物理分片，是真正存資料的地方。每個 Partition 就是磁碟上的一個 **Append-Only Log 檔案**。

```
【Topic "trade-executed" 分成 3 個 Partition】

Partition 0：  [msg0] → [msg3] → [msg6] → [msg9] → ...
                 │        │        │        │
               offset=0  offset=1  offset=2  offset=3

Partition 1：  [msg1] → [msg4] → [msg7] → [msg10] → ...

Partition 2：  [msg2] → [msg5] → [msg8] → [msg11] → ...
```

**為什麼要分 Partition？**

一個 Partition 只能被一個 Consumer 同時讀取（保證有序）。
分成多個 Partition，就能讓多個 Consumer **並行讀取**，大幅提升吞吐量。

```
單一 Partition（效能瓶頸）：
  Producer → [Partition 0] → Consumer A
                              （只有一個人能讀）

三個 Partition（並行處理）：
  Producer → [Partition 0] → Consumer A
           → [Partition 1] → Consumer B   ← 三倍吞吐量！
           → [Partition 2] → Consumer C
```

**Append-Only 為什麼快？**

每條訊息都只追加到 Partition 尾端，永不修改，是純粹的順序寫。這讓 Kafka 能達到接近硬體極限的寫入速度（SSD ~500 MB/s）。

### 2.5 Offset：訊息的座標

**Offset** 是每條訊息在 Partition 內的位置編號，從 0 開始，只增不減。就像陣列的 index。

```
【Offset 概念圖】

Partition 0 的磁碟 Log：

位置:    [offset=0] [offset=1] [offset=2] [offset=3] [offset=4]
         ─────────────────────────────────────────────────────→ 時間軸
內容:    {訂單A成交} {訂單B成交} {訂單C成交} {訂單D成交} {訂單E成交}

Consumer 目前讀到 offset=2：
  → 下次重啟，從 offset=3 繼續讀
  → 如果要「重放」歷史，把 offset 設回 0 就好！
```

**撮合引擎連結**：Offset 讓撮合引擎具備「事件重放」能力。如果系統崩潰需要重建狀態，把 offset 設回 0，重新讀取所有歷史訂單事件，即可還原任意時間點的帳戶狀態。

### 2.6 Producer：發送訊息

**Producer** 是訊息的發送方。在撮合引擎中，撮合成功後就會產生一個 Producer，發送 `trade-executed` 事件。

Producer 發送訊息時，Kafka 用以下策略決定訊息進哪個 Partition：

- **有指定 Key**：相同 Key 的訊息永遠進同一個 Partition（保證有序）
- **無 Key**：Round-robin 輪流分配

**撮合引擎連結**：用股票代碼（如 "2330"）作為 Key，確保同一支股票的所有成交事件都在同一個 Partition，保持時間順序。

### 2.7 Consumer 與 Consumer Group

**Consumer** 是訊息的接收方。**Consumer Group** 是一群 Consumer 的集合，Kafka 會把 Partition 均衡分配給 Group 內的每個 Consumer。

```
【Consumer Group 的負載均衡（3 Partition + 3 Consumer）】

Topic "trade-executed"
  Partition 0 ────→ Consumer A ┐
  Partition 1 ────→ Consumer B ├─ Consumer Group "notification-group"
  Partition 2 ────→ Consumer C ┘

每個 Partition 只給 Group 內一個 Consumer，保證同一 Partition 內有序處理
```

```
【Consumer 數量 < Partition 數量時】

  Partition 0 ──┐
  Partition 1 ──┼──→ Consumer A   ← A 要處理兩個 Partition
  Partition 2 ────→ Consumer B

【Consumer 數量 > Partition 數量時】

  Partition 0 ────→ Consumer A
  Partition 1 ────→ Consumer B
  （Consumer C 閒置，因為沒有多餘的 Partition 可以分配）
```

**重點**：Partition 數量決定了最大並行度。撮合引擎設計時，Partition 數量要根據預期的 Consumer 數量來規劃。

---

## 三、訊息傳遞保證

這是 Kafka 使用中最重要的設計決策，直接影響資料正確性。

### 3.1 三種保證等級

#### At-Most-Once（最多一次）：可能丟失，但不重複

```
流程：Producer 發送 → Consumer 收到後「立刻確認」→ 處理訊息
問題：確認後、處理前如果 Consumer 崩潰 → 訊息丟失，Kafka 不再重發
```

**適用場景**：可以接受少量丟失的場景，例如即時廣告曝光統計。

#### At-Least-Once（至少一次）：不丟失，但可能重複

```
流程：Producer 發送 → Consumer 處理完後才確認（commit offset）
問題：處理完但確認前崩潰 → Kafka 認為未處理，重新發送 → 重複處理
```

**這是最常用的模式**，需要在業務邏輯層實作「冪等性（Idempotency）」來處理重複。

#### Exactly-Once（恰好一次）：不丟失，不重複

```
流程：使用 Kafka Transaction + Idempotent Producer
代價：效能開銷大（需要分散式 Transaction），實作複雜
```

### 3.2 撮合引擎應該選哪種？

**答案：At-Least-Once + 冪等設計**

分析如下：

| 情境 | 要求 | 方案 |
|------|------|------|
| 成交通知（Email/簡訊） | **不能丟**（客戶沒收到通知） | At-Least-Once |
| 帳戶扣款/入帳 | **不能重複**（扣兩次錢） | At-Least-Once + 冪等 |
| 成交報表 | 不能丟 | At-Least-Once |

**冪等設計範例**：每筆成交事件帶一個唯一 `tradeId`，消費者處理前先查資料庫是否已處理過這個 `tradeId`。重複收到時直接忽略。

```java
// 冪等消費者範例
@KafkaListener(topics = "trade-executed")
public void handleTrade(TradeEvent event) {
    // 先檢查是否已處理過（冪等性保護）
    if (tradeRepository.existsByTradeId(event.getTradeId())) {
        log.info("重複訊息，忽略：tradeId={}", event.getTradeId());
        return; // 直接跳過，不重複處理
    }
    // 第一次收到，正常處理
    notificationService.sendTradeNotification(event);
    tradeRepository.markAsProcessed(event.getTradeId());
}
```

---

## 四、Spring Boot 整合 Kafka

### 4.1 加入依賴

在 `pom.xml` 加入 `spring-kafka`：

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.kafka</groupId>
    <artifactId>spring-kafka</artifactId>
    <!-- Spring Boot 會自動管理版本，不需要指定 -->
</dependency>
```

### 4.2 application.yml 設定

```yaml
# application.yml
spring:
  kafka:
    # Kafka Broker 的地址（本地開發用 localhost）
    bootstrap-servers: localhost:9092

    # 生產者設定
    producer:
      # 訊息序列化：Java 物件 → JSON 字串 → bytes
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      # 確保訊息不丟失：等待所有 Replica 確認後才算發送成功
      acks: all
      # 網路問題時自動重試 3 次
      retries: 3

    # 消費者設定
    consumer:
      # Consumer Group 名稱（相同 Group 的消費者共享 Partition）
      group-id: trade-notification-group
      # 訊息反序列化：bytes → JSON 字串 → Java 物件
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      # 啟動時若無已提交的 offset，從最早的訊息開始讀
      auto-offset-reset: earliest
      # 關閉自動提交！改為手動控制，實現 at-least-once
      enable-auto-commit: false
      properties:
        # 告訴 JsonDeserializer 允許哪些 Java 類別被反序列化（安全設定）
        spring.json.trusted.packages: "com.example.matchingengine.event"

    # 手動確認（ack）模式設定
    listener:
      ack-mode: MANUAL_IMMEDIATE
```

### 4.3 定義事件物件

```java
// TradeEvent.java - 成交事件的資料結構
package com.example.matchingengine.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TradeEvent {

    private String tradeId;       // 唯一成交 ID（用於冪等性）
    private String stockSymbol;   // 股票代碼，如 "2330"
    private String buyOrderId;    // 買單 ID
    private String sellOrderId;   // 賣單 ID
    private BigDecimal price;     // 成交價格
    private int quantity;         // 成交數量（股）
    private LocalDateTime tradedAt; // 成交時間

    // 建構子、getter、setter（省略，建議用 Lombok @Data）
    public TradeEvent() {}

    public TradeEvent(String tradeId, String stockSymbol,
                      String buyOrderId, String sellOrderId,
                      BigDecimal price, int quantity) {
        this.tradeId = tradeId;
        this.stockSymbol = stockSymbol;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.price = price;
        this.quantity = quantity;
        this.tradedAt = LocalDateTime.now();
    }

    // getters...
    public String getTradeId() { return tradeId; }
    public String getStockSymbol() { return stockSymbol; }
    public String getBuyOrderId() { return buyOrderId; }
    public String getSellOrderId() { return sellOrderId; }
    public BigDecimal getPrice() { return price; }
    public int getQuantity() { return quantity; }
    public LocalDateTime getTradedAt() { return tradedAt; }
}
```

### 4.4 Producer：撮合成功後發送事件

```java
// TradeEventProducer.java - 撮合引擎的事件發送元件
package com.example.matchingengine.kafka;

import com.example.matchingengine.event.TradeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
public class TradeEventProducer {

    private static final Logger log = LoggerFactory.getLogger(TradeEventProducer.class);

    // Topic 名稱定義為常數，避免打錯字
    private static final String TOPIC = "trade-executed";

    // Spring 自動注入，泛型 <Key類型, Value類型>
    private final KafkaTemplate<String, TradeEvent> kafkaTemplate;

    public TradeEventProducer(KafkaTemplate<String, TradeEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * 撮合成功後呼叫此方法，發送成交事件到 Kafka
     *
     * @param event 成交事件物件
     */
    public void sendTradeEvent(TradeEvent event) {
        // 用股票代碼作為 Key，確保同一支股票的事件進同一個 Partition（保持有序）
        String partitionKey = event.getStockSymbol();

        // 非同步發送，不阻塞撮合引擎的主流程
        CompletableFuture<SendResult<String, TradeEvent>> future =
            kafkaTemplate.send(TOPIC, partitionKey, event);

        // 設定回調：發送成功/失敗時的處理
        future.whenComplete((result, ex) -> {
            if (ex == null) {
                // 發送成功：記錄 Partition 和 Offset
                log.info("成交事件發送成功 | tradeId={} | partition={} | offset={}",
                    event.getTradeId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());
            } else {
                // 發送失敗：記錄錯誤（實際生產環境需要告警通知）
                log.error("成交事件發送失敗 | tradeId={} | 錯誤：{}",
                    event.getTradeId(), ex.getMessage());
            }
        });
    }
}
```

### 4.5 Consumer：通知服務接收成交事件

```java
// TradeNotificationConsumer.java - 通知服務的消費者
package com.example.matchingengine.kafka;

import com.example.matchingengine.event.TradeEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class TradeNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradeNotificationConsumer.class);

    private final NotificationService notificationService;
    private final ProcessedTradeRepository processedTradeRepo;

    public TradeNotificationConsumer(NotificationService notificationService,
                                     ProcessedTradeRepository processedTradeRepo) {
        this.notificationService = notificationService;
        this.processedTradeRepo = processedTradeRepo;
    }

    /**
     * @KafkaListener 會讓 Spring 自動建立一個背景執行緒，持續監聽 Topic
     *
     * topics    = 要監聽的 Topic 名稱
     * groupId   = Consumer Group（複數消費者可共享負載）
     * containerFactory = 指定使用哪個 KafkaListenerContainerFactory（對應 yml 設定）
     */
    @KafkaListener(
        topics = "trade-executed",
        groupId = "trade-notification-group"
    )
    public void handleTradeExecuted(
        ConsumerRecord<String, TradeEvent> record,
        Acknowledgment ack  // 手動確認物件（at-least-once 的關鍵）
    ) {
        TradeEvent event = record.value();

        log.info("收到成交事件 | partition={} | offset={} | tradeId={}",
            record.partition(), record.offset(), event.getTradeId());

        try {
            // ── 冪等性檢查 ──
            // 防止重複處理同一筆成交（網路重試可能導致重複收到）
            if (processedTradeRepo.existsByTradeId(event.getTradeId())) {
                log.warn("重複訊息，跳過處理 | tradeId={}", event.getTradeId());
                ack.acknowledge(); // 仍然要確認，否則 Kafka 會一直重發
                return;
            }

            // ── 業務邏輯 ──
            // 發送成交通知給買賣雙方
            notificationService.sendTradeNotification(event);

            // 記錄已處理（冪等性保護）
            processedTradeRepo.markAsProcessed(event.getTradeId());

            // ── 手動確認 ──
            // 告訴 Kafka：「我已成功處理這條訊息，可以移動 offset 了」
            // 如果不呼叫 ack.acknowledge()，Kafka 下次會重新發送這條訊息
            ack.acknowledge();

            log.info("成交通知發送完成 | tradeId={}", event.getTradeId());

        } catch (Exception e) {
            // 發生錯誤時「不確認」，讓 Kafka 在稍後重試
            log.error("處理成交事件失敗 | tradeId={} | 錯誤：{}",
                event.getTradeId(), e.getMessage());
            // 注意：這裡沒有呼叫 ack.acknowledge()
            // Kafka 會根據重試策略重新發送這條訊息
        }
    }
}
```

### 4.6 撮合引擎完整事件流

把上面的元件串起來，看完整的資料流：

```
【撮合引擎完整事件流】

步驟一：前端送出下單請求
  User ──HTTP POST──→ OrderController
                           │
                           ▼
                      OrderService
                      （驗證、存入DB）
                           │
                           ▼

步驟二：撮合引擎嘗試撮合
                      MatchingEngine
                      （從 OrderBook 找配對）
                           │
              ┌────────────┴────────────┐
              │ 撮合成功                 │ 撮合失敗
              ▼                         ▼
        建立 TradeEvent             訂單進入等待佇列
              │
              ▼

步驟三：發送事件到 Kafka
        TradeEventProducer
        .sendTradeEvent(event)
              │
              ▼
        Kafka Topic: "trade-executed"
        Partition 由 stockSymbol 決定
              │
    ┌─────────┼──────────────┐
    │         │              │
    ▼         ▼              ▼

步驟四：各消費者獨立處理
 通知服務        結算服務       報表服務
(Consumer A)  (Consumer B)  (Consumer C)
   │               │              │
   ▼               ▼              ▼
發 Email/簡訊   更新帳戶餘額   寫入成交報表
```

---

## 五、Kafka vs 傳統訊息佇列（RabbitMQ）

### 5.1 核心差異

| 特性 | Kafka | RabbitMQ |
|------|-------|----------|
| 儲存位置 | **磁碟持久化** | 記憶體為主（可選持久化） |
| 消費後訊息 | **訊息還在**，保留指定時間（預設 7 天） | 消費後刪除 |
| 歷史重放 | **支援**（調整 offset 即可） | 不支援 |
| 吞吐量 | 極高（百萬/秒） | 較低（萬/秒） |
| 訊息順序 | Partition 內保證有序 | 預設有序（單佇列） |
| 適合場景 | 事件流、日誌、事件溯源 | 任務佇列、RPC 模式 |

### 5.2 撮合引擎選 Kafka 的理由：事件溯源

**事件溯源（Event Sourcing）** 是撮合引擎最重要的設計模式之一：

```
【傳統做法（狀態快照）】

  資料庫只存「當前狀態」：
  帳戶 A 餘額 = 50,000 元

  問題：如果發現資料錯誤，無法知道「是哪一筆交易出問題的」

【事件溯源（Kafka 持久化所有事件）】

  Kafka 保存所有歷史事件：
  offset=0: 帳戶A 入金 100,000 元
  offset=1: 帳戶A 買入 2330 成交 30,000 元
  offset=2: 帳戶A 賣出 0050 成交 20,000 元
  offset=3: 帳戶A 出金 40,000 元
  → 當前餘額 = 100,000 - 30,000 + 20,000 - 40,000 = 50,000 元 ✓

  優點：
  1. 可以重放任何時間點的狀態（offset 回到 0，重新計算）
  2. 完整的審計軌跡（Audit Trail），符合金融法規
  3. 系統崩潰後可完全重建，不需要備份快照
```

**撮合引擎連結**：如果撮合引擎需要升級演算法或修正 bug，可以把 Kafka 的 offset 設回歷史某個時間點，用新演算法重新處理所有歷史訂單，驗證結果是否正確。這在 RabbitMQ 架構下是完全不可能的。

---

## 六、本章小結

```
【本章知識地圖】

為什麼需要 Kafka？
  ├─ 解耦：Producer 和 Consumer 不需要同時上線
  ├─ 削峰填谷：高峰期訊息暫存，消費者按速度處理
  └─ 可靠性：訊息持久化到磁碟，服務重啟不丟訊息

Kafka 核心概念（由底層往上）
  ├─ 磁碟順序寫：~500MB/s，比隨機寫快 5 倍，是 Kafka 快的根本原因
  ├─ Broker：Kafka 伺服器，負責存儲和分發訊息
  ├─ Topic：訊息的分類（"trade-executed"）
  ├─ Partition：Topic 的分片，Append-Only Log，決定最大並行度
  ├─ Offset：訊息在 Partition 內的位置，支援歷史重放
  ├─ Producer：發送訊息（撮合引擎 → Kafka）
  └─ Consumer Group：並行消費，每個 Partition 只給一個 Consumer

訊息傳遞保證
  ├─ At-Most-Once：可能丟失，效能最好
  ├─ At-Least-Once：可能重複，需冪等設計（撮合引擎選此）
  └─ Exactly-Once：最可靠，效能開銷最大

Spring Boot 整合
  ├─ KafkaTemplate：發送訊息
  └─ @KafkaListener：接收訊息（手動 ack = at-least-once）

Kafka vs RabbitMQ
  └─ 撮合引擎選 Kafka：磁碟持久化 + 訊息可重放 = 事件溯源架構
```

---

## 七、練習題

### 練習一：概念理解

一個 Topic 有 4 個 Partition，你的 Consumer Group 有 6 個 Consumer 實例。
請問：
1. 有幾個 Consumer 實際上在工作？
2. 有幾個 Consumer 閒置？
3. 如果要讓所有 Consumer 都有工作，至少要有幾個 Partition？

<details>
<summary>點我看答案</summary>

1. **4 個 Consumer 在工作**：每個 Partition 只能分配給一個 Consumer，4 個 Partition 最多讓 4 個 Consumer 同時工作。

2. **2 個 Consumer 閒置**：6 - 4 = 2 個 Consumer 拿不到 Partition，會一直等待。這不是錯誤，是正常的熱備份（Hot Standby）——一旦某個 Consumer 崩潰，閒置的 Consumer 會立刻接手它的 Partition。

3. **至少 6 個 Partition**：Partition 數量決定最大並行度，Partition 數 ≥ Consumer 數 才能讓每個 Consumer 都有工作。

**撮合引擎建議**：設計時 Partition 數量通常設為 Consumer 數量的整數倍（如 3 個 Consumer → 6 個 Partition），方便日後水平擴展。

</details>

---

### 練習二：程式題

請修改下方的 `MatchingEngine.java`，在撮合成功後呼叫 `TradeEventProducer` 發送事件：

```java
// MatchingEngine.java（待補完）
@Service
public class MatchingEngine {

    // TODO: 注入 TradeEventProducer
    
    public MatchResult match(Order buyOrder, Order sellOrder) {
        if (buyOrder.getPrice().compareTo(sellOrder.getPrice()) >= 0) {
            BigDecimal tradePrice = sellOrder.getPrice(); // 以賣方掛價成交
            int tradeQty = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());
            
            // TODO: 建立 TradeEvent 並發送到 Kafka
            
            return new MatchResult(true, tradePrice, tradeQty);
        }
        return new MatchResult(false, null, 0);
    }
}
```

<details>
<summary>點我看答案</summary>

```java
@Service
public class MatchingEngine {

    // 注入 Kafka 生產者
    private final TradeEventProducer tradeEventProducer;

    public MatchingEngine(TradeEventProducer tradeEventProducer) {
        this.tradeEventProducer = tradeEventProducer;
    }

    public MatchResult match(Order buyOrder, Order sellOrder) {
        if (buyOrder.getPrice().compareTo(sellOrder.getPrice()) >= 0) {
            BigDecimal tradePrice = sellOrder.getPrice();
            int tradeQty = Math.min(buyOrder.getQuantity(), sellOrder.getQuantity());

            // 建立唯一的成交 ID（UUID 保證全球唯一）
            String tradeId = UUID.randomUUID().toString();

            // 建立成交事件物件
            TradeEvent event = new TradeEvent(
                tradeId,
                buyOrder.getStockSymbol(),  // 股票代碼作為 Kafka Partition Key
                buyOrder.getOrderId(),
                sellOrder.getOrderId(),
                tradePrice,
                tradeQty
            );

            // 非同步發送到 Kafka，不阻塞撮合流程
            tradeEventProducer.sendTradeEvent(event);

            return new MatchResult(true, tradePrice, tradeQty);
        }
        return new MatchResult(false, null, 0);
    }
}
```

**重點說明**：
- `UUID.randomUUID()` 產生全球唯一 ID，確保冪等性保護能正常運作
- `buyOrder.getStockSymbol()` 作為 Partition Key，確保同一支股票的事件有序
- `sendTradeEvent` 是非同步的（CompletableFuture），不會阻塞撮合引擎

</details>

---

### 練習三：設計題

你的撮合引擎通知服務正在使用 At-Least-Once 策略，但測試時發現某些用戶收到了兩封成交通知 Email。

請回答：
1. 為什麼 At-Least-Once 會導致重複發送？
2. 如何用「冪等性」解決這個問題？請說明設計思路（不需要寫完整程式碼）。
3. 能否直接改用 Exactly-Once 解決？有什麼缺點？

<details>
<summary>點我看答案</summary>

**1. 重複發送的原因**

At-Least-Once 的工作流程是：Consumer 處理完訊息 → 提交 Offset。
但如果 Consumer 在「處理完但還沒提交 Offset」時崩潰，Kafka 不知道這條訊息已被處理，下次重啟後會從上次的 Offset 重新發送。結果是：Email 發了，但 Offset 沒有更新，重啟後又發一次。

**2. 冪等性設計思路**

核心思想：讓「重複執行同一個操作」的結果跟「執行一次」完全相同。

設計步驟：
- 每筆 TradeEvent 帶有唯一的 `tradeId`（如 UUID）
- 建立一張資料庫表 `sent_trade_notifications(trade_id, sent_at)`
- Consumer 收到事件時，先查詢這個 `tradeId` 是否已存在
  - 若存在：跳過（這是重複訊息），仍然 acknowledge
  - 若不存在：發送 Email → 寫入記錄 → acknowledge
- 這樣即使收到 100 次相同的 `tradeId`，Email 只會發送一次

**3. Exactly-Once 的缺點**

可以改用 Exactly-Once，但有以下代價：
- **效能降低**：Exactly-Once 需要 Kafka Transaction，每條訊息要進行兩階段提交（2PC），延遲增加 2～5 倍
- **實作複雜**：需要 `transactional.id`、`isolation.level=read_committed` 等額外設定
- **跨系統無效**：Exactly-Once 只保證 Kafka 內部（Producer → Kafka → Consumer 的提交），如果 Consumer 還要寫資料庫或發 Email，這些外部操作不在 Kafka 的 Transaction 保護範圍內，仍然可能重複

**結論**：At-Least-Once + 冪等設計是業界最常見的選擇，效能好、實作相對簡單，且能真正解決端到端的重複問題。Exactly-Once 適合 Kafka Streams 這類純 Kafka 內部計算的場景。

</details>
