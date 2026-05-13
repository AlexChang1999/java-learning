# 第三十二章：Docker 與容器化

> **前置知識**：建議先讀過第二十四章（作業系統基礎）與第二十六章（Maven/Gradle）。  
> **核心專案連結**：本章最後會用 docker-compose 一鍵啟動撮合引擎的完整開發環境。

---

## 一、為什麼需要 Docker？從「在我電腦上可以跑」說起

### 1.1 傳統部署的痛苦

你在本機寫好了撮合引擎，一切測試通過。上線部署時卻發現：

```
Exception in thread "main" java.lang.UnsupportedClassVersionError:
  MatchingEngine has been compiled by a more recent version of Java runtime
  (class file version 65.0), this version recognizes up to class file
  version 55.0
```

原因：你本機用 **Java 21**，伺服器裝的是 **Java 11**。  
這只是最常見的問題之一。實務上還有：

| 問題類型 | 範例 |
|---|---|
| JDK 版本不同 | 開發用 Java 21，伺服器是 Java 11 |
| 作業系統差異 | 你在 Windows 開發，伺服器是 CentOS |
| 函式庫版本衝突 | 本機 glibc 2.35，伺服器 glibc 2.17 |
| 環境變數設定遺漏 | `JAVA_HOME` 沒設好 |
| 依賴服務版本不符 | 本機 PostgreSQL 16，正式環境 PostgreSQL 13 |

過去解決這個問題的方案是 **Virtual Machine（VM）**。但 VM 有自己的問題。

---

### 1.2 VM vs Container：一定要看懂這張圖

**Virtual Machine（虛擬機）架構：**

```
┌─────────────────────────────────────────────────────┐
│                   Host 實體機器                       │
│  ┌─────────────────────────────────────────────────┐ │
│  │              Host OS（例如 Ubuntu）               │ │
│  │  ┌─────────────────────────────────────────────┐ │ │
│  │  │          Hypervisor（VMware / KVM）           │ │ │
│  │  │                                              │ │ │
│  │  │  ┌──────────────┐  ┌──────────────┐         │ │ │
│  │  │  │   VM 1        │  │   VM 2        │         │ │ │
│  │  │  │ ┌──────────┐  │  │ ┌──────────┐  │         │ │ │
│  │  │  │ │ Guest OS  │  │  │ │ Guest OS  │  │         │ │ │
│  │  │  │ │ (Ubuntu)  │  │  │ │ (CentOS)  │  │         │ │ │
│  │  │  │ ├──────────┤  │  │ ├──────────┤  │         │ │ │
│  │  │  │ │  App A   │  │  │ │  App B   │  │         │ │ │
│  │  │  │ │(Java 21) │  │  │ │(Node.js) │  │         │ │ │
│  │  │  │ └──────────┘  │  │ └──────────┘  │         │ │ │
│  │  │  └──────────────┘  └──────────────┘         │ │ │
│  │  └─────────────────────────────────────────────┘ │ │
│  └─────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘

每個 VM：完整 Guest OS（幾個 GB）+ 應用程式
啟動時間：幾分鐘（要開機）
```

**Container 架構：**

```
┌─────────────────────────────────────────────────────┐
│                   Host 實體機器                       │
│  ┌─────────────────────────────────────────────────┐ │
│  │          Host OS（Linux Kernel）                  │ │
│  │  ┌─────────────────────────────────────────────┐ │ │
│  │  │           Container Runtime（Docker）         │ │ │
│  │  │                                              │ │ │
│  │  │  ┌─────────────┐  ┌─────────────┐           │ │ │
│  │  │  │ Container 1  │  │ Container 2  │           │ │ │
│  │  │  │ ┌─────────┐  │  │ ┌─────────┐  │           │ │ │
│  │  │  │ │  App A   │  │  │ │  App B   │  │           │ │ │
│  │  │  │ │(Java 21) │  │  │ │(Node.js) │  │           │ │ │
│  │  │  │ │ 依賴函式庫 │  │  │ │ 依賴函式庫 │  │           │ │ │
│  │  │  │ └─────────┘  │  │ └─────────┘  │           │ │ │
│  │  │  │  共用 Kernel  │  │  共用 Kernel  │           │ │ │
│  │  │  └─────────────┘  └─────────────┘           │ │ │
│  │  └─────────────────────────────────────────────┘ │ │
│  └─────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘

每個 Container：只有應用程式 + 必要函式庫（幾百 MB）
啟動時間：幾秒（不用開機，直接啟動 Process）
```

| 比較項目 | VM | Container |
|---|---|---|
| OS | 完整 Guest OS（幾個 GB） | 共用 Host Kernel，無 Guest OS |
| 啟動時間 | 幾分鐘 | 幾秒 |
| 磁碟空間 | 數 GB | 數百 MB |
| 隔離程度 | 完全隔離（連 Kernel 都不同） | Process 層級隔離 |
| 適合場景 | 需要不同 OS、強安全隔離 | 微服務、CI/CD、快速部署 |

---

### 1.3 底層技術：Linux Namespace 與 cgroups

Container 看起來很神奇，其實底層只用了兩個 Linux 核心功能：

**Namespace（命名空間）— 做隔離**

Namespace 讓每個 Container 以為自己有獨立的系統資源：

```
Namespace 類型    → 隔離的資源
─────────────────────────────────────────
PID namespace   → Process ID（容器看不到 Host 的其他 Process）
NET namespace   → 網路介面、IP、Port（每個容器有自己的網路）
MNT namespace   → 檔案系統掛載點（容器有自己的根目錄 /）
UTS namespace   → Hostname（容器可以有自己的主機名稱）
IPC namespace   → 跨 Process 通訊（訊號、訊息佇列）
USER namespace  → 使用者 ID 對應
```

**cgroups（Control Groups）— 做資源限制**

Namespace 讓容器「看不到」外面，cgroups 讓容器「用不超過」配額：

```java
// 概念上類似這樣（實際是 kernel 功能，不是 Java 程式碼）
//
// /sys/fs/cgroup/memory/my-container/memory.limit_in_bytes = 512MB
// /sys/fs/cgroup/cpu/my-container/cpu.quota_us           = 50%
//
// → 這個 Container 最多用 512MB 記憶體、50% CPU
```

Docker 就是把這兩個 Linux 功能包裝成友善的命令列工具。  
這也是為什麼 **Docker 在 Windows/macOS 上需要跑一個輕量 Linux VM**（WSL2 或 HyperKit）— Container 依賴 Linux Kernel。

---

### 1.4 Docker 的承諾：Build once, run anywhere

```
開發者的 Mac            CI 伺服器（Linux）      正式伺服器（Linux）
      │                       │                        │
  docker build            docker pull             docker pull
      │                       │                        │
  image:v1.0  ──push──► Registry  ──pull──────►  image:v1.0
      │                                               │
  docker run                                     docker run
      │                                               │
 「可以跑」                                        「一定也可以跑」
```

---

## 二、核心概念

### 2.1 Image、Container、Dockerfile、Registry

用 Java 的比喻來理解：

| Docker 概念 | Java 比喻 | 說明 |
|---|---|---|
| **Image** | Class（類別） | 唯讀的模板，描述應用程式長什麼樣子 |
| **Container** | Object（物件） | Image 的執行實例，可以有很多個 |
| **Dockerfile** | 原始碼（.java 檔） | 描述如何建立 Image 的腳本 |
| **Registry** | Maven Central | 存放和發布 Image 的倉庫 |

```java
// Java 概念
class MatchingEngine { ... }           // 定義類別（像 Image）
MatchingEngine app = new MatchingEngine(); // 建立實例（像 Container）

// Docker 概念
// matching-engine:1.0（Image）
// docker run matching-engine:1.0（建立 Container）
```

### 2.2 Image Layer 架構

Image 不是一個完整的大檔案，而是由多層 Layer 疊加而成。

```
┌─────────────────────────────────┐
│       Layer 4：COPY 應用程式      │  ← 只有你的程式碼（幾 MB）
├─────────────────────────────────┤
│    Layer 3：RUN mvn package      │  ← Maven 建置產物
├─────────────────────────────────┤
│  Layer 2：COPY pom.xml + 依賴    │  ← Maven 依賴（幾百 MB）
├─────────────────────────────────┤
│  Layer 1：FROM eclipse-temurin   │  ← JRE 基底（幾百 MB）
└─────────────────────────────────┘
         ↑ 每條 Dockerfile 指令 = 一層 Layer
```

**為什麼這樣設計？** 快取！

```
第一次 build：全部 4 層都要建（慢）

修改程式碼後第二次 build：
  Layer 1：沒變動 → 從快取讀取 ✓（快）
  Layer 2：沒變動 → 從快取讀取 ✓（快）
  Layer 3：沒變動 → 從快取讀取 ✓（快）
  Layer 4：程式碼改了 → 重新建置（只有這層）
```

這就是為什麼 Dockerfile 要把「不常變動的」指令放在前面——充分利用快取，加速建置。

---

## 三、Dockerfile 詳解（以撮合引擎為例）

### 3.1 多階段建置（Multi-stage Build）

直接用撮合引擎的 `Dockerfile` 來學習每個指令：

```dockerfile
# ============================================================
# Stage 1：Build 階段
# 使用包含 Maven + JDK 的完整建置環境
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS builder

# 設定容器內的工作目錄（相當於 cd /app）
# 後續的 COPY / RUN 指令都以此為基準
WORKDIR /app

# 優先複製 pom.xml，利用 Layer 快取
# 只要 pom.xml 沒改，Maven 依賴就不用重新下載
COPY pom.xml .

# 下載所有 Maven 依賴到本地快取（-B = batch 模式，不互動）
RUN mvn dependency:go-offline -B

# 複製原始碼（分開複製是為了快取 pom.xml 那一層）
COPY src ./src

# 執行建置，跳過測試（測試應在 CI 流程中另外跑）
RUN mvn package -DskipTests -B

# ============================================================
# Stage 2：Runtime 階段
# 使用精簡的 JRE 映像（不含 JDK、Maven、原始碼）
# Alpine Linux 版本體積更小（約 200MB vs 400MB）
# ============================================================
FROM eclipse-temurin:21-jre-alpine

# 建立非 root 使用者，遵循最小權限原則
# 容器預設以 root 跑是安全風險
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# 設定工作目錄
WORKDIR /app

# 從 builder 階段複製編譯好的 JAR（只複製最終產物）
# 原始碼、Maven 工具、.m2 快取都不會進入最終 Image
COPY --from=builder /app/target/matching-engine-*.jar app.jar

# 切換到非 root 使用者
USER appuser

# 聲明容器監聽的 Port（只是文件，不會自動對外開放）
EXPOSE 8080

# 定義容器啟動指令
# ENTRYPOINT 是「主要命令」，CMD 是「預設參數」
# 使用 exec 格式（JSON array），而非 shell 格式，確保訊號正確傳遞
ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-jar", "app.jar"]
```

### 3.2 各指令說明一覽

| 指令 | 用途 | 範例 |
|---|---|---|
| `FROM` | 指定基底 Image | `FROM eclipse-temurin:21-jre-alpine` |
| `WORKDIR` | 設定工作目錄（自動建立） | `WORKDIR /app` |
| `COPY` | 從 Host 或其他 Stage 複製檔案 | `COPY src ./src` |
| `RUN` | 建置時執行命令（產生新 Layer） | `RUN mvn package` |
| `EXPOSE` | 聲明容器監聽的 Port（文件用） | `EXPOSE 8080` |
| `ENV` | 設定環境變數 | `ENV SPRING_PROFILES_ACTIVE=prod` |
| `ENTRYPOINT` | 容器主程式（不易被覆蓋） | `ENTRYPOINT ["java", "-jar", "app.jar"]` |
| `CMD` | 預設參數（可被 `docker run` 覆蓋） | `CMD ["--spring.profiles.active=dev"]` |
| `ARG` | 建置時參數（`docker build --build-arg`） | `ARG APP_VERSION=1.0` |

### 3.3 為什麼要多階段建置？

```
沒有多階段：
  最終 Image 包含 JDK + Maven + 原始碼 + target/ + .m2/
  大小：約 800MB～1.2GB
  安全問題：原始碼洩漏、攻擊面增大

使用多階段：
  最終 Image 只有 JRE + 一個 JAR 檔
  大小：約 200MB～300MB
  原始碼留在 builder 中間層，不進最終 Image
```

---

## 四、常用 Docker 指令速查

### 4.1 建置與執行

```bash
# 建置 Image（. 表示 Dockerfile 所在目錄，-t 指定名稱:標籤）
docker build -t matching-engine:1.0 .

# 建置時指定 Dockerfile 路徑
docker build -f docker/Dockerfile -t matching-engine:1.0 .

# 執行 Container
docker run matching-engine:1.0

# 背景執行（detached mode）
docker run -d matching-engine:1.0

# 端口映射（Host:8888 → Container:8080）
docker run -d -p 8888:8080 matching-engine:1.0

# 傳入環境變數
docker run -d -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/matching \
  -e SPRING_DATASOURCE_PASSWORD=secret \
  matching-engine:1.0

# 掛載目錄（Host 目錄:Container 目錄）
docker run -d -v /host/logs:/app/logs matching-engine:1.0

# 指定容器名稱（方便後續操作）
docker run -d --name my-engine -p 8080:8080 matching-engine:1.0
```

### 4.2 管理 Container

```bash
# 列出執行中的 Container
docker ps

# 列出所有 Container（含已停止的）
docker ps -a

# 停止 Container（送 SIGTERM，等 10 秒後強制 SIGKILL）
docker stop my-engine

# 強制停止（直接 SIGKILL）
docker kill my-engine

# 刪除已停止的 Container
docker rm my-engine

# 停止並刪除
docker rm -f my-engine

# 查看 Container 即時日誌
docker logs my-engine

# 追蹤日誌（類似 tail -f）
docker logs -f my-engine

# 進入執行中的 Container 互動（debug 用）
docker exec -it my-engine /bin/sh
```

### 4.3 管理 Image

```bash
# 列出本機所有 Image
docker image ls

# 從 Registry 下載 Image
docker pull eclipse-temurin:21-jre-alpine

# 推送 Image 到 Registry
docker push your-repo/matching-engine:1.0

# 刪除 Image
docker rmi matching-engine:1.0

# 刪除所有未使用的 Image（釋放空間）
docker image prune

# 查看 Image 的 Layer 結構
docker image history matching-engine:1.0
```

### 4.4 網路與資源

```bash
# 查看 Container 資源使用（類似 top）
docker stats

# 查看 Container 詳細資訊（IP、掛載、環境變數等）
docker inspect my-engine

# 建立自訂網路（讓 Container 之間用名稱互通）
docker network create my-network

# 在指定網路中執行
docker run -d --network my-network --name db postgres:16
```

---

## 五、docker-compose：多服務編排

### 5.1 為什麼需要 docker-compose？

撮合引擎不是獨立運作的，它需要：
- PostgreSQL（儲存訂單、成交紀錄）
- Redis（快取行情、Session）
- Kafka + ZooKeeper（接收下單訊息、發送成交事件）

如果用 `docker run` 一個一個啟動，要記住多個設定，還要手動管理啟動順序和網路。

`docker-compose` 讓你用一個 YAML 檔描述整個環境，一個命令啟動全部。

### 5.2 完整 docker-compose.yml（撮合引擎開發環境）

```yaml
# docker-compose.yml
# 撮合引擎完整開發環境
# 使用方式：docker compose up -d

services:

  # ──────────────────────────────────────────
  # 撮合引擎主程式
  # ──────────────────────────────────────────
  matching-engine:
    build:
      context: .
      dockerfile: Dockerfile
    container_name: matching-engine
    ports:
      - "8080:8080"         # Spring Boot REST API
    environment:
      # Spring 設定（覆蓋 application.properties）
      SPRING_PROFILES_ACTIVE: docker
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/matching_db
      SPRING_DATASOURCE_USERNAME: matching_user
      SPRING_DATASOURCE_PASSWORD: matching_pass
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: 6379
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      # JVM 記憶體（由 Container 限制決定，見下方 deploy）
      JAVA_OPTS: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
    deploy:
      resources:
        limits:
          memory: 512m      # Container 記憶體上限
          cpus: "1.0"
    depends_on:
      postgres:
        condition: service_healthy   # 等 PostgreSQL 健康才啟動
      redis:
        condition: service_healthy
      kafka:
        condition: service_started
    networks:
      - matching-network
    restart: unless-stopped

  # ──────────────────────────────────────────
  # PostgreSQL 資料庫
  # ──────────────────────────────────────────
  postgres:
    image: postgres:16-alpine
    container_name: matching-postgres
    ports:
      - "5432:5432"         # 對外暴露，方便用 DBeaver 連線
    environment:
      POSTGRES_DB: matching_db
      POSTGRES_USER: matching_user
      POSTGRES_PASSWORD: matching_pass
    volumes:
      # 掛載 Named Volume，Container 刪除後資料仍保留
      - postgres-data:/var/lib/postgresql/data
      # 初始化 SQL（第一次啟動自動執行）
      - ./sql/init.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U matching_user -d matching_db"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - matching-network

  # ──────────────────────────────────────────
  # Redis 快取
  # ──────────────────────────────────────────
  redis:
    image: redis:7-alpine
    container_name: matching-redis
    ports:
      - "6379:6379"
    command: redis-server --appendonly yes  # 開啟持久化
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - matching-network

  # ──────────────────────────────────────────
  # ZooKeeper（Kafka 的協調服務）
  # ──────────────────────────────────────────
  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    container_name: matching-zookeeper
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    networks:
      - matching-network

  # ──────────────────────────────────────────
  # Kafka 訊息佇列
  # ──────────────────────────────────────────
  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: matching-kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      # PLAINTEXT_HOST 供 Host 機器連線，PLAINTEXT 供容器間通訊
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:29092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"
    depends_on:
      - zookeeper
    networks:
      - matching-network

# ──────────────────────────────────────────
# Named Volumes（資料卷）
# 讓資料在 Container 重啟後仍然存在
# ──────────────────────────────────────────
volumes:
  postgres-data:
  redis-data:

# ──────────────────────────────────────────
# 自訂網路
# 同一 Network 內的 Container 可以用服務名稱互通
# 例如：jdbc:postgresql://postgres:5432
# 而不用寫 IP（IP 每次可能不同）
# ──────────────────────────────────────────
networks:
  matching-network:
    driver: bridge
```

### 5.3 docker-compose 常用指令

```bash
# 啟動所有服務（-d = 背景執行）
docker compose up -d

# 只啟動特定服務
docker compose up -d postgres redis

# 停止所有服務（Container 停止但不刪除）
docker compose stop

# 停止並刪除 Container（Volume 預設保留）
docker compose down

# 停止並刪除 Container + Volume（資料全清，謹慎使用）
docker compose down -v

# 查看所有服務狀態
docker compose ps

# 查看某服務的日誌
docker compose logs -f matching-engine

# 重新建置並啟動（程式碼改了要加 --build）
docker compose up -d --build matching-engine

# 在執行中的服務執行命令
docker compose exec postgres psql -U matching_user -d matching_db
```

### 5.4 depends_on 的限制

```yaml
# ⚠️ 這樣寫「Container 啟動了」就往下，不保證服務已就緒
depends_on:
  - postgres   # 只等 postgres Container 啟動

# ✅ 這樣寫才是等 healthcheck 通過（服務真的可以接受連線）
depends_on:
  postgres:
    condition: service_healthy
```

Spring Boot 連線 DB 如果 DB 還沒就緒，會拋出異常。  
建議：healthcheck + `condition: service_healthy`，或是設定 Spring Boot 的連線重試。

---

## 六、Container 內的 JVM 注意事項

### 6.1 舊版 JVM 的坑：不認識 Container 限制

假設你給 Container 設定 `memory: 512m`，但：

```
Java 8u191 之前的行為：

  Host 機器：16 GB RAM
  Container 限制：512 MB

  JVM 預設 Heap = Host 記憶體的 1/4
                = 16 GB × 25%
                = 4 GB ← 超過 Container 限制！

  結果：Container 被 OOM Killer 強制殺掉
```

這是因為舊版 JVM 直接讀取 Host 的 `/proc/meminfo`，看不到 cgroups 的限制。

### 6.2 現代 JVM 的正確設定

```dockerfile
ENTRYPOINT ["java", \
            # Java 10+ 預設開啟，Java 8u191+ 需手動加
            # 讓 JVM 讀取 cgroups 的 CPU/記憶體限制
            "-XX:+UseContainerSupport", \
            \
            # 使用 Container 記憶體的 75% 作為 Heap 上限
            # 剩餘 25% 留給 Metaspace、Thread Stack、Direct Buffer 等
            "-XX:MaxRAMPercentage=75.0", \
            \
            "-jar", "app.jar"]
```

### 6.3 為什麼不直接寫 `-Xmx512m`？

```
情境：你在 K8s 部署，不同環境給不同記憶體配額

  開發環境：Container 限制 256m → -Xmx512m 就爆了
  測試環境：Container 限制 1g   → -Xmx512m 浪費記憶體
  正式環境：Container 限制 2g   → -Xmx512m 浪費記憶體

使用 MaxRAMPercentage=75.0：
  開發環境：256m × 75% = 192m Heap
  測試環境：1g   × 75% = 768m Heap
  正式環境：2g   × 75% = 1.5g  Heap
  → 自動適應，無需修改 Dockerfile
```

### 6.4 建議的 JVM 啟動參數組合

```dockerfile
ENTRYPOINT ["java", \
            # Container 記憶體感知
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            \
            # GC 選擇（Java 21 預設 G1GC，低延遲場景可考慮 ZGC）
            "-XX:+UseZGC", \
            \
            # 在容器崩潰時輸出 Heap Dump，方便事後分析
            "-XX:+HeapDumpOnOutOfMemoryError", \
            "-XX:HeapDumpPath=/app/logs/heapdump.hprof", \
            \
            # JVM 崩潰時輸出詳細日誌
            "-XX:ErrorFile=/app/logs/hs_err.log", \
            \
            "-jar", "app.jar"]
```

---

## 七、常見問題排查

### Q1：容器明明啟動了，但 HTTP 連不上？

```bash
# 確認 Port 映射正確
docker ps
# 看 PORTS 欄位：0.0.0.0:8080->8080/tcp ← 正確
#               8080/tcp                ← 沒有對外映射！

# 確認容器內服務有在監聽
docker exec -it my-engine netstat -tlnp
# 或
docker exec -it my-engine curl localhost:8080/actuator/health
```

### Q2：修改了程式碼，但容器跑的還是舊版？

```bash
# 必須重新 build Image
docker compose up -d --build matching-engine
# 或者明確 build 後再 run
docker build -t matching-engine:latest .
docker compose up -d
```

### Q3：容器啟動後馬上停止？

```bash
# 查看容器日誌找原因
docker logs my-engine

# 常見原因：
# 1. 程式在啟動時拋出 Exception（看 stack trace）
# 2. 依賴的服務（DB）還沒就緒
# 3. Port 衝突（另一個 Container 或 Host 程式佔用了）
# 4. 記憶體不足（Container 被 OOM Killer 殺掉）
```

---

## 本章重點整理

```
Docker 的核心價值
  └─ 解決「在我機器上可以跑」的問題
  └─ 封裝應用程式 + 所有依賴 → Image
  └─ Image 在任何環境執行 → Container

底層技術
  ├─ Linux Namespace → 隔離 Process、網路、檔案系統
  └─ cgroups → 限制 CPU、記憶體使用

三個核心操作
  ├─ docker build → 把 Dockerfile 變成 Image
  ├─ docker run   → 從 Image 建立 Container
  └─ docker compose up → 一鍵啟動多服務環境

JVM 容器化要點
  ├─ -XX:+UseContainerSupport（讀取 cgroups 限制）
  └─ -XX:MaxRAMPercentage=75.0（比例配置比固定值更靈活）
```

---

## 練習題

<details>
<summary>練習 1：建立撮合引擎的 Image 並驗證</summary>

**題目**：  
假設你的撮合引擎專案在 `~/matching-engine/`，請完成以下步驟：  
1. 在專案根目錄建立 `Dockerfile`（使用本章的多階段建置範例）  
2. 執行 `docker build` 建置 Image  
3. 用 `docker run` 啟動，並確認 `http://localhost:8080/actuator/health` 回傳 `{"status":"UP"}`  

**參考答案**：

```bash
# 1. 進入專案目錄
cd ~/matching-engine

# 2. 建置 Image（-t 指定 name:tag，. 表示當前目錄）
docker build -t matching-engine:local .

# 3. 確認 Image 建好了
docker image ls matching-engine

# 4. 啟動 Container
docker run -d \
  --name test-engine \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=docker \
  matching-engine:local

# 5. 查看啟動日誌
docker logs -f test-engine

# 6. 測試健康檢查
curl http://localhost:8080/actuator/health

# 7. 清理
docker rm -f test-engine
```

**解題關鍵**：  
- `SPRING_PROFILES_ACTIVE=docker` 讓 Spring Boot 讀取 `application-docker.properties`，其中 DB URL 指向容器名稱（`postgres`）  
- 如果單獨啟動 matching-engine 沒有 DB，Spring Boot 會在啟動時報錯——這時應該用 `docker compose up` 整套環境一起啟動

</details>

---

<details>
<summary>練習 2：分析 Image 大小，找出優化空間</summary>

**題目**：  
下面有兩個 Dockerfile，請回答：  
（a）哪個最終 Image 比較小？為什麼？  
（b）哪個建置速度比較快（在依賴沒改的情況下）？為什麼？  

```dockerfile
# Dockerfile A
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY . .
RUN mvn package -DskipTests
ENTRYPOINT ["java", "-jar", "target/app.jar"]
```

```dockerfile
# Dockerfile B
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**參考答案**：

（a）**Dockerfile B 更小**：  
- A 的最終 Image 包含完整 JDK（非 JRE）、所有原始碼、target/ 目錄、Maven 快取（`~/.m2`）  
- B 使用多階段建置，最終 Image 只有 JRE（Alpine，精簡版）+ 一個 JAR 檔  
- 差距通常在 3～5 倍（A 約 700MB～1GB，B 約 150MB～250MB）

（b）**Dockerfile B 的增量建置更快**：  
- B 把 `COPY pom.xml` 和 `RUN mvn dependency:go-offline` 分開成獨立 Layer  
- 只要 `pom.xml` 沒改，Maven 依賴那一層就從快取讀取，不用重新下載  
- A 的 `COPY . .` 會把所有檔案複製進去，任何一個檔案修改都導致後續 Layer 快取失效

</details>

---

<details>
<summary>練習 3：Container JVM 記憶體設定計算</summary>

**題目**：  
你的撮合引擎部署在 K8s，Container 的記憶體限制設定如下：

```yaml
resources:
  limits:
    memory: "768Mi"   # 768 MB
```

JVM 啟動參數為：
```
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
```

請回答：  
（a）JVM 最大 Heap 是多少？  
（b）剩下的記憶體（非 Heap）用在哪些地方？  
（c）如果直接寫 `-Xmx700m` 會有什麼問題？  

**參考答案**：

（a）**最大 Heap = 768MB × 75% = 576MB**

（b）剩餘 192MB（768 - 576）分配給：  
- **Metaspace**：儲存類別的 Metadata（反射、動態代理會增加這個）  
- **Thread Stack**：每個執行緒預設 256KB～512KB，100 個執行緒約 25～50MB  
- **Direct ByteBuffer**：Netty / NIO 使用的堆外記憶體  
- **JVM 本身的 Native 記憶體**：JIT 編譯程式碼、GC 資料結構  

（c）`-Xmx700m` 的問題：  
- 700MB（Heap）+ 上述非 Heap 開銷（可能超過 100MB）= 總使用量可能超過 768MB 限制  
- Container 超過記憶體限制時，Linux OOM Killer 會直接殺掉 Container  
- 日誌裡看到的是 `Killed` 或 `Exit Code 137`，不是 Java 的 `OutOfMemoryError`  
- 使用 `MaxRAMPercentage` 保留緩衝空間，就能避免這個問題

</details>
