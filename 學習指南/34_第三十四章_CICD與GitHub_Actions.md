# 第三十四章：CI/CD 與 GitHub Actions

> **前置章節**：第十六章（TDD / JUnit）、第二十六章（Maven/Gradle）、第三十二章（Docker）
> **核心專案**：撮合引擎（java-learning @ https://github.com/AlexChang1999/java-learning）

---

## 一、沒有 CI/CD 的世界長什麼樣？

想像你和兩位同學一起開發撮合引擎。大家在各自的分支上寫程式，
星期五下班前要合併到 `main`，然後部署到測試伺服器。

**你的一天：**

```
09:00  小明的 PR merge 進來
       你 git pull，發現本機跑不起來
09:30  找到問題：小明改了 OrderBook 的 constructor 但忘了更新你的測試
10:00  修好了，跑一次 mvn test
       輸出捲了好幾百行……測試通過
10:15  準備把自己的 PR merge 進去
       老闆說「先部署一版讓 QA 看一下」
10:30  ssh 進伺服器，手動 mvn package，複製 jar 檔，重啟服務
       服務起不來，因為你忘了把設定檔一起更新
11:00  終於部署完了，QA 說「怎麼剛才測好的功能消失了？」
       ……
```

這就是沒有自動化流程的日常：**手動、容易出錯、沒有一致的標準**。

CI/CD 就是要把這些痛點全部自動化。

---

## 二、CI/CD 是什麼？

### 2.1 CI — Continuous Integration（持續整合）

**核心概念**：每次有人 `git push`，系統就**自動**跑一次測試。

- 讓問題在「剛剛提交」的當下就被發現，而不是三天後合併時
- 與第十六章的 TDD 呼應：你寫了測試，CI 就幫你自動執行
- 所有人的程式碼都通過同一套測試，保證品質標準一致

### 2.2 CD — Continuous Delivery / Deployment（持續交付 / 持續部署）

**持續交付（Delivery）**：測試通過後，自動把程式打包成可以部署的產物（例如 JAR 檔或 Docker image）。

**持續部署（Deployment）**：更進一步，自動把打包好的產物部署到伺服器上。

---

### 2.3 整體流程圖

```
開發者電腦                  GitHub                    伺服器
    │                          │                         │
    │  git push                │                         │
    │ ─────────────────────►  │                         │
    │                          │ 觸發 CI                 │
    │                          │  ↓                      │
    │                          │ 下載程式碼              │
    │                          │  ↓                      │
    │                          │ 執行 mvn test           │
    │                          │  ↓                      │
    │                          │ 測試通過？              │
    │                          │  ↓                      │
    │                          │ mvn package             │
    │                          │ 打包 JAR / Docker       │
    │                          │  ↓                      │
    │                          │ CD 觸發部署  ──────────►│
    │                          │                         │ 重啟服務
    │                          │                         │
    │  收到通知（成功/失敗）   │                         │
    │ ◄─────────────────────  │                         │
```

**重點**：整條流水線完全自動，你只需要 `git push`。

---

## 三、GitHub Actions 核心概念

GitHub Actions 是 GitHub 內建的 CI/CD 平台，**免費提供一定額度的使用量**，對個人學習專案來說幾乎不需要付費。

### 3.1 五個核心概念的層次結構

```
Workflow（工作流程）
 │  定義在 .github/workflows/*.yml
 │  由 Event 觸發
 │
 ├── Job（工作單元）
 │    │  在一台 Runner 機器上執行
 │    │  多個 Job 預設並行執行
 │    │
 │    ├── Step（步驟）
 │    │    執行一個指令 (run) 或
 │    │    呼叫一個 Action (uses)
 │    │
 │    └── Step
 │         ...
 │
 └── Job（另一個工作單元）
      可設定「等前一個 Job 完成再執行」
```

### 3.2 各概念說明

| 名詞 | 中文 | 說明 |
|------|------|------|
| **Workflow** | 工作流程 | 整個自動化流程，存放在 `.github/workflows/` 資料夾的 YAML 檔 |
| **Event** | 事件 | 觸發 Workflow 的條件，例如 `push`、`pull_request` |
| **Job** | 工作單元 | 一個 Job 在同一台機器上執行，有自己的環境 |
| **Step** | 步驟 | Job 內的每一個動作，按順序執行 |
| **Runner** | 執行機器 | GitHub 提供的雲端虛擬機，`ubuntu-latest` 最常用 |
| **Action** | 動作 | 封裝好的可重用步驟，格式為 `owner/repo@version` |

### 3.3 常見的 Event（觸發條件）

```yaml
on:
  push:               # 有人 git push 時觸發
  pull_request:       # 有人開 PR 或更新 PR 時觸發
  schedule:           # 定時觸發（cron 語法）
    - cron: '0 8 * * 1'  # 每週一早上 8 點
  workflow_dispatch:  # 允許手動從 GitHub 網頁按鈕觸發
```

---

## 四、第一個 Workflow：讓撮合引擎自動跑測試

### 4.1 建立檔案

在你的撮合引擎專案根目錄，建立以下路徑的檔案：

```
java-learning/
  ├── .github/
  │   └── workflows/
  │       └── ci.yml    ← 就是這個檔案
  ├── src/
  ├── pom.xml
  └── ...
```

### 4.2 完整 ci.yml 說明

```yaml
# Workflow 的名稱，會顯示在 GitHub Actions 頁面
name: CI

# 設定觸發條件
on:
  push:
    # 只有 push 到 main 或 master 分支時才觸發
    branches: [ main, master ]
  pull_request:
    # 開 PR 的目標分支是 main 或 master 時觸發
    branches: [ main, master ]

# 定義要執行的 Job
jobs:

  # Job 的名稱（自己取，英文無空格）
  build-and-test:

    # 在 GitHub 提供的 Ubuntu 最新版虛擬機上執行
    runs-on: ubuntu-latest

    # 這個 Job 包含的步驟，按順序執行
    steps:

      # 步驟 1：把程式碼 checkout 到 Runner 機器上
      # actions/checkout 是官方提供的 Action，@v4 是版本號
      - uses: actions/checkout@v4

      # 步驟 2：安裝 Java 環境
      - uses: actions/setup-java@v4
        with:
          java-version: '21'          # 使用 Java 21
          distribution: 'temurin'     # Eclipse Temurin 發行版（推薦）

      # 步驟 3：用 Maven 編譯並執行所有測試
      # clean：清除上次的編譯產物
      # verify：包含 compile → test → package → integration-test 等所有階段
      - name: Build with Maven
        run: mvn clean verify

      # 步驟 4：如果測試失敗，上傳測試報告讓你可以下載查看
      - name: Upload test results
        uses: actions/upload-artifact@v4
        # if: failure() 表示「只有前面步驟失敗時才執行這步」
        if: failure()
        with:
          name: test-results                  # 上傳的壓縮檔名稱
          path: target/surefire-reports/      # Maven 測試報告的位置
```

### 4.3 推送並觀察

```bash
git add .github/workflows/ci.yml
git commit -m "ci: 新增自動測試 Workflow"
git push origin main
```

推送後，到 GitHub 你的專案頁面 → 點選上方的 **Actions** 分頁，
就能看到 Workflow 正在執行，每個 Step 的輸出都可以即時查看。

---

## 五、加速 CI：快取 Maven 依賴

### 5.1 為什麼需要快取？

Maven 第一次執行時，會從網路下載所有依賴（Spring Boot、JUnit 等），
存放在 `~/.m2/repository`。這些檔案**幾百 MB**，每次 CI 都重新下載，
**單次可能耗費 2～5 分鐘**，非常浪費。

解決方案：用 `actions/cache` 把 `~/.m2/repository` 快取起來。

### 5.2 快取 Key 的設計邏輯

快取 Key 要能反映「依賴有沒有改變」：

```
key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
```

- `runner.os`：作業系統（`Linux`），不同 OS 的快取不共用
- `maven`：識別這是 Maven 的快取
- `hashFiles('**/pom.xml')`：計算 `pom.xml` 的雜湊值

**邏輯**：
- `pom.xml` 沒改 → hash 相同 → 直接用快取，跳過下載（省幾分鐘）
- `pom.xml` 有改 → hash 不同 → 重新下載（確保依賴是最新的）

### 5.3 加入快取的完整 ci.yml

```yaml
name: CI

on:
  push:
    branches: [ main, master ]
  pull_request:
    branches: [ main, master ]

jobs:
  build-and-test:
    runs-on: ubuntu-latest

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      # ★ 新增：快取 Maven 本地倉庫
      - name: Cache Maven dependencies
        uses: actions/cache@v4
        with:
          # 要快取的資料夾路徑
          path: ~/.m2/repository
          # 快取 Key：OS + pom.xml 的 hash 值
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
          # 備用 Key：如果完全沒有快取命中，嘗試找前綴相同的舊快取
          restore-keys: |
            ${{ runner.os }}-maven-

      - name: Build with Maven
        run: mvn clean verify

      - name: Upload test results
        uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: test-results
          path: target/surefire-reports/
```

**加入快取後，CI 時間通常從 5 分鐘縮短到 1 分鐘以內。**

---

## 六、Matrix Strategy：同時測試多個 Java 版本

### 6.1 為什麼需要 Matrix？

撮合引擎可能要在 Java 17 和 Java 21 兩種環境下都能跑。
與其建立兩個幾乎一樣的 Job，`matrix` 讓你用一個設定自動產生多個並行 Job。

### 6.2 並行執行示意圖

```
單次 push 觸發

         build-and-test (java-version: 17)
        ╱                                   ╲
push ──                                      ── 全部通過才算 CI 成功
        ╲                                   ╱
         build-and-test (java-version: 21)

兩個 Job 同時執行，節省總等待時間
```

### 6.3 Matrix Strategy 的 YAML 寫法

```yaml
name: CI - Multi Java Version

on:
  push:
    branches: [ main, master ]
  pull_request:
    branches: [ main, master ]

jobs:
  build-and-test:
    runs-on: ubuntu-latest

    # 定義矩陣策略
    strategy:
      # fail-fast: false 表示一個版本失敗，不要立刻取消另一個
      # 設為 true（預設）則一個失敗就停止所有，節省資源
      fail-fast: false
      matrix:
        # 定義 java-version 變數，GitHub Actions 會自動展開
        java-version: [ '17', '21' ]

    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          # 使用 matrix 中的變數，語法是 ${{ matrix.變數名 }}
          java-version: ${{ matrix.java-version }}
          distribution: 'temurin'

      - name: Cache Maven dependencies
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          # Key 也要包含 java 版本，避免不同版本共用快取
          key: ${{ runner.os }}-maven-java${{ matrix.java-version }}-${{ hashFiles('**/pom.xml') }}
          restore-keys: |
            ${{ runner.os }}-maven-java${{ matrix.java-version }}-

      - name: Build with Maven (Java ${{ matrix.java-version }})
        run: mvn clean verify

      - name: Upload test results
        uses: actions/upload-artifact@v4
        if: failure()
        with:
          # 加上 java 版本號以區分不同版本的報告
          name: test-results-java${{ matrix.java-version }}
          path: target/surefire-reports/
```

---

## 七、CD：自動部署到伺服器

> 注意：本節假設你的伺服器已依第三十二章設定好 Docker 環境。

### 7.1 Secrets：絕對不能把密碼寫在程式碼裡

SSH 金鑰、資料庫密碼這類敏感資訊，**絕對不能出現在 YAML 檔或程式碼裡**。
GitHub 提供 **Secrets** 功能，讓你把這些資訊安全地存放在 GitHub 後台。

**設定方式：**

```
GitHub 專案頁面
  → Settings（設定）
    → Secrets and variables
      → Actions
        → New repository secret
```

常見的 Secrets 設定：

| Secret 名稱 | 說明 |
|------------|------|
| `SSH_PRIVATE_KEY` | 連線伺服器用的私鑰（`~/.ssh/id_rsa` 的內容） |
| `SERVER_HOST` | 伺服器 IP 或網域 |
| `SERVER_USER` | SSH 登入帳號（例如 `ubuntu`） |

在 YAML 中引用 Secret 的語法：`${{ secrets.SECRET_名稱 }}`

### 7.2 加入 CD 的完整 Workflow

```yaml
name: CI/CD

on:
  push:
    branches: [ main, master ]
  pull_request:
    branches: [ main, master ]

jobs:
  # ── CI：跑測試 ──────────────────────────────
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Cache Maven dependencies
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-maven-

      - name: Run tests
        run: mvn clean verify

  # ── CD：部署到伺服器 ─────────────────────────
  deploy:
    # 必須等 test Job 成功才執行 deploy
    needs: test

    runs-on: ubuntu-latest

    # ★ 關鍵設定：只有 push 到 main 分支才部署
    # PR 只跑測試，不部署，避免未審核的程式碼上線
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'

    steps:
      - uses: actions/checkout@v4

      # 打包 JAR
      - uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Package JAR
        run: mvn clean package -DskipTests  # 測試已在前一個 Job 跑過，這裡跳過

      # 把 JAR 複製到伺服器並重啟服務
      - name: Deploy to server
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.SERVER_HOST }}      # 從 Secrets 取得伺服器位址
          username: ${{ secrets.SERVER_USER }}  # 從 Secrets 取得帳號
          key: ${{ secrets.SSH_PRIVATE_KEY }}   # 從 Secrets 取得 SSH 私鑰
          script: |
            # 進入專案目錄
            cd /opt/matching-engine
            # 拉取最新的 Docker image
            docker pull alexchang1999/matching-engine:latest
            # 重啟容器（--force-recreate 確保使用新的 image）
            docker compose up -d --force-recreate
            # 確認容器有起來
            docker compose ps
```

### 7.3 Job 依賴關係圖

```
push to main
     │
     ▼
  test Job
  （跑測試）
     │
     │ 成功才繼續
     ▼
  deploy Job
  （部署伺服器）

push to feature branch 或開 PR：
     │
     ▼
  test Job
  （只跑測試，不部署）
```

---

## 八、Branch Protection Rules：保護主分支

### 8.1 設定步驟

```
GitHub 專案頁面
  → Settings
    → Branches
      → Add branch protection rule
```

建議勾選的選項：

```
Branch name pattern: main

☑ Require a pull request before merging
    ☑ Require approvals: 1
       （至少一個人 review 才能 merge）

☑ Require status checks to pass before merging
    ☑ Require branches to be up to date before merging
    搜尋並選取你的 CI Job 名稱：build-and-test

☑ Do not allow bypassing the above settings
   （就算是管理員也不能繞過，維持紀律）
```

### 8.2 設定後的效果

```
開發者想把 feature/new-order merge 進 main

  feature/new-order ──► 開 Pull Request ──► CI 自動執行
                                                │
                              CI 失敗 ◄─────────┤
                              （無法 merge）     │
                                                │
                              CI 通過 ◄─────────┘
                                 │
                              等待 reviewer 審核
                                 │
                              approved → 允許 merge
```

### 8.3 工程師文化：為什麼這很重要

**「任何程式碼都要先過測試才能進主線」** 不只是技術問題，而是工程文化。

1. **可重複性**：任何人在任何時間推送，都跑同一套測試，標準一致
2. **快速失敗**：問題在提交後幾分鐘就被發現，而不是部署後才爆炸
3. **文件化**：CI 的執行記錄就是一份「每次變更是否通過測試」的歷史紀錄
4. **心理安全**：有 CI 守門，開發者可以放心重構，不怕不小心破壞其他功能

這也正是第十六章強調 TDD 的原因：**先寫測試，CI 才有意義**。
沒有測試的 CI，只能確認「程式能編譯」，而無法確認「程式是正確的」。

---

## 九、本章完整 Workflow 總覽

以下是整合了快取、matrix 測試、CD 部署的生產級完整設定，
放在 `.github/workflows/ci-cd.yml`：

```yaml
name: CI/CD Pipeline

on:
  push:
    branches: [ main, master ]
  pull_request:
    branches: [ main, master ]
  workflow_dispatch:   # 允許手動觸發（緊急補丁時很有用）

jobs:
  test:
    name: Test (Java ${{ matrix.java-version }})
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        java-version: [ '17', '21' ]

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK ${{ matrix.java-version }}
        uses: actions/setup-java@v4
        with:
          java-version: ${{ matrix.java-version }}
          distribution: 'temurin'

      - name: Cache Maven repository
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-maven-java${{ matrix.java-version }}-${{ hashFiles('**/pom.xml') }}
          restore-keys: |
            ${{ runner.os }}-maven-java${{ matrix.java-version }}-
            ${{ runner.os }}-maven-

      - name: Build and test with Maven
        run: mvn clean verify

      - name: Upload Surefire reports on failure
        uses: actions/upload-artifact@v4
        if: failure()
        with:
          name: surefire-java${{ matrix.java-version }}
          path: target/surefire-reports/

  deploy:
    name: Deploy to Production
    needs: test                    # 等所有 test matrix Job 都通過
    runs-on: ubuntu-latest
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'

    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Cache Maven repository
        uses: actions/cache@v4
        with:
          path: ~/.m2/repository
          key: ${{ runner.os }}-maven-java21-${{ hashFiles('**/pom.xml') }}
          restore-keys: ${{ runner.os }}-maven-

      - name: Package application
        run: mvn clean package -DskipTests

      - name: Deploy via SSH
        uses: appleboy/ssh-action@v1
        with:
          host: ${{ secrets.SERVER_HOST }}
          username: ${{ secrets.SERVER_USER }}
          key: ${{ secrets.SSH_PRIVATE_KEY }}
          script: |
            cd /opt/matching-engine
            docker compose pull
            docker compose up -d --force-recreate
            docker compose ps
```

---

## 十、練習題

### 練習一

你在撮合引擎加了一個新的 `OrderValidator` 類別，並寫了三個 JUnit 測試。
請描述：從你執行 `git push` 到收到「CI 通過」通知，GitHub Actions 中間經過了哪些步驟？
每個步驟在 Workflow 的哪一個 Job / Step 中執行？

<details>
<summary>▶ 展開參考答案</summary>

1. `git push` 觸發 Workflow 的 `push` event
2. GitHub 啟動一台 `ubuntu-latest` Runner
3. **Step: actions/checkout@v4** — 把你的程式碼複製到 Runner
4. **Step: actions/setup-java@v4** — 安裝 Java 21（或指定版本）
5. **Step: actions/cache@v4** — 嘗試從快取恢復 `~/.m2/repository`；
   若 `pom.xml` 沒有改動則快取命中，跳過下載
6. **Step: mvn clean verify** — 編譯專案，執行所有測試（包含你的 `OrderValidatorTest`）
7. 測試全數通過 → Step 回傳 exit code 0 → Job 狀態標記為 success
8. GitHub 在 PR 頁面或 commit 旁顯示綠色打勾，並傳送通知

若 `OrderValidatorTest` 有任何一個失敗：
- `mvn clean verify` 回傳非零 exit code → Step 失敗 → Job 失敗
- **Step: Upload Surefire reports** 因 `if: failure()` 而執行，上傳測試報告
- CI 顯示紅色叉叉，Branch Protection Rule 阻止 PR 被 merge

</details>

---

### 練習二

你的 CI Workflow 目前每次跑都要花 4 分鐘下載 Maven 依賴。
請修改 `ci.yml`，加入 Maven 依賴快取，並說明你選擇的 cache key 設計理由。

<details>
<summary>▶ 展開參考答案</summary>

在 `setup-java` 步驟後加入：

```yaml
- name: Cache Maven repository
  uses: actions/cache@v4
  with:
    path: ~/.m2/repository
    key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}
    restore-keys: |
      ${{ runner.os }}-maven-
```

**Cache Key 設計理由：**

- `${{ runner.os }}`：不同作業系統（Linux/Windows/macOS）的 native 函式庫不通用，必須分開快取
- `maven`：作為識別字，避免和其他快取混淆
- `${{ hashFiles('**/pom.xml') }}`：`pom.xml` 定義了所有依賴，用它的 hash 值做 key，確保：
  - 依賴沒有改變 → hash 相同 → 命中快取，直接使用（省 3～4 分鐘）
  - 有新增/升級/移除依賴 → hash 改變 → 重新下載並建立新快取

`restore-keys` 是備用方案：如果完整 key 沒命中（例如 pom.xml 剛剛改了），
先用舊的部分快取，減少下載量（只下載新增的依賴，而非全部重下）。

</details>

---

### 練習三

你的老闆說：「每次 PR 都要同時確認在 Java 17 和 Java 21 能跑，
而且部署只能在 main 分支 push 時才發生，PR 不能觸發部署。」

請寫出滿足這兩個需求的 Workflow 骨架（只需要 YAML 結構，不需要寫完整的每一步）。

<details>
<summary>▶ 展開參考答案</summary>

```yaml
name: CI/CD

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  test:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        java-version: [ '17', '21' ]   # 同時測試兩個版本
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: ${{ matrix.java-version }}
          distribution: 'temurin'
      # ... 快取、mvn verify ...

  deploy:
    needs: test    # 等 test 矩陣中所有 Job 都通過
    runs-on: ubuntu-latest
    # 只有「push 事件」且「目標是 main 分支」才執行
    # pull_request 事件不符合此條件，不會觸發部署
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    steps:
      # ... 打包、ssh 部署 ...
```

**關鍵說明：**

- `strategy.matrix.java-version: ['17', '21']` 會產生兩個並行 Job
- `needs: test` 確保兩個 matrix Job **都**成功才繼續執行 deploy
- `if: github.event_name == 'push'` 排除 `pull_request` 事件
- `github.ref == 'refs/heads/main'` 確保只有 main 分支的 push 才部署，
  其他分支（例如 `feature/xxx`）的 push 不會觸發部署

</details>

---

## 本章總結

| 主題 | 重點 |
|------|------|
| CI 的價值 | 每次 push 自動跑測試，問題早發現早修正 |
| CD 的價值 | 測試通過後自動部署，消除手動操作失誤 |
| Workflow 結構 | Event → Workflow → Jobs → Steps |
| 快取 | 用 pom.xml 的 hash 做 key，大幅縮短 CI 時間 |
| Matrix | 一個設定自動產生多個版本的並行 Job |
| Secrets | 密碼和金鑰永遠不放在程式碼裡，存在 GitHub Secrets |
| Branch Protection | 要求 CI 通過才能 merge，是工程文化的基礎 |

與第十六章的連結：**沒有測試，CI 只是「自動確認能編譯」而已。**
先把撮合引擎的 `OrderBook`、`MatchingEngine` 等核心邏輯寫好 JUnit 測試，
讓 CI 幫你守門，才能在快速迭代的同時確保系統不崩壞。
