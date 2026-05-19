# 第六十八章：Netty 網路框架深入

## 前言：為什麼需要 Netty？

```
Java 原生 NIO 的問題：
  ❌ API 複雜難用（Selector、SelectionKey、ByteBuffer...）
  ❌ ByteBuffer 容易用錯（position/limit 切換容易出 bug）
  ❌ 需要自己處理粘包/拆包
  ❌ 需要自己管理執行緒模型
  ❌ 沒有內建的心跳、重連、TLS 支援

Netty 的解法：
  ✅ 統一的 Channel API（封裝 NIO 的複雜性）
  ✅ ByteBuf（比 ByteBuffer 更好用，引用計數自動記憶體管理）
  ✅ Pipeline + Handler（鏈式處理，每個 Handler 只做一件事）
  ✅ 內建編解碼器（HTTP、WebSocket、Protobuf...）
  ✅ 高效能：Java 中最快的網路框架，Kafka/gRPC/ElasticSearch 內部都用它

使用場景：
  - 自訂高效能 TCP 協定（遊戲、HFT）
  - 實作 HTTP Server / Proxy
  - 長連線推送服務（IM、即時行情）
  - RPC 框架的通訊層（Dubbo、gRPC for Java 底層）
```

---

## 一、Netty 核心架構

```
執行緒模型（Reactor Pattern）：

  Boss EventLoopGroup（1 個執行緒）
       ↓ accept 新連線
  Worker EventLoopGroup（N 個執行緒，N = CPU 核數 × 2）
       ↓ 每個 EventLoop 負責 M 個 Channel 的所有 IO
       ↓
  Channel → ChannelPipeline
               ↓
        [ChannelHandler1]   (解碼：bytes → Java 物件)
               ↓
        [ChannelHandler2]   (業務邏輯)
               ↓
        [ChannelHandler3]   (編碼：Java 物件 → bytes)
               ↓
             寫出

每個 Channel 只由一個 EventLoop 執行緒處理（無鎖設計）
Pipeline 中的 Handler 是鏈式的，資料流可以向前傳遞或向後傳遞
```

---

## 二、最小可用的 Echo Server

```java
// 啟動 Netty Server
public class EchoServer {

    public static void main(String[] args) throws Exception {
        // Boss：接受新連線
        EventLoopGroup bossGroup = new NioEventLoopGroup(1);
        // Worker：處理已建立連線的 IO
        EventLoopGroup workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                // 設定 Server Socket 選項
                .option(ChannelOption.SO_BACKLOG, 128)
                // 設定每個 Channel 的選項
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)     // 關閉 Nagle 演算法（低延遲）
                // 每個新連線的 Pipeline 設定
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                            // 粘包/拆包處理：按換行符分割消息
                            .addLast(new LineBasedFrameDecoder(1024))
                            // 字串編解碼
                            .addLast(new StringDecoder(CharsetUtil.UTF_8))
                            .addLast(new StringEncoder(CharsetUtil.UTF_8))
                            // 業務 Handler
                            .addLast(new EchoServerHandler());
                    }
                });

            ChannelFuture future = bootstrap.bind(8888).sync();
            log.info("Server 啟動在 :8888");
            future.channel().closeFuture().sync();  // 等待 Channel 關閉
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }
}

// 業務 Handler（無狀態，可被多個 Channel 共享）
@ChannelHandler.Sharable
public class EchoServerHandler extends SimpleChannelInboundHandler<String> {

    // 收到消息時觸發
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
        log.info("收到：{}", msg);
        // 回寫給客戶端
        ctx.writeAndFlush(msg + "\n");
    }

    // 新連線建立時觸發
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("新連線：{}", ctx.channel().remoteAddress());
        ctx.writeAndFlush("歡迎連線！\n");
    }

    // 連線斷開時觸發
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("連線斷開：{}", ctx.channel().remoteAddress());
    }

    // 異常處理
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("連線異常", cause);
        ctx.close();
    }
}
```

---

## 三、粘包 / 拆包問題

TCP 是流協定，不保證消息邊界，必須在應用層定義分包規則：

```java
// 常用的分包方案（加入 Pipeline 的第一個 Handler）：

// 方案 1：固定長度（每條消息都是 1024 bytes）
ch.pipeline().addLast(new FixedLengthFrameDecoder(1024));

// 方案 2：換行符分割（HTTP 報頭、FTP 命令等）
ch.pipeline().addLast(new LineBasedFrameDecoder(8192));

// 方案 3：分隔符分割（自訂分隔符）
ByteBuf delimiter = Unpooled.copiedBuffer("$$", CharsetUtil.UTF_8);
ch.pipeline().addLast(new DelimiterBasedFrameDecoder(8192, delimiter));

// 方案 4：長度前綴（最常用！）
// 消息格式：[4 bytes 長度][N bytes 內容]
ch.pipeline().addLast(new LengthFieldBasedFrameDecoder(
    1024 * 1024,  // maxFrameLength
    0,            // lengthFieldOffset（長度欄位在消息的第幾個 byte 開始）
    4,            // lengthFieldLength（長度欄位佔幾個 byte）
    0,            // lengthAdjustment
    4             // initialBytesToStrip（讀出後跳過幾個 byte，4 = 跳過長度欄位本身）
));
ch.pipeline().addLast(new LengthFieldPrepender(4));  // 寫出時自動加 4 bytes 長度頭
```

---

## 四、自訂協定：即時行情推送

```java
// 定義消息格式（Protobuf 或自訂二進位協定）
@Data
public class MarketDataMessage {
    private byte messageType;     // 1=行情, 2=心跳, 3=訂閱
    private String symbol;        // 股票代碼
    private BigDecimal price;     // 最新價
    private long timestamp;       // 時間戳
}

// 自訂編碼器（Java 物件 → bytes）
public class MarketDataEncoder extends MessageToByteEncoder<MarketDataMessage> {
    @Override
    protected void encode(ChannelHandlerContext ctx,
                          MarketDataMessage msg, ByteBuf out) {
        out.writeByte(msg.getMessageType());
        byte[] symbolBytes = msg.getSymbol().getBytes(CharsetUtil.UTF_8);
        out.writeShort(symbolBytes.length);    // 2 bytes 長度
        out.writeBytes(symbolBytes);           // 字串內容
        out.writeLong(msg.getPrice().unscaledValue().longValue());
        out.writeLong(msg.getTimestamp());
    }
}

// 自訂解碼器（bytes → Java 物件）
public class MarketDataDecoder extends ByteToMessageDecoder {
    @Override
    protected void decode(ChannelHandlerContext ctx,
                          ByteBuf in, List<Object> out) {
        // 確保至少有 3 bytes（messageType + symbolLength）
        if (in.readableBytes() < 3) return;

        in.markReaderIndex();   // 標記位置（資料不夠時可以回退）
        byte type = in.readByte();
        short symbolLen = in.readShort();

        // 確保有足夠的資料
        if (in.readableBytes() < symbolLen + 16) {
            in.resetReaderIndex();   // 回退，等更多資料
            return;
        }

        byte[] symbolBytes = new byte[symbolLen];
        in.readBytes(symbolBytes);
        String symbol = new String(symbolBytes, CharsetUtil.UTF_8);
        long priceUnscaled = in.readLong();
        long timestamp = in.readLong();

        out.add(new MarketDataMessage(type, symbol,
            BigDecimal.valueOf(priceUnscaled, 2), timestamp));
    }
}

// 業務 Handler：管理訂閱關係並推送行情
@ChannelHandler.Sharable
public class MarketDataHandler extends SimpleChannelInboundHandler<MarketDataMessage> {

    // 管理所有訂閱了某股票的 Channel
    private static final Map<String, Set<Channel>> subscriptions = new ConcurrentHashMap<>();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, MarketDataMessage msg) {
        if (msg.getMessageType() == 3) {  // 訂閱請求
            subscriptions.computeIfAbsent(msg.getSymbol(), k -> ConcurrentHashMap.newKeySet())
                         .add(ctx.channel());
            log.info("{} 訂閱了 {}", ctx.channel().remoteAddress(), msg.getSymbol());
        }
    }

    // 向所有訂閱了某股票的客戶端推送行情
    public static void broadcast(String symbol, MarketDataMessage data) {
        Set<Channel> channels = subscriptions.getOrDefault(symbol, Collections.emptySet());
        channels.removeIf(ch -> !ch.isActive());  // 清除已斷線的
        channels.forEach(ch -> ch.writeAndFlush(data));
    }
}
```

---

## 五、心跳機制（長連線保活）<!-- 💡 進階 -->

```java
// Pipeline 加入心跳偵測
ch.pipeline()
    .addLast(new IdleStateHandler(
        60,    // readerIdleTime：60 秒沒有讀到資料，觸發 READER_IDLE 事件
        30,    // writerIdleTime：30 秒沒有寫出資料，觸發 WRITER_IDLE 事件
        0,     // allIdleTime
        TimeUnit.SECONDS
    ))
    .addLast(new HeartbeatHandler());

// 心跳 Handler
public class HeartbeatHandler extends ChannelInboundHandlerAdapter {

    private static final ByteBuf HEARTBEAT = Unpooled.unreleasableBuffer(
        Unpooled.copiedBuffer("PING", CharsetUtil.UTF_8)
    );

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent event) {
            if (event.state() == IdleState.WRITER_IDLE) {
                // 30 秒沒寫資料，主動發心跳
                ctx.writeAndFlush(HEARTBEAT.duplicate());
                log.debug("發送心跳：{}", ctx.channel().remoteAddress());
            } else if (event.state() == IdleState.READER_IDLE) {
                // 60 秒沒收到任何資料（包括心跳回應），斷線
                log.warn("心跳超時，斷線：{}", ctx.channel().remoteAddress());
                ctx.close();
            }
        }
    }
}
```

---

## 六、Netty vs Spring WebFlux 選型

| 維度 | Netty（直接用）| Spring WebFlux（基於 Netty）|
|------|-------------|---------------------------|
| **抽象層級** | 低（直接操作 Channel/ByteBuf）| 高（Reactive Stream）|
| **學習曲線** | 陡（需要理解 NIO 和執行緒模型）| 中（Reactor 思維）|
| **自訂協定** | ✅ 完全控制（TCP 二進位）| ❌ 主要是 HTTP/WebSocket |
| **HTTP 服務** | 需要自己處理 | ✅ 直接用 @RestController |
| **適合場景** | 自訂協定、HFT、遊戲、RPC | HTTP API、SSE 推送 |

**建議**：除非需要自訂 TCP 協定，否則用 Spring WebFlux 就夠了，Netty 留給底層框架開發者。

---

## 本章練習題

**Q1：Netty 的 EventLoop 執行緒模型如何避免鎖競爭？**
<details>
<summary>答案</summary>
Netty 的核心設計是：每個 Channel 固定只由一個 EventLoop 執行緒處理所有 IO 事件（accept、read、write）。這意味著同一個 Channel 的所有操作都在同一個執行緒執行，完全不需要鎖。不同 Channel 由不同 EventLoop 執行緒處理，彼此隔離。這是 Reactor 模式的精髓：用「任務隔離」代替「鎖同步」。唯一需要注意的是：在業務 Handler 裡不能做耗時的阻塞操作（如資料庫查詢），否則這個 EventLoop 執行緒的所有 Channel 都會被阻塞。耗時操作要放到獨立的業務執行緒池處理。
</details>

**Q2：為什麼說 ByteBuf 比 Java 原生 ByteBuffer 更好用？**
<details>
<summary>答案</summary>
Java ByteBuffer 的讀寫共用一個 position 指針，讀寫切換時必須手動呼叫 flip()，忘了就讀到錯誤位置，是常見的 Bug 來源。ByteBuf 有分開的 readerIndex 和 writerIndex，讀寫互不影響，不需要 flip()。此外 ByteBuf 支援引用計數（ReferenceCounting）：多個地方引用同一個 ByteBuf 時，只有引用計數歸零才真正釋放記憶體，避免了過早釋放和記憶體洩漏。ByteBuf 還支援 Slice（零拷貝切片）和 Composite（邏輯合并多個 buffer），在處理大量網路數據時性能更好。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 64 章 | Phase 9：微服務與分散式架構
> 下一章（第 65 章）：[第五十章：響應式程式設計（WebFlux）](50_第五十章_響應式程式設計WebFlux.md)
