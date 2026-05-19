# 第五十五章：gRPC 深入實作

## 前言：為什麼微服務需要 gRPC？

REST API 的問題：
- **效能**：HTTP/1.1 文字協定（JSON），解析開銷大；每次請求都要建立連線
- **型別不安全**：`{"amount": "999.99"}` 是字串還是數字？靠文件約定，不靠編譯器
- **沒有規範**：每個服務的 REST API 風格都不一樣

**gRPC** 的解法：
- 使用 **Protocol Buffers（Protobuf）** 定義嚴格型別的 API 契約（`.proto` 檔）
- 基於 **HTTP/2**：多路復用（同一個連線處理多個請求）、Header 壓縮、雙向流
- **自動生成**程式碼：從 `.proto` 生成 Java Client/Server，再也不怕打字錯誤

---

## 一、Protocol Buffers：API 契約

```protobuf
// src/main/proto/order.proto

syntax = "proto3";

package com.example.order;

option java_package = "com.example.grpc";
option java_outer_classname = "OrderProto";
option java_multiple_files = true;

// 請求/回應訊息定義
message CreateOrderRequest {
  string customer_id = 1;    // 每個欄位都有唯一的 field number（編號不能改！）
  string product_id = 2;
  int32 quantity = 3;
  double price = 4;
}

message OrderResponse {
  string order_id = 1;
  string status = 2;
  double total_amount = 3;
  int64 created_at_ms = 4;   // Unix timestamp（毫秒）
}

message GetOrderRequest {
  string order_id = 1;
}

message OrderListResponse {
  repeated OrderResponse orders = 1;  // repeated = List
}

// 服務定義（4 種 RPC 模式）
service OrderService {
  // 一元 RPC（最常用）：一個請求，一個回應
  rpc CreateOrder(CreateOrderRequest) returns (OrderResponse);

  // 伺服器串流：一個請求，多個回應（如即時訂單狀態更新）
  rpc WatchOrder(GetOrderRequest) returns (stream OrderResponse);

  // 客戶端串流：多個請求，一個回應（如批次下單）
  rpc BatchCreateOrders(stream CreateOrderRequest) returns (OrderListResponse);

  // 雙向串流：多個請求，多個回應（如即時撮合）
  rpc StreamTrade(stream CreateOrderRequest) returns (stream OrderResponse);
}
```

---

## 二、Spring Boot 整合 gRPC

```xml
<!-- pom.xml -->
<dependency>
    <groupId>net.devh</groupId>
    <artifactId>grpc-server-spring-boot-starter</artifactId>
    <version>3.1.0.RELEASE</version>
</dependency>
<dependency>
    <groupId>net.devh</groupId>
    <artifactId>grpc-client-spring-boot-starter</artifactId>
    <version>3.1.0.RELEASE</version>
</dependency>

<!-- Protobuf Maven Plugin：編譯 .proto 生成 Java 程式碼 -->
<plugin>
    <groupId>com.github.os72</groupId>
    <artifactId>protoc-jar-maven-plugin</artifactId>
    <executions>
        <execution>
            <goals><goal>run</goal></goals>
        </execution>
    </executions>
</plugin>
```

```yaml
# application.yml（Server 端）
grpc:
  server:
    port: 9090         # gRPC 用獨立 Port（HTTP/2）
    security:
      enabled: false   # 開發環境關閉 TLS（生產要開）
```

---

## 三、Server 端：實作 gRPC 服務

```java
import net.devh.boot.grpc.server.service.GrpcService;
import io.grpc.stub.StreamObserver;

// @GrpcService 替代 @Service，並向 gRPC Server 註冊
@GrpcService
public class OrderGrpcService extends OrderServiceGrpc.OrderServiceImplBase {
    // OrderServiceGrpc 是從 .proto 自動生成的基底類別

    private final OrderRepository orderRepository;

    // 一元 RPC
    @Override
    public void createOrder(CreateOrderRequest request,
                            StreamObserver<OrderResponse> responseObserver) {
        try {
            // 業務邏輯
            Order order = Order.builder()
                .customerId(request.getCustomerId())
                .productId(request.getProductId())
                .quantity(request.getQuantity())
                .price(request.getPrice())
                .build();
            order = orderRepository.save(order);

            // 建立 Protobuf 回應物件
            OrderResponse response = OrderResponse.newBuilder()
                .setOrderId(order.getId())
                .setStatus(order.getStatus().name())
                .setTotalAmount(order.getTotalAmount())
                .setCreatedAtMs(order.getCreatedAt().toInstant().toEpochMilli())
                .build();

            responseObserver.onNext(response);     // 發送回應
            responseObserver.onCompleted();         // 完成
        } catch (Exception e) {
            // gRPC 的錯誤處理：回傳 Status 碼
            responseObserver.onError(
                Status.INTERNAL
                    .withDescription("訂單建立失敗: " + e.getMessage())
                    .withCause(e)
                    .asRuntimeException()
            );
        }
    }

    // 伺服器串流 RPC：持續推送訂單狀態更新
    @Override
    public void watchOrder(GetOrderRequest request,
                           StreamObserver<OrderResponse> responseObserver) {
        String orderId = request.getOrderId();

        // 訂閱訂單狀態變更事件
        orderEventEmitter.subscribe(orderId, event -> {
            if (responseObserver.isReady()) {  // 檢查客戶端還在連線
                OrderResponse update = OrderResponse.newBuilder()
                    .setOrderId(orderId)
                    .setStatus(event.getNewStatus())
                    .build();
                responseObserver.onNext(update);  // 每次狀態變更都推送
            }
        });

        // 訂單完成後才 complete（之前一直推送）
        orderEventEmitter.onOrderFinished(orderId, () -> {
            responseObserver.onCompleted();
        });
    }
}
```

---

## 四、Client 端：呼叫 gRPC 服務

```yaml
# application.yml（Client 端，如 API Gateway 或其他 Service）
grpc:
  client:
    order-service:                       # 自訂的連線名稱
      address: static://localhost:9090   # 服務地址（生產環境用服務發現）
      negotiation-type: plaintext        # 開發環境不加密
```

```java
import net.devh.boot.grpc.client.inject.GrpcClient;

@Service
public class OrderFacadeService {

    @GrpcClient("order-service")         // 對應 yml 中的名稱
    private OrderServiceGrpc.OrderServiceBlockingStub orderStub;
    // BlockingStub：同步阻塞呼叫（最簡單）

    @GrpcClient("order-service")
    private OrderServiceGrpc.OrderServiceStub asyncOrderStub;
    // 非同步 Stub：用於串流 RPC

    // 一元呼叫（同步）
    public OrderDTO createOrder(CreateOrderDTO dto) {
        CreateOrderRequest request = CreateOrderRequest.newBuilder()
            .setCustomerId(dto.getCustomerId())
            .setProductId(dto.getProductId())
            .setQuantity(dto.getQuantity())
            .setPrice(dto.getPrice())
            .build();

        try {
            OrderResponse response = orderStub
                .withDeadlineAfter(3, TimeUnit.SECONDS)  // 設定超時
                .createOrder(request);

            return OrderDTO.from(response);
        } catch (StatusRuntimeException e) {
            // gRPC 錯誤碼處理
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                throw new OrderNotFoundException();
            }
            throw new ServiceException("訂單服務不可用", e);
        }
    }

    // 訂閱訂單串流（非同步）
    public void watchOrder(String orderId, Consumer<OrderResponse> onUpdate) {
        GetOrderRequest request = GetOrderRequest.newBuilder()
            .setOrderId(orderId)
            .build();

        asyncOrderStub.watchOrder(request, new StreamObserver<OrderResponse>() {
            @Override
            public void onNext(OrderResponse response) {
                onUpdate.accept(response);  // 收到每一筆更新
            }

            @Override
            public void onError(Throwable t) {
                log.error("訂單串流錯誤", t);
            }

            @Override
            public void onCompleted() {
                log.info("訂單 {} 追蹤完成", orderId);
            }
        });
    }
}
```

---

## 五、攔截器（Interceptor）：gRPC 的 AOP <!-- 💡 進階 -->

類似 Spring MVC 的 Filter，gRPC 攔截器可以在所有 RPC 呼叫前後插入邏輯：

```java
// Server 端攔截器：JWT 驗證
@GrpcGlobalServerInterceptor  // 自動應用到所有 gRPC 服務
public class JwtServerInterceptor implements ServerInterceptor {

    private static final Metadata.Key<String> AUTH_KEY =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
        ServerCall<ReqT, RespT> call,
        Metadata headers,
        ServerCallHandler<ReqT, RespT> next) {

        String authHeader = headers.get(AUTH_KEY);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            call.close(Status.UNAUTHENTICATED.withDescription("需要 JWT Token"), headers);
            return new ServerCall.Listener<>() {};
        }

        String token = authHeader.substring(7);
        try {
            Claims claims = jwtUtil.parseClaims(token);
            // 把用戶資訊放入 gRPC Context（類似 MDC）
            Context ctx = Context.current().withValue(USER_CONTEXT_KEY, claims.getSubject());
            return Contexts.interceptCall(ctx, call, headers, next);
        } catch (JwtException e) {
            call.close(Status.UNAUTHENTICATED.withDescription("Token 無效"), headers);
            return new ServerCall.Listener<>() {};
        }
    }
}

// Client 端攔截器：自動帶 JWT Token
@GrpcGlobalClientInterceptor
public class JwtClientInterceptor implements ClientInterceptor {

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
        MethodDescriptor<ReqT, RespT> method,
        CallOptions callOptions,
        Channel next) {

        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                // 每個請求自動加 Authorization Header
                String token = tokenStorage.getCurrentToken();
                headers.put(AUTH_KEY, "Bearer " + token);
                super.start(responseListener, headers);
            }
        };
    }
}
```

---

## 六、gRPC vs REST 選型

| 維度 | REST (HTTP/1.1 + JSON) | gRPC (HTTP/2 + Protobuf) |
|------|----------------------|--------------------------|
| 效能 | 較低（文字解析）| 高（二進位，壓縮率 5-10x）|
| 型別安全 | 靠文件約定 | 編譯時強型別 |
| 語言支援 | 任何語言 | 支援 10+ 語言的生成工具 |
| 瀏覽器支援 | 完整 | 需要 gRPC-Web 轉換層 |
| 除錯難度 | 低（curl/Postman）| 中（需要 grpcurl 或 GUI 工具）|
| 串流支援 | Server-Sent Events（單向）| 雙向串流 |
| 適合場景 | 對外 Public API、瀏覽器存取 | 微服務內部通信、高效能場景 |

**最佳實踐**：對外（前端/第三方）用 REST，服務間內部通信用 gRPC。

---

## 本章練習題

**Q1：Protobuf 的 field number 為什麼不能隨意修改？**
<details>
<summary>答案</summary>
Protobuf 序列化用 field number（不是欄位名稱）來識別欄位。如果你把 field number 1 從 customer_id 改成 product_id，舊版本的 Client 解析到 field 1 時會把 product_id 的值當成 customer_id 來用，造成數據錯亂。欄位名稱可以改（只影響生成的程式碼，不影響序列化格式），但 field number 絕對不能改。
</details>

**Q2：gRPC 的 BlockingStub 和 AsyncStub 各適合什麼情況？**
<details>
<summary>答案</summary>
BlockingStub（同步阻塞）：等待 Server 回應再繼續，程式碼簡單直覺。適合一元 RPC（單請求單回應），是大多數服務間呼叫的首選。AsyncStub（非同步）：呼叫後立刻返回，透過 StreamObserver 回調接收結果。必須用在任何串流 RPC（Server Stream、Client Stream、Bidirectional Stream），因為串流本質上是持續的非同步事件流。
</details>

**Q3：如果 gRPC Server 要做 A/B 測試，把 10% 流量路由到新版本，怎麼做？**
<details>
<summary>答案</summary>
在 Client 端的 gRPC 攔截器裡加入路由邏輯：讀取當前用戶的 feature flag（或按 userId 雜湊），決定呼叫哪個 Server address。或者在 API Gateway 層（如 Istio/Envoy）設定流量分割規則，不需要修改應用程式碼。Istio 支援對 HTTP/2 流量（包括 gRPC）設定 VirtualService 的 weight（權重），10% 路由到 v2，90% 路由到 v1。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 57 章 | Phase 9：微服務與分散式架構
> 下一章（第 58 章）：[第四十一章：分散式系統設計原則](41_第四十一章_分散式系統設計原則.md)
