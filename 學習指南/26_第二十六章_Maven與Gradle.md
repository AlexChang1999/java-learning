# 第二十六章：Maven 與 Gradle 建置工具
> 沒有它你無法管理 Spring Boot 的依賴；工作第一天就要用到

---

## 前言：為什麼需要建置工具？

在學習 Java 的前幾章，你可能只有幾個 `.java` 檔案，手動 `javac` 編譯就夠了。  
但真實專案有幾十個依賴函式庫（Spring Boot、Hibernate、Jackson...），手動下載 `.jar` 再設定 classpath 根本不可能維護。

```
沒有建置工具的痛苦：

1. 下載依賴：手動去 Maven Central 找 spring-boot-starter-web-3.2.5.jar
             → 但它還依賴 spring-webmvc，spring-webmvc 還依賴 spring-core...
             → 依賴的依賴的依賴，手動追蹤幾十個 jar
2. 編譯：javac -cp "lib/spring-boot.jar;lib/jackson.jar;..." src/**/*.java
         → 一個字打錯就出錯，不同人電腦路徑不同
3. 打包：把所有 .class 和依賴打成一個 jar 讓伺服器跑？手動寫 MANIFEST.MF...
4. 測試：手動跑 JUnit 測試？要設定哪些 jar？

→ 有了 Maven / Gradle，這些全部自動化，一行指令搞定
```

---

## 一、Maven 基礎

### Maven 的核心概念

```
Maven 的三大職責：
  1. 依賴管理（Dependency Management）
     → 你說「我要用 Spring Boot 3.2」，Maven 自動下載它和所有子依賴
  2. 建置生命週期（Build Lifecycle）
     → 定義好的步驟：編譯 → 測試 → 打包 → 部署
  3. 專案結構標準化
     → 所有 Maven 專案長一樣，任何人接手都知道去哪找程式碼
```

### 標準的 Maven 專案結構

```
my-project/
├── pom.xml                        ← Maven 的設定檔（整個專案的「說明書」）
└── src/
    ├── main/
    │   ├── java/
    │   │   └── com/example/
    │   │       └── App.java       ← 你的主程式碼
    │   └── resources/
    │       └── application.yml   ← 設定檔（Spring Boot 用）
    └── test/
        ├── java/
        │   └── com/example/
        │       └── AppTest.java  ← 測試程式碼
        └── resources/
```

### pom.xml 結構詳解

`pom.xml`（Project Object Model）是 Maven 的核心設定檔：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>

    <!-- ① 專案座標：唯一識別這個專案 -->
    <groupId>com.example</groupId>          <!-- 公司/組織的套件名（反向網域） -->
    <artifactId>matching-engine</artifactId> <!-- 專案名稱 -->
    <version>1.0.0-SNAPSHOT</version>        <!-- 版本（SNAPSHOT = 開發中） -->
    <packaging>jar</packaging>               <!-- 打包格式（jar / war） -->

    <!-- ② 統一管理版本號（避免在多個地方重複寫同一個版本） -->
    <properties>
        <java.version>21</java.version>
        <spring.boot.version>3.2.5</spring.boot.version>
    </properties>

    <!-- ③ 依賴父 POM（Spring Boot 提供預設依賴版本，省去自己查版本） -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.5</version>
    </parent>

    <!-- ④ 依賴清單：你要用哪些函式庫 -->
    <dependencies>

        <!-- Spring Boot Web（自動包含 Spring MVC、Tomcat、Jackson） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <!-- 繼承自 parent，不需要寫版本號！ -->
        </dependency>

        <!-- JPA + Hibernate（資料庫 ORM） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-jpa</artifactId>
        </dependency>

        <!-- H2 記憶體資料庫（只在測試時用，scope=test 表示不打入正式 jar） -->
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
            <scope>test</scope>  <!-- test = 只在 src/test 中可用 -->
        </dependency>

        <!-- Lombok（自動生成 getter/setter，scope=provided 表示只在編譯時需要） -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Spring Boot 測試框架（包含 JUnit 5 + Mockito） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>

    </dependencies>

    <!-- ⑤ 建置插件 -->
    <build>
        <plugins>
            <!-- Spring Boot Maven Plugin：把專案打成可執行的 fat jar -->
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>
```

### 依賴 Scope 對比

| Scope | 編譯時 | 測試時 | 打包進 jar | 典型用途 |
|-------|--------|--------|-----------|---------|
| `compile`（預設）| ✅ | ✅ | ✅ | 大部分依賴 |
| `test` | ❌ | ✅ | ❌ | JUnit、H2 測試 DB |
| `provided` | ✅ | ✅ | ❌ | Lombok、Servlet API（容器提供）|
| `runtime` | ❌ | ✅ | ✅ | 資料庫驅動（只在執行時需要）|

---

## 二、Maven 建置生命週期

### 核心的 default 生命週期

```
Maven default lifecycle（按順序執行）：

validate  → 驗證 pom.xml 格式正確
    ↓
compile   → 編譯 src/main/java → 產出 .class 到 target/classes/
    ↓
test      → 執行 src/test/java 的測試，失敗則停止
    ↓
package   → 把 .class 打包成 .jar（或 .war）到 target/
    ↓
verify    → 執行整合測試（integration tests）
    ↓
install   → 把 .jar 安裝到本機 Maven 倉庫（~/.m2/repository）
    ↓
deploy    → 上傳到遠端 Maven 倉庫（CI/CD 時用）

重要規則：執行某個階段，會先執行前面所有階段
  mvn package  → 會先跑 validate → compile → test → package
```

### 常用 Maven 指令

```bash
# 編譯（只編譯，不跑測試）
mvn compile

# 跑所有測試
mvn test

# 打包成 jar（會先跑測試，測試失敗就停）
mvn package

# 打包但跳過測試（開發時節省時間，不推薦 CI/CD 使用）
mvn package -DskipTests

# 清除 target/ 目錄（清除上次的編譯結果）
mvn clean

# 清除後重新打包（最常用）
mvn clean package

# 啟動 Spring Boot 應用（不需要先打包）
mvn spring-boot:run

# 查看依賴樹（debug 版本衝突時很有用）
mvn dependency:tree

# 查看哪些依賴有新版本
mvn versions:display-dependency-updates
```

---

## 三、依賴管理：Maven Central

```
你在 pom.xml 寫的每個 <dependency>，Maven 從哪裡下載？

1. 先查本機快取：~/.m2/repository/
   └── org/springframework/boot/spring-boot-starter-web/3.2.5/
       └── spring-boot-starter-web-3.2.5.jar  ← 如果有，直接用

2. 如果沒有，從 Maven Central（https://repo1.maven.org/maven2）下載
   → 下載完存到本機快取，下次不用再下載

依賴解析流程：
   pom.xml 寫 spring-boot-starter-web
       ↓
   Maven 找到 spring-boot-starter-web-3.2.5.pom（這個 jar 的「說明書」）
       ↓
   pom 說它還需要：spring-webmvc, spring-boot-autoconfigure, tomcat...
       ↓
   遞迴下載所有子依賴
       ↓
   你只寫了 1 個依賴，Maven 幫你解決了 50 個
```

### 如何找到依賴的座標？

去 [https://mvnrepository.com](https://mvnrepository.com) 搜尋，例如搜尋 "jackson databind"：

```xml
<!-- 搜尋結果頁面會給你可以複製的 XML -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.1</version>
</dependency>
```

---

## 四、Gradle 基礎

Gradle 是 Maven 的繼承者，語法更簡潔、速度更快。  
Android 專案強制使用 Gradle，Spring Boot 也全面支援。

### Gradle 的建置檔：build.gradle（Groovy）

```groovy
// build.gradle（Groovy DSL，較舊但廣泛使用）

plugins {
    id 'java'
    id 'org.springframework.boot' version '3.2.5'  // Spring Boot 插件
    id 'io.spring.dependency-management' version '1.1.4'  // 依賴版本管理
}

group = 'com.example'
version = '1.0.0-SNAPSHOT'
sourceCompatibility = '21'

repositories {
    mavenCentral()  // 從 Maven Central 下載依賴
}

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    runtimeOnly    'com.h2database:h2'          // 只在執行時需要
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    compileOnly    'org.projectlombok:lombok'   // 只在編譯時需要
    annotationProcessor 'org.projectlombok:lombok'  // 注解處理器
}
```

### build.gradle.kts（Kotlin DSL，現代推薦）

```kotlin
// build.gradle.kts（Kotlin DSL，有型別檢查和 IDE 自動補全）

plugins {
    java
    id("org.springframework.boot") version "3.2.5"
    id("io.spring.dependency-management") version "1.1.4"
}

group = "com.example"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
}
```

### Gradle 的依賴 Configuration 對比

| Gradle Configuration | 相當於 Maven Scope | 說明 |
|---------------------|-------------------|------|
| `implementation` | `compile` | 主要依賴（不暴露給使用者）|
| `api` | `compile` | 暴露給下游模組的依賴 |
| `testImplementation` | `test` | 測試用依賴 |
| `runtimeOnly` | `runtime` | 只在執行時需要 |
| `compileOnly` | `provided` | 只在編譯時需要 |

### 常用 Gradle 指令

```bash
# 編譯
./gradlew compileJava

# 跑測試
./gradlew test

# 打包（生成 build/libs/xxx.jar）
./gradlew build

# 打包跳過測試
./gradlew build -x test

# 清除 build/ 目錄
./gradlew clean

# 清除後重新打包
./gradlew clean build

# 啟動 Spring Boot
./gradlew bootRun

# 查看依賴樹
./gradlew dependencies

# Gradle Wrapper 是什麼？
# gradlew（Linux/Mac）或 gradlew.bat（Windows）是 Gradle Wrapper
# 確保所有人用相同版本的 Gradle，不需要本機安裝 Gradle
# 應該把 gradlew 和 gradle/ 目錄一起提交到 git！
```

---

## 五、Maven vs Gradle 怎麼選？

```
專案類型                   推薦
──────────────────────────────────────────────
新的 Spring Boot 專案      Maven（文件多、Spring 官方支援好）
Android 專案               Gradle（強制使用）
大型多模組專案              Gradle（增量建置快很多，C2 優化後差距更明顯）
公司現有 Maven 專案         繼續用 Maven（不要無謂遷移）
個人學習專案                Maven（stackoverflow 答案多）
```

**Maven 的優點：**
- XML 格式，結構清晰（雖然冗長）
- 成熟穩定，踩過的坑都有解答
- Spring 官方 Initializr 預設生成 Maven

**Gradle 的優點：**
- 程式碼式設定（比 XML 靈活）
- 增量建置（只重新編譯有改動的部分），大型專案省時
- 比 Maven 快 2~10 倍（有 build cache）

---

## 六、與 Spring Boot 整合的完整流程

### 從 Spring Initializr 產生專案

```
1. 去 https://start.spring.io/
2. 選擇：
   - Project: Maven（或 Gradle）
   - Language: Java
   - Spring Boot: 3.2.5
   - Java: 21
3. 加入依賴：Spring Web、Spring Data JPA、H2
4. 點 Generate → 下載 zip
5. 解壓縮，用 IntelliJ 打開（直接 open pom.xml）
```

### 實際工作流程

```bash
# Day 1：第一次拿到專案
git clone https://github.com/company/matching-engine.git
cd matching-engine

# Maven 自動下載所有依賴（第一次會慢，之後快）
mvn compile

# 跑測試確認環境正常
mvn test

# 啟動本機開發伺服器
mvn spring-boot:run
# → 預設在 http://localhost:8080

# 打包成可部署的 fat jar
mvn clean package
java -jar target/matching-engine-1.0.0.jar  # 直接執行，不需要 Tomcat
```

### 版本衝突怎麼解決

```bash
# 查看完整依賴樹，找衝突
mvn dependency:tree | grep "jackson"

# 輸出可能像這樣：
# [INFO] +- org.springframework.boot:spring-boot-starter-web:jar:3.2.5
# [INFO] |  \- com.fasterxml.jackson.core:jackson-databind:jar:2.17.1
# [INFO] \- com.some.other:lib:jar:1.0.0
# [INFO]    \- com.fasterxml.jackson.core:jackson-databind:jar:2.13.0  ← 衝突！

# 解法：在 <dependencyManagement> 固定版本
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <version>2.17.1</version>  <!-- 強制所有人用這個版本 -->
        </dependency>
    </dependencies>
</dependencyManagement>
```

---

## 本章練習題

**Q1：`mvn package` 和 `mvn install` 有什麼差別？什麼時候需要用 `install`？**
<details>
<summary>答案</summary>
mvn package 把專案打包成 jar/war 放在 target/ 目錄下，只有這個專案能用。mvn install 額外把 jar 複製到本機 Maven 倉庫（~/.m2/repository），讓其他本機專案可以透過 pom.xml 引用它。在單一專案開發時用 package 就夠；只有當你同時開發多個互相依賴的專案（多模組或多個 repo）時，才需要 install 讓其他專案能引用最新版本。
</details>

**Q2：以下 pom.xml 片段中，`scope=test` 的 H2 資料庫和 `scope=provided` 的 Lombok，打包成正式 jar 後還存在嗎？為什麼？**
```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <scope>provided</scope>
</dependency>
```
<details>
<summary>答案</summary>
兩個都不會打包進正式 jar。H2 的 scope=test 表示只在測試階段可用，正式打包時排除；Lombok 的 scope=provided 表示編譯時用（生成 getter/setter 的 bytecode），但執行時不需要（因為 Lombok 只是在編譯階段生成程式碼，運行時 .class 文件已經有這些方法了）。正式環境通常改用 MySQL/PostgreSQL，不需要 H2。
</details>

**Q3：你接手一個 Maven 專案，mvn compile 報錯找不到某個 class，但你明明在 pom.xml 看到了那個依賴。最可能的原因是什麼？怎麼排查？**
<details>
<summary>答案</summary>
最常見的三個原因：(1) 依賴的 scope 不對，例如設成 test 但在 main 程式碼中使用；(2) 本機 ~/.m2 快取損壞，用 mvn dependency:purge-local-repository 清除後重新下載；(3) 版本衝突被排除（exclusion）。排查方式：mvn dependency:tree 查看實際載入的依賴樹；確認 scope 設定；嘗試 mvn clean compile -U（強制更新 SNAPSHOT 依賴）。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 18 章 | Phase 3：工程基礎
> 下一章（第 19 章）：[第六十一章：Git 工作流程進階](61_第六十一章_Git工作流程進階.md)
