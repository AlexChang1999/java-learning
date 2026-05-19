# 第六十七章：Spring Batch 批次處理

## 前言：什麼時候需要批次處理？

```
場景：每天凌晨 2 點，需要
  - 從 3 億筆訂單中計算每個用戶的月度消費統計
  - 對 500 萬名帳戶欠款用戶發送催繳通知
  - 把今天新增的 200 萬筆交易數據同步到報表資料庫

這些任務的特性：
  - 資料量巨大（百萬～億級）
  - 定時執行（非即時）
  - 允許失敗後「從上次中斷的地方繼續」
  - 需要監控進度和失敗記錄

用普通的 for 迴圈寫？很容易遇到：
  ❌ OOM（一次把 3 億筆全讀入記憶體）
  ❌ 失敗後不知道處理到哪裡了（要重跑全部）
  ❌ 沒有進度監控和告警

Spring Batch 解決了這些問題。
```

---

## 一、Spring Batch 核心架構

```
Job（任務）
  └── Step 1：讀取訂單數據 → 計算統計 → 寫入報表
  └── Step 2：發送催繳通知
  └── Step 3：同步到 ClickHouse

每個 Step 的處理模型（Chunk-Oriented Processing）：

  ItemReader → ItemProcessor → ItemWriter
      ↓              ↓              ↓
   讀取 N 筆     轉換/驗證每筆    批次寫入 N 筆
（每次 chunk_size 筆，不是全部讀入記憶體）

  chunk_size = 1000：每讀 1000 筆，轉換後批次寫入，提交事務
  → 即使有 1 億筆，記憶體裡最多只有 1000 筆
```

---

## 二、快速上手：對帳批次任務

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-batch</artifactId>
</dependency>
```

```yaml
# application.yml
spring:
  batch:
    job:
      enabled: false        # 不要啟動時自動執行所有 Job
    jdbc:
      initialize-schema: always  # 自動建 Spring Batch 的 metadata 表
```

```java
@Configuration
@EnableBatchProcessing
public class MonthlyStatsBatchConfig {

    // ===== 定義 Job =====
    @Bean
    public Job monthlyStatsJob(JobRepository jobRepository,
                                Step calculateStatsStep,
                                Step sendNotificationStep) {
        return new JobBuilder("monthlyStatsJob", jobRepository)
            .start(calculateStatsStep)
            .next(sendNotificationStep)
            .build();
    }

    // ===== 定義 Step（Chunk 模式）=====
    @Bean
    public Step calculateStatsStep(JobRepository jobRepository,
                                   PlatformTransactionManager txManager,
                                   ItemReader<Order> orderReader,
                                   ItemProcessor<Order, UserStats> statsProcessor,
                                   ItemWriter<UserStats> statsWriter) {
        return new StepBuilder("calculateStatsStep", jobRepository)
            .<Order, UserStats>chunk(1000, txManager)  // 每 1000 筆一個 chunk
            .reader(orderReader)
            .processor(statsProcessor)
            .writer(statsWriter)
            .faultTolerant()
                .skip(DataAccessException.class)    // 遇到 DB 異常跳過這筆
                .skipLimit(100)                      // 最多跳過 100 筆
                .retry(ConnectTimeoutException.class)// 連線超時自動重試
                .retryLimit(3)
            .build();
    }

    // ===== ItemReader：分頁讀取（不會 OOM）=====
    @Bean
    @StepScope
    public JdbcPagingItemReader<Order> orderReader(
            DataSource dataSource,
            @Value("#{jobParameters['month']}") String month) {

        return new JdbcPagingItemReaderBuilder<Order>()
            .name("orderReader")
            .dataSource(dataSource)
            .selectClause("SELECT id, customer_id, amount, status, created_at")
            .fromClause("FROM orders")
            .whereClause("WHERE DATE_FORMAT(created_at, '%Y-%m') = :month AND status = 'COMPLETED'")
            .parameterValues(Map.of("month", month))
            .sortKeys(Map.of("id", Order.ASCENDING))  // 必須有排序才能分頁
            .pageSize(1000)                           // 每頁 1000 筆
            .rowMapper(new BeanPropertyRowMapper<>(Order.class))
            .build();
    }

    // ===== ItemProcessor：業務邏輯轉換 =====
    @Bean
    @StepScope
    public ItemProcessor<Order, UserStats> statsProcessor() {
        return order -> {
            if (order.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
                return null;  // 返回 null = 跳過這筆（不寫入）
            }
            return UserStats.builder()
                .customerId(order.getCustomerId())
                .totalAmount(order.getAmount())
                .orderCount(1)
                .month(order.getCreatedAt().getMonth())
                .build();
        };
    }

    // ===== ItemWriter：批次寫入 =====
    @Bean
    @StepScope
    public JdbcBatchItemWriter<UserStats> statsWriter(DataSource dataSource) {
        return new JdbcBatchItemWriterBuilder<UserStats>()
            .dataSource(dataSource)
            .sql("""
                INSERT INTO user_monthly_stats (customer_id, month, total_amount, order_count)
                VALUES (:customerId, :month, :totalAmount, :orderCount)
                ON DUPLICATE KEY UPDATE
                    total_amount = total_amount + :totalAmount,
                    order_count  = order_count  + :orderCount
                """)
            .beanMapped()   // 用 UserStats 的 getter 自動對應 :name 佔位符
            .build();
    }
}
```

---

## 三、觸發 Job 執行

```java
@Service
public class BatchJobService {

    private final JobLauncher jobLauncher;
    private final Job monthlyStatsJob;

    // 手動觸發（如 REST API 呼叫）
    public void runMonthlyStats(String month) throws Exception {
        JobParameters params = new JobParametersBuilder()
            .addString("month", month)
            .addLong("runTime", System.currentTimeMillis())  // 確保每次參數不同
            .toJobParameters();

        JobExecution execution = jobLauncher.run(monthlyStatsJob, params);
        log.info("Job 狀態：{}", execution.getStatus());
    }

    // 定時觸發（每月 1 號凌晨 2 點）
    @Scheduled(cron = "0 0 2 1 * *")
    public void scheduledMonthlyStats() throws Exception {
        String lastMonth = YearMonth.now().minusMonths(1).toString();
        runMonthlyStats(lastMonth);
    }
}

// REST API 觸發
@RestController
@RequestMapping("/admin/batch")
public class BatchController {

    @PostMapping("/monthly-stats")
    public ResponseEntity<String> triggerMonthlyStats(@RequestParam String month) {
        batchJobService.runMonthlyStats(month);
        return ResponseEntity.accepted().body("Job 已提交，請查看執行狀態");
    }

    // 查詢 Job 執行歷史（Spring Batch 自動記錄在 metadata 表）
    @GetMapping("/executions")
    public List<JobExecution> getExecutions() {
        return jobExplorer.getJobExecutions(
            jobExplorer.getJobInstance("monthlyStatsJob", 0L)
        );
    }
}
```

---

## 四、Job 重啟與斷點續跑 <!-- 💡 進階 -->

Spring Batch 的核心優勢：**失敗後可從中斷點繼續，不需要重跑全部**。

```java
// Spring Batch 自動記錄每個 Step 的進度（BATCH_STEP_EXECUTION 表）
// 失敗的 Job 可以重新啟動，從上次失敗的 chunk 繼續

// 讓 Job 支援重啟（可重啟 = 失敗後可繼續）
@Bean
public Job restartableJob(JobRepository jobRepository, Step step) {
    return new JobBuilder("restartableJob", jobRepository)
        .start(step)
        // 預設 Job 支援重啟，但某些特殊 Job 只能跑一次：
        // .preventRestart()  // 加這個就不能重啟
        .build();
}

// 重啟失敗的 Job（傳入相同的 JobParameters 即可）
public void restartFailedJob(Long jobExecutionId) throws Exception {
    JobExecution failedExecution = jobExplorer.getJobExecution(jobExecutionId);
    JobParameters params = failedExecution.getJobParameters();
    jobLauncher.run(monthlyStatsJob, params);
    // Spring Batch 自動識別這是重啟，從上次失敗的 Step 繼續
}
```

---

## 五、Partitioned Step：並行分片處理 <!-- 🔴 資深 -->

```java
// 把 1 億筆訂單分成 10 個分片，並行處理（每個分片 1000 萬筆）
@Bean
public Step partitionedStep(JobRepository jobRepository,
                             Step slaveStep,
                             Partitioner orderPartitioner) {
    return new StepBuilder("partitionedOrderStep", jobRepository)
        .partitioner("slaveStep", orderPartitioner)
        .step(slaveStep)
        .gridSize(10)               // 分成 10 個分片
        .taskExecutor(taskExecutor()) // 用執行緒池並行執行
        .build();
}

// 分片策略：按 ID 範圍分片
@Bean
public Partitioner orderPartitioner(DataSource dataSource) {
    return gridSize -> {
        Map<String, ExecutionContext> partitions = new HashMap<>();
        long totalOrders = countOrders(dataSource);
        long partitionSize = totalOrders / gridSize;

        for (int i = 0; i < gridSize; i++) {
            ExecutionContext context = new ExecutionContext();
            context.putLong("minId", i * partitionSize + 1);
            context.putLong("maxId", (i + 1) * partitionSize);
            partitions.put("partition" + i, context);
        }
        return partitions;
    };
}
```

---

## 本章練習題

**Q1：Spring Batch 的 Chunk 模式為什麼能避免 OOM？**
<details>
<summary>答案</summary>
Chunk 模式每次只讀取 chunk_size 筆資料進記憶體（例如 1000 筆），處理完這批後提交事務，清空記憶體，再讀下一批。不管總資料量是 100 萬還是 1 億，記憶體裡永遠只有 chunk_size 筆資料。如果用普通的 for 迴圈 + findAll()，會一次把所有資料載入記憶體，10 億筆數據就會有 OOM 風險。JdbcPagingItemReader 更進一步，讀取時也是分頁查詢（每次 SQL 只取 1000 筆），資料庫側也沒有壓力。
</details>

**Q2：Spring Batch 的 JobParameters 為什麼每次都要帶不同的值？**
<details>
<summary>答案</summary>
Spring Batch 用 JobParameters 的組合來識別「一次 Job 執行」。如果 JobParameters 完全相同，Spring Batch 認為這是同一次 Job 的重試（FAILED 的才能重試），而不是全新的執行。如果 Job 上次是 COMPLETED，用完全相同的 JobParameters 再跑一次，Spring Batch 會拒絕執行（拋 JobInstanceAlreadyCompleteException）。解法：加一個變動的參數，如 runTime = System.currentTimeMillis()，確保每次都是一個新的 JobInstance。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 63 章 | Phase 9：微服務與分散式架構
> 下一章（第 64 章）：[第六十八章：Netty 網路框架深入](68_第六十八章_Netty網路框架深入.md)
