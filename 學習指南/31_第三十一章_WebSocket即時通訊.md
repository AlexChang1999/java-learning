# 第三十一章：WebSocket 即時通訊

> 先決知識：第三章（網路基礎）、第十三章（TCP/IP 協定）、第二十二章（REST API）

---

## 本章目標

撮合引擎完成一筆成交後，前端畫面要「立刻」顯示成交價格。  
REST API 做不到「主動推送」——本章就是要解決這個問題。

讀完本章你將能夠：
1. 解釋 HTTP 輪詢的浪費，以及 WebSocket 如何解決它
2. 從 TCP 握手說明 WebSocket 連線升級的過程
3. 用 Spring Boot 整合 STOMP，廣播撮合成交結果
4. 設定心跳、處理斷線重連
5. 理解 10,000 個訂閱者時的效能陷阱

---

## 一、HTTP 的限制與 WebSocket 的誕生

### 1-1 REST API 的「問答模式」

在第二十二章，我們學到 REST API 是**請求-回應（Request-Response）**模式：

```
客戶端送出請求  →  伺服器處理  →  伺服器回應  →  連線關閉
```

這個模式非常適合「查詢資料」、「送出訂單」等場景。  
但有一個致命限制：**只有客戶端可以主動開口，伺服器永遠只能被動回答。**

撮合引擎的場景：
- 你在 09:30:01 下了一張 TSLA 買單
- 撮合引擎在 09:30:01.003（3 毫秒後）找到賣單，成交了
- 問題：**前端怎麼知道成交了？**

伺服器沒有辦法主動通知你。它只能等你來問。

---

### 1-2 輪詢（Polling）——最笨的解法

最直覺的想法：前端每隔一段時間就問一次。

```javascript
// 每秒問一次「我的訂單成交了嗎？」
setInterval(() => {
    fetch('/api/orders/status')
        .then(res => res.json())
        .then(data => {
            if (data.filled) {
                showTradeResult(data);
            }
        });
}, 1000);
```

問題在哪？

```
時間軸（每格 = 1 秒）
─────────────────────────────────────────────
客戶端：問 問 問 問 問[成交] 問 問 問 問 問
伺服器：空 空 空 空 有! 空 空 空 空 空
               ↑
            只有這一次有意義，其他 9 次全浪費
```

**數字量化浪費：**
- HTTP Request Header：~500 bytes（含 Cookie、User-Agent 等）
- HTTP Response（無成交）：~200 bytes
- 1 個使用者、每秒輪詢 = 每分鐘 ~42 KB 無效流量
- 1,000 個同時在線使用者 = 每分鐘 42 MB 無效流量

---

### 1-3 長輪詢（Long Polling）——稍微聰明但更耗資源

改進方案：客戶端送出請求後，**伺服器先不回應，等到有資料才回**。

```
客戶端：─── 送出請求 ────────────────────── 收到成交 ── 再送下一個請求
伺服器：─── 收到請求 ── 等待中... ── 有成交! 回應 ─────────────────────
                          ↑
                  連線一直開著，佔用伺服器資源
```

問題：
- 伺服器上每個等待中的請求都佔用一個 **Thread**（或至少一個連線描述符）
- 1,000 個使用者 = 1,000 個 Thread 在那邊空等
- 仍然是 HTTP 協定，每次重新建立連線都有 Header 開銷

---

### 1-4 WebSocket——正確的解法

WebSocket 的核心思想：**握手一次，連線永久雙向開通。**

```
╔══════════════════════════════════════════════════════════╗
║          三種模式比較（ASCII 圖解）                        ║
╠══════════════════════════════════════════════════════════╣
║                                                          ║
║  [輪詢 Polling]                                          ║
║  客戶端  →請求→  伺服器  （空回應）                        ║
║  客戶端  →請求→  伺服器  （空回應）                        ║
║  客戶端  →請求→  伺服器  （有資料！）                      ║
║  客戶端  →請求→  伺服器  （空回應）                        ║
║                                                          ║
║  [長輪詢 Long Polling]                                   ║
║  客戶端  →請求→  伺服器                                   ║
║               ╔══════╗  ← 連線掛起，等資料               ║
║               ╚══════╝                                   ║
║  客戶端  ←回應←  伺服器  （有資料！）                      ║
║  客戶端  →請求→  伺服器  ← 馬上再送下一個請求              ║
║                                                          ║
║  [WebSocket]                                             ║
║  客戶端  ─── HTTP Upgrade 握手 ───→  伺服器               ║
║  客戶端  ←────────────────────────  伺服器               ║
║           ↑ 雙向持久連線建立完成                           ║
║  客戶端  ←── 推送成交 ──────────  伺服器（有資料就推）      ║
║  客戶端  ←── 推送成交 ──────────  伺服器                  ║
║  客戶端  ──→ 送出訂單 ──────────→ 伺服器                  ║
║  客戶端  ←── 推送成交 ──────────  伺服器                  ║
║                                                          ║
╚══════════════════════════════════════════════════════════╝
```

---

## 二、WebSocket 協定原理（從 TCP 說起）

### 2-1 TCP 是地基（複習第十三章）

還記得第十三章說的 TCP 三次握手嗎？

```
客戶端  ──── SYN ────→  伺服器
客戶端  ←── SYN+ACK ──  伺服器
客戶端  ──── ACK ────→  伺服器
         ↑ TCP 連線建立完成
```

TCP 建立連線之後，這條「管道」就可以雙向傳輸資料。  
HTTP/1.1 在這條管道上加了「問一次答一次」的規則。  
**WebSocket 的做法：先用 HTTP 敲門，然後說「我要升級協定，從今以後不用 HTTP 格式了」。**

---

### 2-2 WebSocket Handshake（升級握手）

這是 WebSocket 連線建立的 HTTP 請求，你可以在 Chrome DevTools 的 Network 頁面看到：

```http
GET /ws HTTP/1.1
Host: localhost:8080
Upgrade: websocket          ← 告訴伺服器：我要升級成 WebSocket
Connection: Upgrade
Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==   ← 隨機產生的挑戰碼
Sec-WebSocket-Version: 13
```

伺服器同意升級，回應：

```http
HTTP/1.1 101 Switching Protocols    ← 101 = 協定切換中
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=  ← 用金鑰計算出的回應值
```

**101 之後，這條 TCP 連線就不再走 HTTP 格式了。**  
雙方可以隨時發送 WebSocket Frame（幀）。

---

### 2-3 WebSocket Frame 結構

WebSocket 資料以 **Frame（幀）** 為單位傳輸。每個 Frame 的最小 overhead 只有 **2 bytes**：

```
 0                   1
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
├─┼─┼─┼─┼─────────┼─┼─────────────
│F│R│R│R│ opcode  │M│  Payload    │
│I│S│S│S│  (4bit) │A│  Length     │
│N│V│V│V│         │S│             │
│ │1│2│3│         │K│             │
└─┴─┴─┴─┴─────────┴─┴─────────────
  ↑               ↑
  FIN: 是否最後一幀  MASK: 客戶端→伺服器必須加密
```

- Payload Length < 126 bytes：overhead = **2 bytes**
- Payload Length 126–65535 bytes：overhead = **4 bytes**
- Payload Length > 65535 bytes：overhead = **10 bytes**

**對比 HTTP Header：**

| 協定 | 每次傳輸的額外負擔 |
|------|------------------|
| HTTP/1.1 輪詢 | ~500 bytes（Header 含 Cookie 等） |
| WebSocket Frame | 2–14 bytes |
| 節省比例 | **~97% 以上** |

對撮合引擎來說，每秒可能有數千筆成交推送，這個差異非常顯著。

---

## 三、STOMP 協定（Spring WebSocket 的推薦方式）

### 3-1 裸 WebSocket 的問題

用原始 WebSocket API，你只能傳送「字串」或「bytes」：

```javascript
// 裸 WebSocket，沒有主題概念
const ws = new WebSocket('ws://localhost:8080/ws');
ws.onmessage = (event) => {
    // 所有訊息都來這裡，你自己決定怎麼分類
    const data = JSON.parse(event.data);
};
```

問題：如果有 100 種不同的資料（TSLA 成交、AAPL 成交、帳戶餘額更新...），  
你要自己在訊息裡加 `type` 欄位，然後寫一堆 `if-else` 分發。  
這等於自己重新發明了「訊息路由」。

---

### 3-2 STOMP 是什麼？

**STOMP（Simple Text Oriented Messaging Protocol）**  
是 WebSocket 之上的一層協定，提供：

1. **主題訂閱（Subscribe/Unsubscribe）**：訂閱 `/topic/trades/TSLA` 就只收 TSLA 的成交
2. **訊息發送（Send）**：`SEND /app/order` 送出下單請求
3. **確認機制（ACK/NACK）**：可以確認訊息有沒有被處理
4. **心跳（Heartbeat）**：保持連線存活

STOMP Frame 格式（純文字）：

```
SUBSCRIBE                        ← 命令
id:sub-0                         ← 訂閱 ID
destination:/topic/trades/TSLA   ← 主題

^@                               ← NULL 字元結尾
```

---

### 3-3 STOMP 訂閱流程（ASCII 圖解）

```
╔════════════════════════════════════════════════════════════════╗
║                STOMP 廣播流程                                   ║
╠════════════════════════════════════════════════════════════════╣
║                                                                ║
║  使用者A ─── SUBSCRIBE /topic/trades/TSLA ──→ Spring Broker    ║
║  使用者B ─── SUBSCRIBE /topic/trades/TSLA ──→ Spring Broker    ║
║  使用者C ─── SUBSCRIBE /topic/trades/AAPL ──→ Spring Broker    ║
║                                                                ║
║  撮合引擎成交 TSLA！                                             ║
║     ↓                                                          ║
║  SimpMessagingTemplate                                         ║
║  .convertAndSend("/topic/trades/TSLA", tradeResult)            ║
║     ↓                                                          ║
║  Spring Broker ─── 推送成交資料 ──→ 使用者A                    ║
║  Spring Broker ─── 推送成交資料 ──→ 使用者B                    ║
║  Spring Broker ─── （不推，訂閱的是 AAPL）── 使用者C           ║
║                                                                ║
╚════════════════════════════════════════════════════════════════╝
```

---

## 四、Spring Boot 整合 WebSocket + STOMP

### 4-1 加入依賴

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-websocket</artifactId>
    <!-- 已包含 spring-websocket 和 spring-messaging -->
</dependency>
```

---

### 4-2 WebSocket 設定類別

```java
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocketMessageBroker   // 啟用 WebSocket 訊息代理
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry
            .addEndpoint("/ws")         // 客戶端連線的端點 URL
            .setAllowedOriginPatterns("*")  // 允許跨域（開發用；正式環境要限制）
            .withSockJS();              // 降級支援：若瀏覽器不支援 WebSocket，改用 SockJS
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 伺服器推送訊息的前綴：/topic/xxx 代表廣播給所有訂閱者
        registry.enableSimpleBroker("/topic", "/queue");

        // 客戶端傳訊息給伺服器時，路徑要加 /app 前綴
        // 例如：客戶端送到 /app/order，會路由到 @MessageMapping("/order") 的方法
        registry.setApplicationDestinationPrefixes("/app");
    }
}
```

---

### 4-3 接收客戶端訊息（@MessageMapping）

```java
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
public class TradeController {

    // 客戶端送訊息到 /app/ping，伺服器廣播到 /topic/pong
    // 注意：路徑不含 /app 前綴（設定類別已處理）
    @MessageMapping("/ping")
    @SendTo("/topic/pong")
    public String handlePing(String message) {
        return "pong: " + message;
    }
}
```

---

### 4-4 伺服器主動推送（SimpMessagingTemplate）

這是撮合引擎最關鍵的部分——**成交後主動推給所有訂閱者**：

```java
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class MatchingEngineService {

    // 注入 Spring 的訊息發送工具
    private final SimpMessagingTemplate messagingTemplate;

    public MatchingEngineService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 撮合一筆訂單。如果成交，廣播成交結果。
     */
    public void processOrder(Order incomingOrder) {
        // ... 撮合邏輯（詳見第十四章）...

        // 假設撮合成功，產生 TradeResult
        TradeResult result = matchOrders(incomingOrder);

        if (result != null) {
            // 廣播到 /topic/trades/TSLA（只有訂閱 TSLA 的客戶端會收到）
            String destination = "/topic/trades/" + result.getSymbol();
            messagingTemplate.convertAndSend(destination, result);

            // 也可以發給特定使用者（私人通知）
            // messagingTemplate.convertAndSendToUser(
            //     result.getBuyerUsername(),
            //     "/queue/notifications",
            //     result
            // );
        }
    }
}
```

---

### 4-5 完整撮合引擎推送流程

```
╔══════════════════════════════════════════════════════════════════╗
║               完整流程圖                                          ║
╠══════════════════════════════════════════════════════════════════╣
║                                                                  ║
║  Step 1：前端建立 WebSocket 連線                                  ║
║  ─────────────────────────────────────────────────────           ║
║  Browser ─── WS Handshake (/ws) ───→ Spring Server               ║
║  Browser ─── SUBSCRIBE /topic/trades/TSLA ───→ Broker            ║
║                                                                  ║
║  Step 2：下訂單（仍用 REST API）                                  ║
║  ─────────────────────────────────────────────────────           ║
║  Browser ─── POST /api/orders {symbol:TSLA, qty:100} ─→ Server   ║
║  Server  ─── 200 OK {orderId: 42} ─────────────────→ Browser     ║
║                                                                  ║
║  Step 3：撮合引擎在背景成交                                        ║
║  ─────────────────────────────────────────────────────           ║
║  MatchingEngineService.processOrder() 找到對手方                  ║
║     ↓                                                            ║
║  SimpMessagingTemplate.convertAndSend(                           ║
║      "/topic/trades/TSLA",                                       ║
║      {price:150.25, qty:100, time:"09:30:01.003"}                ║
║  )                                                               ║
║                                                                  ║
║  Step 4：Spring Broker 推送給所有訂閱者                           ║
║  ─────────────────────────────────────────────────────           ║
║  Broker ─── PUSH {成交資料} ─→ Browser（你）                     ║
║  Broker ─── PUSH {成交資料} ─→ Browser（其他訂閱者）              ║
║                                                                  ║
║  Step 5：前端即時更新 UI                                          ║
║  ─────────────────────────────────────────────────────           ║
║  onMessage → updatePriceChart() → 畫面立即顯示新成交價            ║
║                                                                  ║
╚══════════════════════════════════════════════════════════════════╝
```

---

### 4-6 JavaScript 前端（簡易版）

```javascript
// 需要引入 SockJS 和 STOMP.js 兩個函式庫
// <script src="https://cdn.jsdelivr.net/npm/sockjs-client/dist/sockjs.min.js"></script>
// <script src="https://cdn.jsdelivr.net/npm/@stomp/stompjs/bundles/stomp.umd.min.js"></script>

const client = new StompJs.Client({
    // SockJS 提供降級支援（不支援 WebSocket 的舊瀏覽器會改用 HTTP long polling）
    webSocketFactory: () => new SockJS('http://localhost:8080/ws'),

    // 心跳設定（毫秒）：每 10 秒發一次心跳
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,

    // 連線成功後的回呼
    onConnect: (frame) => {
        console.log('WebSocket 連線成功！');

        // 訂閱 TSLA 的成交資料
        client.subscribe('/topic/trades/TSLA', (message) => {
            // message.body 是 JSON 字串，需要解析
            const trade = JSON.parse(message.body);
            console.log(`TSLA 成交！價格：${trade.price}，數量：${trade.qty}`);
            updatePriceDisplay(trade.price);   // 更新畫面
        });
    },

    // 連線失敗或斷線時的回呼
    onStompError: (frame) => {
        console.error('STOMP 錯誤：', frame.headers['message']);
    }
});

// 啟動連線（StompJs 會自動處理重連）
client.activate();
```

---

## 五、心跳（Heartbeat）與連線管理

### 5-1 為什麼需要心跳？

TCP 連線理論上可以永久存活，但現實環境中有很多「中間人」：

```
客戶端 ─── 路由器 ─── NAT 設備 ─── 防火牆 ─── 伺服器
              ↑
  NAT 表會記錄每條 TCP 連線
  如果 10 分鐘沒有流量，NAT 就會刪除這筆記錄
  之後封包就送不到了（但雙方都不知道連線已死）
```

**症狀：** 使用者打開行情頁面掛著，過 15 分鐘回來，發現成交資料沒有更新，但也沒有任何錯誤訊息。

**解法：** 心跳（Heartbeat）——定期發送一個微小的封包，讓中間設備知道「這條連線還活著」。

---

### 5-2 Spring Boot 設定心跳

```java
@Override
public void configureMessageBroker(MessageBrokerRegistry registry) {
    registry
        .enableSimpleBroker("/topic", "/queue")
        // 心跳設定：[伺服器發送間隔, 伺服器期望收到的間隔]（毫秒）
        .setHeartbeatValue(new long[]{10000, 10000});

    registry.setApplicationDestinationPrefixes("/app");
}
```

STOMP 心跳協商流程：
- 客戶端說：「我每 10 秒發一次，我希望每 10 秒收到一次」
- 伺服器說：「我每 10 秒發一次，我希望每 10 秒收到一次」
- 雙方取**最大值**：10 秒
- 結果：每 10 秒雙向各發一個心跳封包（內容是空行 `\n`）

---

### 5-3 斷線重連策略

```javascript
const client = new StompJs.Client({
    webSocketFactory: () => new SockJS('http://localhost:8080/ws'),

    // StompJs 內建重連：斷線後等 5 秒重試
    reconnectDelay: 5000,

    onConnect: (frame) => {
        console.log('連線（或重連）成功');
        // 重連後必須重新訂閱！
        subscribeToMarketData();
    },

    onDisconnect: () => {
        console.log('連線斷開，等待重連...');
        // 可以在 UI 顯示「連線中...」的提示
        showReconnectingBanner();
    }
});
```

> **注意**：重連之後要重新呼叫 `client.subscribe()`，因為舊的訂閱隨連線消失了。

---

## 六、效能考量

### 6-1 Thread-per-Connection 的問題

傳統 Servlet 模型（Spring MVC + Tomcat）：  
**每個 WebSocket 連線都需要一個 Thread 保持存活。**

```
現實計算：
─────────────────────────────────────────
JVM Thread 的預設 Stack 大小 = 512KB ~ 1MB

10,000 個同時連線的使用者訂閱 TSLA 行情：
→ 需要 10,000 個 Thread
→ 記憶體消耗：10,000 × 1MB = 10 GB（！）

實際伺服器通常只有 16~32 GB RAM，
光是 Thread Stack 就把記憶體吃光了。
─────────────────────────────────────────
```

### 6-2 Non-Blocking I/O 的解法

```
╔════════════════════════════════════════════════════════╗
║       Thread-per-Connection vs Non-Blocking I/O        ║
╠════════════════════════════════════════════════════════╣
║                                                        ║
║  傳統（Thread-per-Connection）：                        ║
║  ┌──────┐  ┌──────┐  ┌──────┐  ← 10,000 個 Thread     ║
║  │ T1   │  │ T2   │  │ T3   │    大多數在等待 I/O       ║
║  │sleep │  │sleep │  │sleep │    佔記憶體但沒做事        ║
║  └──────┘  └──────┘  └──────┘                         ║
║                                                        ║
║  Non-Blocking（Event Loop）：                           ║
║  ┌────────────────────────────┐                        ║
║  │     Event Loop Thread      │  ← 只需少數幾個 Thread  ║
║  │  處理 conn1 的資料...       │    輪流服務所有連線       ║
║  │  處理 conn2 的資料...       │    CPU 利用率高          ║
║  │  處理 conn3 的資料...       │                        ║
║  └────────────────────────────┘                        ║
║                                                        ║
╚════════════════════════════════════════════════════════╝
```

### 6-3 Spring MVC vs Spring WebFlux 的選擇

| 面向 | Spring MVC + WebSocket | Spring WebFlux |
|------|----------------------|----------------|
| 程式模型 | 同步、命令式 | 非同步、響應式 |
| 連線數上限（8GB RAM） | ~5,000 | ~100,000 |
| 學習曲線 | 低（已學過 MVC） | 高（需學 Reactor） |
| 適合場景 | 中小型系統、<1000 連線 | 高並發行情推送 |
| 與現有程式整合 | 容易 | 需要全面改寫 |

**建議（新手友善版）：**
- 先用 Spring MVC + WebSocket 做出功能
- 當同時連線超過 5,000 人、或 CPU/記憶體成為瓶頸時，再考慮遷移到 WebFlux

> 過早最佳化是萬惡之源。先讓功能跑起來，再考慮優化。

---

### 6-4 撮合引擎行情推送的實際架構

當系統真正需要擴展時，通常的做法是在 Spring 和 WebSocket 之間加入訊息佇列：

```
╔══════════════════════════════════════════════════════════════════╗
║                  生產環境行情推送架構                              ║
╠══════════════════════════════════════════════════════════════════╣
║                                                                  ║
║  MatchingEngine                                                  ║
║       │ 成交！                                                   ║
║       ↓                                                          ║
║  Kafka / Redis Pub-Sub   ← 訊息佇列（解耦合、可水平擴展）           ║
║       │                                                          ║
║       ↓                                                          ║
║  WebSocket Server 1  ──推送→  使用者 A, B, C（共 3,000 人）       ║
║  WebSocket Server 2  ──推送→  使用者 D, E, F（共 3,000 人）       ║
║  WebSocket Server 3  ──推送→  使用者 G, H, I（共 3,000 人）       ║
║                                                                  ║
║  → 水平擴展：加 Server 就能服務更多使用者                           ║
║                                                                  ║
╚══════════════════════════════════════════════════════════════════╝
```

本章聚焦在單一 Server 的實作；Kafka 整合會在後續章節介紹。

---

## 章節回顧

| 概念 | 關鍵點 |
|------|-------|
| Polling | 每次請求 ~500 bytes Header；99% 是浪費 |
| WebSocket | 2-byte Frame overhead；握手一次永久雙向 |
| STOMP | WebSocket 上的訊息協定；支援主題訂閱 |
| @MessageMapping | 接收客戶端傳來的訊息 |
| SimpMessagingTemplate | 伺服器主動推送到主題 |
| 心跳 | 防止 NAT/防火牆切斷閒置連線 |
| Thread 問題 | 10,000 連線 × 1MB stack = 10GB；需 Non-blocking |

---

## 六、WebSocket Frame 格式深度解析 <!-- 💡 進階 -->

### Frame 結構（RFC 6455）

WebSocket 並不是直接傳字串，而是用固定格式的 **Frame（訊框）** 包裝資料：

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
├─┬─┬─┬─┬─────────┬─┬─────────────────────────────────────────────┤
│F│R│R│R│  Opcode │M│          Payload Length                      │
│I│S│S│S│  (4bit) │A│             (7/16/64 bit)                    │
│N│V│V│V│         │S│                                              │
│ │1│2│3│         │K│                                              │
├─┴─┴─┴─┴─────────┼─┴──────────────────────────────────────────────┤
│                  │    Masking Key (32bit, 若 MASK=1)              │
├──────────────────┴────────────────────────────────────────────────┤
│                       Payload Data                                │
└───────────────────────────────────────────────────────────────────┘

FIN  = 1 表示這是訊息的最後一個 Fragment（分片）
Opcode：
  0x0 = Continuation（接續前一個分片）
  0x1 = Text（UTF-8 文字）
  0x2 = Binary（二進制）
  0x8 = Close（關閉連線）
  0x9 = Ping（心跳請求）
  0xA = Pong（心跳回應）
MASK = 1 表示 Payload 被 Masking Key XOR 過（客戶端到伺服器必須 Mask）
```

### 為什麼客戶端必須 Mask？

```
安全考量（防止 Cache Poisoning Attack）：
  早期發現：若不 Mask，惡意 JavaScript 可以偽造 HTTP Response 格式，
  騙過中間的代理伺服器（proxy），讓 proxy 把惡意資料快取起來。

硬體開銷：
  Mask 操作 = 每個 byte XOR 4-byte key 的某一個 byte
  對於 1KB 訊息 = 1024 次 XOR 運算
  現代 CPU：1 ns / XOR → 1024 × 1 ns ≈ 1μs（幾乎可以忽略）
```

### Binary vs Text Frame

```java
// Text Frame（常見，但 JSON 序列化有開銷）
session.sendMessage(new TextMessage("{\"type\":\"ORDER_BOOK\",\"price\":100.0}"));

// Binary Frame（適合高頻撮合推送，減少序列化開銷）
// 例如用 Protobuf 序列化
OrderBookUpdate update = OrderBookUpdate.newBuilder()
    .setPrice(100_000L)    // 整數避免浮點誤差，單位：分
    .setQuantity(500L)
    .setAction(Action.ADD)
    .build();
session.sendMessage(new BinaryMessage(update.toByteArray()));

// Protobuf vs JSON 效能對比：
//   JSON：{"price":100.0,"quantity":500,"action":"ADD"} = ~45 bytes
//   Protobuf：同樣資料 ≈ 8 bytes（大約省 80%，解析速度快 5-10 倍）
```

---

## 七、多節點廣播：Redis Pub/Sub <!-- 🔴 資深 -->

### 問題：為什麼單節點 WebSocket 不夠？

```
沒有 Redis Pub/Sub 的問題：

用戶 A 的 WebSocket 連線在 Pod-1
用戶 B 的 WebSocket 連線在 Pod-2
訂單撮合發生在 Pod-1

Pod-1 可以推給用戶 A ✅
Pod-1 無法直接推給在 Pod-2 上的用戶 B ❌

即使用 SimpMessagingTemplate 廣播，
訊息只會發給「連到這個 Pod」的用戶！
```

### 解法：Redis Pub/Sub 作為 Message Broker

```
撮合結果
    │
    ↓
[Pod-1] PUBLISH channel "trade_event" message
    │
    ↓  Redis Pub/Sub 廣播
    ├─────────────────────────┐
    ↓                         ↓
[Pod-1 訂閱者]           [Pod-2 訂閱者]
  接收訊息                  接收訊息
  推給本節點的 WS 用戶      推給本節點的 WS 用戶
```

### 實作：Spring Boot + Redis Pub/Sub + WebSocket

```java
// 1. 設定 Redis Message Listener
@Configuration
public class RedisPubSubConfig {

    @Bean
    public MessageListenerAdapter tradeListener(TradeEventHandler handler) {
        // handleMessage 是 TradeEventHandler 中接收訊息的方法名稱
        return new MessageListenerAdapter(handler, "handleMessage");
    }

    @Bean
    public RedisMessageListenerContainer container(
            RedisConnectionFactory factory,
            MessageListenerAdapter tradeListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        // 訂閱 "trade-events" channel
        container.addMessageListener(tradeListener, new PatternTopic("trade-events"));
        return container;
    }
}

// 2. 接收 Redis 訊息，推送給 WebSocket 客戶端
@Component
public class TradeEventHandler {

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 每當 Redis "trade-events" channel 有訊息，這個方法就被呼叫
     * 不管訊息是哪個 Pod 發佈的！
     */
    public void handleMessage(String message) {
        TradeEvent event = parseJson(message, TradeEvent.class);
        // 廣播給訂閱這個 topic 的所有 WebSocket 客戶端（本節點上的）
        messagingTemplate.convertAndSend("/topic/trades/" + event.getSymbol(), event);
    }
}

// 3. 撮合完成後，發佈到 Redis
@Service
public class MatchingService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    public void publishTradeResult(TradeEvent event) {
        String json = toJson(event);
        // PUBLISH 廣播到所有訂閱的 Pod
        redisTemplate.convertAndSend("trade-events", json);
    }
}
```

### Redis Pub/Sub 的限制

```
⚠️ 注意事項：
  1. 訊息不持久化：Redis Pub/Sub 是 Fire-and-Forget
     若 Pod 在收到訊息前崩潰，訊息遺失
     → 若需要可靠投遞，改用 Redis Streams 或 Kafka

  2. 無法查詢歷史訊息
     → 客戶端重連後需主動拉取最新狀態

  3. 高頻場景下 Redis Pub/Sub 會成為瓶頸
     → 行情推送頻率 > 10,000/s 時考慮 Kafka

💡 撮合引擎建議：
  - 低頻（成交回報）：Redis Pub/Sub 足夠，延遲 < 1ms
  - 高頻（每隔 100ms 的行情快照）：Redis Pub/Sub 可行
  - 超高頻（tick-by-tick 逐筆行情）：考慮 Kafka + 前端 WebSocket 分離
```

---

## 八、Backpressure 背壓控制 <!-- 🔴 資深 -->

### 什麼是 Backpressure？

```
問題：生產者（撮合引擎）產生訊息的速度 > 消費者（客戶端）能接收的速度

                    ╔════════════╗
  撮合引擎  ──────→ ║ WebSocket  ║ ──────→  客戶端（手機，網速慢）
  10,000 msg/s     ║  Buffer    ║          能處理 100 msg/s
                    ╚════════════╝
                        ↓
                    Buffer 滿 → OOM 或訊息堆積
```

### Spring WebSocket 的 Backpressure 設定

```java
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue")
            // 設定 outbound channel 的 buffer 大小
            // 超過這個大小會丟棄訊息（而非無限堆積導致 OOM）
            .setTaskScheduler(heartbeatScheduler())
            .setCacheLimit(1024);  // 預設 1024 訊息
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry
            .setMessageSizeLimit(64 * 1024)        // 單個訊息最大 64KB
            .setSendBufferSizeLimit(512 * 1024)     // 每個 session 的發送 buffer 512KB
            .setSendTimeLimit(20_000);              // 20 秒送不出去就斷線
    }
}
```

### 應用層 Backpressure：訊息合併（Throttling）

```java
/**
 * 行情推送節流：不管撮合多快，客戶端最多每 100ms 收到一次更新
 * 思路：用一個定時任務，每 100ms 抓最新快照推送，而非每次成交都推
 */
@Component
public class MarketDataThrottler {

    // 用 ConcurrentHashMap 存每個 symbol 的「最新行情」（只保留最新）
    private final Map<String, OrderBookSnapshot> latestSnapshots = new ConcurrentHashMap<>();

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    // 撮合每次更新只更新 Map，不直接推送
    public void updateSnapshot(String symbol, OrderBookSnapshot snapshot) {
        latestSnapshots.put(symbol, snapshot);  // 新的快照覆蓋舊的
    }

    // 每 100ms 統一推送一次（即使撮合發生了 1000 次，客戶端只收到 1 次）
    @Scheduled(fixedRate = 100)
    public void flushSnapshots() {
        latestSnapshots.forEach((symbol, snapshot) -> {
            messagingTemplate.convertAndSend("/topic/orderbook/" + symbol, snapshot);
        });
        latestSnapshots.clear();
    }
}
```

---

## 九、Spring WebFlux 響應式 WebSocket <!-- 🔴 資深 -->

### 為什麼需要 WebFlux？

```
傳統 Spring MVC + WebSocket（Servlet-based）：
  每個 WebSocket 連線需要 1 個 Thread 維持
  10,000 連線 × 1MB stack = 10GB RAM
  線程切換成本：~5-30μs × 頻繁切換 = 高延遲

Spring WebFlux（Reactive, Netty-based）：
  使用 Event Loop（類似 Node.js）
  1 個 CPU core ≈ 1 個 Event Loop Thread 處理所有 IO
  10,000 連線 × 同一個 Event Loop = 共用少量 Thread
  IO 等待時不佔用 Thread，延遲更低

適用時機：
  - 連線數 > 10,000，且訊息大多是 IO bound（等待資料）
  - 需要背壓（Reactive Streams 原生支援背壓）
```

### WebFlux WebSocket Handler

```java
@Component
public class ReactiveOrderBookHandler implements WebSocketHandler {

    @Autowired
    private OrderBookService orderBookService;

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        // Flux.interval 每 100ms 發射一個事件
        Flux<WebSocketMessage> outbound = Flux.interval(Duration.ofMillis(100))
            .map(tick -> {
                // 每次 tick 取最新行情
                OrderBookSnapshot snapshot = orderBookService.getLatestSnapshot("BTC/USD");
                return session.textMessage(toJson(snapshot));
            })
            // 背壓策略：超過 16 個 buffer 就丟棄最舊的（而非堆積）
            .onBackpressureLatest();  // 只保留最新的，其餘丟棄

        // 接收客戶端訊息（例如訂閱不同的 symbol）
        Flux<String> inbound = session.receive()
            .map(WebSocketMessage::getPayloadAsText)
            .doOnNext(msg -> handleSubscription(session.getId(), msg))
            .thenMany(Flux.empty());

        // merge：同時處理 inbound 和 outbound
        return session.send(outbound).and(inbound);
    }
}

// 路由設定
@Bean
public RouterFunction<ServerResponse> wsRoute(ReactiveOrderBookHandler handler) {
    return RouterFunctions.route()
        .GET("/ws/orderbook", request ->
            ServerResponse.ok().build(/* HandshakeWebSocketService */))
        .build();
}
```

---

## 十、WebSocket 安全性 <!-- 💡 進階 -->

### 認證（Authentication）

```java
/**
 * 問題：WebSocket 握手是 HTTP，之後就不再是 HTTP 了，
 * 所以 Spring Security 的 HTTP filter 在握手後不再生效。
 * 解法：在握手階段驗證 JWT Token。
 */
@Configuration
public class WebSocketSecurityConfig extends AbstractSecurityWebSocketMessageBrokerConfigurer {

    @Override
    protected void configureInbound(MessageSecurityMetadataSourceRegistry messages) {
        messages
            .nullDestMatcher().authenticated()           // 連線本身需要認證
            .simpSubscribeDestMatchers("/topic/**").authenticated()  // 訂閱需要認證
            .simpDestMatchers("/app/**").authenticated() // 發送訊息需要認證
            .anyMessage().denyAll();                     // 其餘全拒絕
    }

    @Override
    protected boolean sameOriginDisabled() {
        return true; // 若前端和後端不同 origin，需要關閉 CSRF 保護
    }
}

// 客戶端連線時帶上 JWT（在 query string 或 header）
// ws://your-server/ws?token=eyJhbGciOiJIUzI1NiJ9...
@Component
public class JwtHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        // 從 URL 參數取得 token
        String token = getTokenFromRequest(request);
        if (token != null && jwtService.validateToken(token)) {
            // 把解析出來的用戶資訊存入 WebSocket session attributes
            attributes.put("userId", jwtService.getUserId(token));
            return true;   // 允許握手
        }
        return false;  // 拒絕握手
    }
}
```

---

## 練習題

<details>
<summary>練習一：解釋 WebSocket 如何從 HTTP 升級（基礎）</summary>

**問題：**  
一個同學說「WebSocket 和 HTTP 完全不同，是另一種協定」。  
請用 TCP、HTTP 101、Upgrade Header 等關鍵字，說明他說的對不對，以及 WebSocket 連線的完整建立過程。

**解答：**

這個說法**半對半錯**。

**同：** WebSocket 的第一步確實是一個標準的 HTTP GET 請求，走在 TCP 之上。

**異：** 一旦伺服器回應 `101 Switching Protocols`，這條 TCP 連線就**不再使用 HTTP 格式**，改用 WebSocket Frame 格式傳輸資料。

完整過程：
1. 客戶端向 `/ws` 發送 HTTP GET，附上 `Upgrade: websocket` 和 `Sec-WebSocket-Key`
2. 伺服器回應 `HTTP/1.1 101 Switching Protocols`，附上 `Sec-WebSocket-Accept`
3. 101 之後，**同一條 TCP 連線**切換到 WebSocket 協定
4. 雙方可以隨時互相傳送 WebSocket Frame，不需要再等對方問

所以 WebSocket 是「**從 HTTP 升級而來，建立在 TCP 之上的雙向協定**」，不是憑空出現的全新協定。

</details>

---

<details>
<summary>練習二：為撮合引擎新增「個人成交通知」（實作）</summary>

**問題：**  
目前 `/topic/trades/TSLA` 是廣播給**所有人**。  
如果我只想讓**掛單成交的那個使用者**收到私人通知（例如「你的 100 股 TSLA 以 150.25 成交了！」），應該怎麼改？

提示：查看 `SimpMessagingTemplate.convertAndSendToUser()`

**解答：**

使用 `convertAndSendToUser()` 發送私人訊息：

```java
// MatchingEngineService.java
private final SimpMessagingTemplate messagingTemplate;

public void processOrder(Order order) {
    TradeResult result = matchOrders(order);

    if (result != null) {
        // 1. 廣播成交（所有人看得到行情）
        messagingTemplate.convertAndSend(
            "/topic/trades/" + result.getSymbol(),
            result
        );

        // 2. 私人通知買方（只有 buyerUsername 收到）
        messagingTemplate.convertAndSendToUser(
            result.getBuyerUsername(),   // Spring Security 的 principal name
            "/queue/my-trades",          // 客戶端訂閱路徑會自動加上使用者前綴
            result
        );
    }
}
```

前端訂閱私人頻道（注意路徑前綴是 `/user`）：

```javascript
// convertAndSendToUser 會推送到 /user/{username}/queue/my-trades
// 但客戶端只需訂閱 /user/queue/my-trades（Spring 自動加上當前使用者）
client.subscribe('/user/queue/my-trades', (message) => {
    const trade = JSON.parse(message.body);
    alert(`成交通知：你的 ${trade.qty} 股 ${trade.symbol} 以 ${trade.price} 成交！`);
});
```

這樣的架構：行情廣播（/topic）給所有人看，成交通知（/queue）只給本人。

</details>

---

<details>
<summary>練習三：計算記憶體需求，決定是否需要 WebFlux（效能）</summary>

**問題：**  
你的撮合引擎預計服務 **2,000 個同時在線的使用者**，每人都透過 WebSocket 訂閱行情。  
伺服器是 **8 GB RAM**，JVM heap 設定為 **4 GB**（`-Xmx4g`）。

使用 Spring MVC（每個連線一個 Thread，stack 大小 512 KB）：
1. 2,000 個 Thread 需要多少 stack 記憶體？
2. 加上 JVM heap，總共需要多少 RAM？
3. 8 GB 的伺服器夠用嗎？需要升級到 WebFlux 嗎？

**解答：**

**計算：**

```
Thread Stack 記憶體：
2,000 個 Thread × 512 KB = 1,000,000 KB = ~976 MB ≈ 1 GB

總記憶體需求：
JVM Heap：  4 GB
Thread Stack：1 GB
JVM 本身 + Metaspace + OS：約 1 GB
────────────────────────────
合計：      ~6 GB
```

**結論：8 GB 伺服器，6 GB 使用，還有 2 GB 緩衝。夠用！**

**這個規模不需要 WebFlux。**

升級到 WebFlux 的時機參考：
- 同時連線超過 **10,000** 人
- 或 CPU 使用率長期超過 80%（Context Switch 太頻繁）
- 或 Thread Pool 出現 `Thread starvation` 警告

目前 2,000 人的規模，用 Spring MVC 加上適當的 Thread Pool 設定（`server.tomcat.threads.max=3000`）就足夠了。過早切換 WebFlux 只會增加程式碼複雜度，得不償失。

</details>

---

## 延伸閱讀

- RFC 6455：WebSocket 協定規範（可直接看 Section 1 的概述）
- STOMP 1.2 規範：https://stomp.github.io/stomp-specification-1.2.html
- Spring 官方文件：[WebSockets](https://docs.spring.io/spring-framework/docs/current/reference/html/web.html#websocket)
- 下一章預告：**第三十二章 — 訊息佇列與 Kafka**（解決多台 WebSocket 伺服器之間的訊息同步問題）
