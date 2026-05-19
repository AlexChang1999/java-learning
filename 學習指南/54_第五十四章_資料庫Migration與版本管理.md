# 第五十四章：資料庫 Migration 與版本管理（Flyway）

## 前言：沒有 Migration 工具的痛苦

你的 Spring Boot 專案有 5 個開發環境（本機、CI、Staging、生產、DR）。每次新增一個欄位：

```
❌ 沒有 Migration 工具的做法：
  1. 本機手動 ALTER TABLE
  2. 告訴隊友「記得跑一下這個 SQL」（隊友忘了）
  3. 生產部署時忘記改 DB Schema（應用啟動失敗）
  4. 找不到是誰什麼時候改了哪個 Table（沒有記錄）
```

**Flyway** 把 SQL 腳本版本化管理，讓「資料庫變更」和「程式碼變更」一起被 Git 追蹤，應用啟動時自動執行尚未跑過的 Migration。

---

## 一、Flyway 核心概念

```
migration/
  V1__create_orders_table.sql      ← 第一版：建立訂單表
  V2__add_customer_id_to_orders.sql ← 第二版：加客戶 ID 欄位
  V3__create_products_table.sql    ← 第三版：建立商品表
  R__refresh_product_view.sql      ← Repeatable：每次內容變化都重跑（視圖/函數）
```

**版本號命名規則：**

```
V{版本號}__{描述}.sql
  V = Version（固定前綴）
  版本號 = 數字，用點或底線分隔（1, 1.1, 2, 20240115）
  __ = 兩個底線（分隔版本號和描述）
  描述 = 用底線分隔單詞
```

Flyway 在 DB 裡維護一張 `flyway_schema_history` 表，記錄每個 migration 的執行狀態和 checksum。

---

## 二、Spring Boot 整合

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
<!-- MySQL 需要額外加 -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/orderdb
    username: root
    password: password
  flyway:
    enabled: true
    locations: classpath:db/migration   # SQL 腳本的位置
    baseline-on-migrate: true           # 對已有資料庫初始化時使用
    validate-on-migrate: true           # 啟動前驗證 checksum（防止腳本被修改）
    out-of-order: false                 # 不允許亂序（v3 在 v2 前跑）
```

**應用啟動時，Flyway 自動執行：**

```
啟動 → Flyway 掃描 db/migration/ → 比對 flyway_schema_history
  → 發現 V4 還沒跑過 → 執行 V4__xxx.sql
  → 記錄到 flyway_schema_history
  → 應用繼續啟動
```

---

## 三、SQL 腳本最佳實踐

```sql
-- src/main/resources/db/migration/V1__create_orders_table.sql
-- 每個腳本都要是冪等的（即使意外跑兩次也不出錯）

CREATE TABLE IF NOT EXISTS orders (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    VARCHAR(50) NOT NULL,
    customer_id VARCHAR(50) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    total_amount DECIMAL(15, 2) NOT NULL DEFAULT 0.00,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_id (order_id),
    INDEX idx_customer_id (customer_id),
    INDEX idx_status_created (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

```sql
-- V2__add_items_table.sql
CREATE TABLE IF NOT EXISTS order_items (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    BIGINT NOT NULL,
    product_id  VARCHAR(50) NOT NULL,
    product_name VARCHAR(200) NOT NULL,
    unit_price  DECIMAL(10, 2) NOT NULL,
    quantity    INT NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE CASCADE,
    INDEX idx_order_id (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

```sql
-- V3__add_shipping_address_to_orders.sql
-- 新增欄位要有預設值（避免影響現有資料）
ALTER TABLE orders
    ADD COLUMN shipping_city     VARCHAR(50),
    ADD COLUMN shipping_address  VARCHAR(200),
    ADD COLUMN shipped_at        DATETIME;
```

```sql
-- V4__migrate_customer_data.sql
-- 資料 Migration（轉換現有資料格式）
UPDATE orders
SET status = 'PAID'
WHERE status = 'COMPLETED' AND payment_confirmed = 1;
```

---

## 四、多環境設定

```
src/main/resources/
  db/
    migration/          ← 所有環境共用
      V1__xxx.sql
      V2__xxx.sql
    migration-dev/      ← 只在開發環境跑（測試資料）
      V1__insert_test_data.sql
```

```yaml
# application-dev.yml
spring:
  flyway:
    locations:
      - classpath:db/migration
      - classpath:db/migration-dev   # 開發環境額外跑測試資料

# application-prod.yml
spring:
  flyway:
    locations:
      - classpath:db/migration       # 生產只跑主 migration
```

---

## 五、Undo Migration（回滾）<!-- 💡 進階 -->

Flyway Community Edition 不支援自動回滾，但可以手動建立「回滾腳本」：

```sql
-- V5__add_discount_column.sql（正向 migration）
ALTER TABLE orders ADD COLUMN discount DECIMAL(5,2) DEFAULT 0.00;

-- U5__add_discount_column.sql（對應的 undo，需要 Flyway Pro，或手動執行）
ALTER TABLE orders DROP COLUMN discount;
```

**Community 版的回滾策略（實務做法）：**

```
不要回滾，而是向前修復（Forward Fix）：

V5__add_discount_column.sql    ← 加了一個有問題的欄位
V6__fix_discount_column.sql    ← 修復或移除問題欄位
```

這和軟體版本的哲學一致：生產問題不是回退程式碼，而是快速發布修復版本。

---

## 六、CI/CD 整合 <!-- 💡 進階 -->

```yaml
# .github/workflows/ci.yml
- name: Run Flyway Migration
  run: |
    # 在 CI 環境跑 migration（確認腳本語法正確）
    docker run --rm \
      -e FLYWAY_URL="jdbc:mysql://${{ secrets.DB_HOST }}/testdb" \
      -e FLYWAY_USER="${{ secrets.DB_USER }}" \
      -e FLYWAY_PASSWORD="${{ secrets.DB_PASSWORD }}" \
      -v ${{ github.workspace }}/src/main/resources/db/migration:/flyway/sql \
      flyway/flyway migrate

- name: Run Tests
  run: mvn test  # 測試跑在已 migrate 的 DB 上
```

**部署流程（藍綠部署的 Migration 策略）：**

```
問題：藍綠部署時，新舊版本可能同時跑在同一個 DB 上
     如果 Migration 刪除了欄位，舊版本就會報錯

原則：
  1. 先加欄位（新版本用新欄位，舊版本忽略新欄位） → 部署新版本
  2. 等舊版本完全下線後，再刪除舊欄位（另一次 Migration）

也就是說：每次 Release 包含兩個 PR：
  PR1：加新欄位 Migration + 新功能程式碼
  PR2（下次 Release）：刪除舊欄位 Migration + 清理程式碼
```

---

## 七、Liquibase：另一個選擇

Flyway 用 SQL 腳本，**Liquibase** 用 XML/YAML/JSON 描述變更（資料庫無關）：

```yaml
# db/changelog/db.changelog-master.yaml（Liquibase 格式）
databaseChangeLog:
  - changeSet:
      id: 1
      author: alex
      changes:
        - createTable:
            tableName: orders
            columns:
              - column:
                  name: id
                  type: BIGINT
                  autoIncrement: true
                  constraints:
                    primaryKey: true
              - column:
                  name: status
                  type: VARCHAR(20)
                  defaultValue: PENDING
```

| | Flyway | Liquibase |
|---|---|---|
| 腳本格式 | 純 SQL（直覺）| XML/YAML/JSON（跨 DB）|
| 學習曲線 | 低 | 中 |
| 自動回滾 | 付費版 | 支援（generateRollbackScript）|
| 社群 | 大 | 大 |
| Spring Boot 整合 | 一流 | 一流 |

**選哪個？** 如果只用 MySQL/PostgreSQL，Flyway 更簡單直覺；需要支援多種資料庫或需要回滾腳本生成，考慮 Liquibase。

---

## 本章練習題

**Q1：已經跑過的 Flyway migration 腳本能修改嗎？**
<details>
<summary>答案</summary>
不能修改！Flyway 在執行時記錄了每個腳本的 checksum（內容的雜湊值）。下次啟動時會重新計算 checksum，如果和歷史記錄不符，Flyway 會報錯拒絕啟動（`FlywayException: Validate failed: Migration checksum mismatch`）。這是為了確保 DB Schema 的演進歷史是可信賴的。正確做法是新建一個版本號更高的腳本來修正問題。
</details>

**Q2：如果兩個工程師同時提交了 V5 的 Migration 怎麼辦？**
<details>
<summary>答案</summary>
這是版本衝突，CI 會因為重複版本號報錯。需要其中一個人把自己的腳本改為 V6。為了避免這種情況，可以用時間戳記作為版本號（如 V20240115142030），衝突機率極低；或者用功能分支 + PR Review，確保一個時間只有一個人在修改 Schema。
</details>

**Q3：生產環境有已存在的 Table，第一次引入 Flyway 怎麼辦？**
<details>
<summary>答案</summary>
使用 baseline 機制：設定 `spring.flyway.baseline-on-migrate=true` 和 `spring.flyway.baseline-version=1`。Flyway 會把當前狀態標記為 V1（baseline），之後只執行 V2 以上的腳本，不會嘗試重新建立已存在的 Table。最佳做法是先寫一個 V1__baseline.sql 描述現有的完整 Schema（用 `mysqldump --no-data` 匯出），讓新環境可以從零建立完整的 Schema。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 25 章 | Phase 4：資料庫 + Spring 後端
> 下一章（第 26 章）：[第二十二章：REST API 設計](22_第二十二章_REST_API設計.md)
