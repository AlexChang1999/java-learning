# 第五十三章：Elasticsearch 搜尋引擎

## 前言：為什麼 MySQL LIKE 不夠用？

```sql
-- MySQL 全文搜尋
SELECT * FROM products WHERE name LIKE '%Java 程式設計%';
```

這個查詢有兩個致命問題：
1. **效能**：`LIKE '%關鍵字%'` 無法使用索引，全表掃描，百萬筆資料秒級延遲
2. **相關性**：只能精確匹配，搜「程式設計」找不到「programming」，搜「Java入門」找不到「Java 基礎教學」

**Elasticsearch（ES）** 是基於倒排索引的分散式搜尋引擎，毫秒級全文搜尋、智慧相關性排序、支援中文分詞。

---

## 一、核心概念：倒排索引

普通索引：文件 ID → 內容
倒排索引：詞語 → [含有這個詞的文件 ID 列表]

```
文件：
  doc1: "Java 程式設計入門"
  doc2: "Java 進階開發"
  doc3: "Python 程式設計"

倒排索引：
  "Java"   → [doc1, doc2]
  "程式設計" → [doc1, doc3]
  "入門"    → [doc1]
  "進階"    → [doc2]

搜尋 "Java 程式設計"：
  → 找 "Java" = [doc1, doc2]
  → 找 "程式設計" = [doc1, doc3]
  → 交集/聯集 + 計算相關性分數（TF-IDF / BM25）
  → doc1 出現兩個詞，分數最高，排第一
```

### ES vs MySQL 概念對比

```
MySQL        →   Elasticsearch
Database     →   Index（索引）
Table        →   （ES 7.x+ 廢棄 Type，只有 Index）
Row          →   Document（文件，JSON 格式）
Column       →   Field（欄位）
Schema       →   Mapping（映射，定義欄位型別）
SELECT       →   Search API（DSL 查詢）
```

---

## 二、Spring Boot 整合 Elasticsearch

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-elasticsearch</artifactId>
</dependency>
```

```yaml
# application.yml
spring:
  elasticsearch:
    uris: http://localhost:9200
    username: elastic      # 若有安全設定
    password: yourpassword
```

### 定義 Document

```java
import org.springframework.data.elasticsearch.annotations.*;

@Document(indexName = "products")   // 對應 ES 的 index 名稱
@Setting(settingPath = "es/product-settings.json")  // 分詞器設定
public class ProductDocument {

    @Id
    private String id;

    @Field(type = FieldType.Text, analyzer = "ik_max_word",
           searchAnalyzer = "ik_smart")   // 中文分詞：ik_max_word 建索引，ik_smart 搜尋
    private String name;

    @Field(type = FieldType.Text, analyzer = "ik_max_word")
    private String description;

    @Field(type = FieldType.Keyword)   // Keyword：精確匹配，不分詞（用於篩選/排序）
    private String category;

    @Field(type = FieldType.Double)
    private Double price;

    @Field(type = FieldType.Integer)
    private Integer stock;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private LocalDateTime createdAt;

    @Field(type = FieldType.Keyword)
    private List<String> tags;
}
```

**Text vs Keyword 的關鍵區別：**

| 型別 | 用途 | 範例 |
|------|------|------|
| `Text` | 全文搜尋，會分詞 | 商品名稱、描述 |
| `Keyword` | 精確匹配、排序、聚合 | 分類、狀態、標籤 |

---

## 三、Repository：基本 CRUD 與搜尋

```java
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface ProductSearchRepository
    extends ElasticsearchRepository<ProductDocument, String> {

    // 方法名稱自動轉為 ES 查詢
    List<ProductDocument> findByCategory(String category);

    // 全文搜尋（name 或 description 包含關鍵字）
    List<ProductDocument> findByNameOrDescription(String name, String description);

    // 價格範圍
    Page<ProductDocument> findByPriceBetween(
        double minPrice, double maxPrice, Pageable pageable);
}
```

```java
@Service
public class ProductIndexService {

    private final ProductSearchRepository repository;

    // 新增/更新文件（資料庫有新商品時同步到 ES）
    public void index(Product product) {
        ProductDocument doc = ProductDocument.from(product);
        repository.save(doc);
    }

    // 批次索引（資料庫全量同步）
    public void indexAll(List<Product> products) {
        List<ProductDocument> docs = products.stream()
            .map(ProductDocument::from)
            .collect(Collectors.toList());
        repository.saveAll(docs);
    }

    // 刪除
    public void delete(String productId) {
        repository.deleteById(productId);
    }
}
```

---

## 四、ElasticsearchOperations：複雜查詢 <!-- 💡 進階 -->

```java
import org.springframework.data.elasticsearch.core.*;
import org.springframework.data.elasticsearch.core.query.*;
import co.elastic.clients.elasticsearch._types.query_dsl.*;

@Service
public class ProductSearchService {

    private final ElasticsearchOperations elasticsearchOperations;

    // 全文搜尋 + 多欄位 + 相關性排序
    public SearchResult<ProductDocument> search(String keyword,
                                                 String category,
                                                 double minPrice,
                                                 double maxPrice,
                                                 int page, int size) {
        // 建立複合查詢
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        // must：全文搜尋（影響相關性分數）
        if (keyword != null && !keyword.isBlank()) {
            boolQuery.must(m -> m
                .multiMatch(mm -> mm
                    .query(keyword)
                    .fields("name^3", "description^1")  // name 權重 3 倍
                    .type(TextQueryType.BestFields)
                )
            );
        }

        // filter：精確篩選（不影響相關性分數，有快取優化）
        if (category != null) {
            boolQuery.filter(f -> f
                .term(t -> t.field("category").value(category))
            );
        }
        boolQuery.filter(f -> f
            .range(r -> r.field("price").gte(JsonData.of(minPrice)).lte(JsonData.of(maxPrice)))
        );

        // 組裝查詢
        NativeQuery query = NativeQuery.builder()
            .withQuery(q -> q.bool(boolQuery.build()))
            .withPageable(PageRequest.of(page, size))
            .withSort(Sort.by(Sort.Direction.DESC, "_score"))  // 按相關性排序
            .withHighlightQuery(new HighlightQuery(
                new Highlight(List.of(new HighlightField("name"),
                                      new HighlightField("description"))),
                ProductDocument.class
            ))
            .build();

        SearchHits<ProductDocument> hits =
            elasticsearchOperations.search(query, ProductDocument.class);

        return SearchResult.from(hits);
    }
}
```

---

## 五、高亮顯示（Highlight）

搜尋「Java 程式」時，結果中把匹配的詞標紅：

```java
// 在 SearchResult 中取出 highlight
SearchHits<ProductDocument> hits = elasticsearchOperations.search(query, ProductDocument.class);

hits.forEach(hit -> {
    ProductDocument doc = hit.getContent();

    // 取得 highlight 片段（有 <em> 標籤包住匹配的詞）
    Map<String, List<String>> highlightFields = hit.getHighlightFields();
    List<String> nameHighlights = highlightFields.get("name");

    if (nameHighlights != null && !nameHighlights.isEmpty()) {
        // nameHighlights.get(0) 可能是："<em>Java</em> <em>程式</em>設計入門"
        System.out.println(nameHighlights.get(0));
    }
});
```

---

## 六、聚合（Aggregation）：統計分析 <!-- 💡 進階 -->

類似 SQL 的 `GROUP BY`，用來生成篩選面板（左側的「分類 / 品牌 / 價格區間」）：

```java
// 取得各分類的商品數量（用於前端篩選面板）
NativeQuery query = NativeQuery.builder()
    .withQuery(q -> q.matchAll(ma -> ma))
    .withAggregation("categories",
        Aggregation.of(a -> a
            .terms(t -> t.field("category").size(20))  // 取前 20 個分類
        )
    )
    .withAggregation("price_ranges",
        Aggregation.of(a -> a
            .range(r -> r
                .field("price")
                .ranges(
                    AggregationRange.of(ar -> ar.to("100")),
                    AggregationRange.of(ar -> ar.from("100").to("500")),
                    AggregationRange.of(ar -> ar.from("500").to("1000")),
                    AggregationRange.of(ar -> ar.from("1000"))
                )
            )
        )
    )
    .withMaxResults(0)   // 只要 aggregation，不要 hits（提升效能）
    .build();

SearchHits<ProductDocument> result =
    elasticsearchOperations.search(query, ProductDocument.class);
// 從 result.getAggregations() 取出分類計數和價格區間計數
```

---

## 七、資料同步：MySQL → Elasticsearch <!-- 🔴 資深 -->

ES 是搜尋索引，不是主資料庫。真實資料在 MySQL，需要同步到 ES。

### 方案一：雙寫（寫 DB 的同時寫 ES）

```java
@Service
public class ProductService {

    @Transactional
    public Product createProduct(ProductRequest req) {
        Product product = productRepository.save(Product.from(req));

        // 非同步寫入 ES（不阻塞 DB Transaction）
        CompletableFuture.runAsync(() ->
            productIndexService.index(product)
        );

        return product;
    }
}
```

**問題：** DB 寫成功但 ES 寫失敗 → 兩邊不一致

### 方案二：基於 Kafka 的非同步同步（推薦）<!-- 🔴 資深 -->

```
MySQL 寫入
    ↓ Debezium CDC（第44章）捕捉 Binlog 變更
    ↓ 發送到 Kafka Topic "product-changes"
    ↓
Kafka Consumer
    → 解析 CREATE / UPDATE / DELETE 事件
    → 寫入 Elasticsearch

優點：
  - DB 操作和 ES 操作完全解耦
  - ES 短暫宕機時，Kafka 幫你緩衝，恢復後自動補齊
  - 天然的最終一致性
```

```java
@KafkaListener(topics = "product-changes")
public void syncToEs(ProductChangeEvent event) {
    switch (event.getOperation()) {
        case CREATE, UPDATE ->
            productIndexService.index(event.getProduct());
        case DELETE ->
            productIndexService.delete(event.getProductId());
    }
}
```

### 全量重建索引

```java
@Scheduled(cron = "0 0 3 * * ?")  // 每天凌晨 3 點全量重建
public void rebuildIndex() {
    // 使用別名（Alias）實現零停機重建
    String newIndex = "products_" + System.currentTimeMillis();

    // 1. 建立新 index
    // 2. 把所有 MySQL 資料寫入新 index
    // 3. 切換 alias "products" 指向新 index（原子操作）
    // 4. 刪除舊 index
}
```

---

## 本章練習題

**Q1：ES 的 `Text` 和 `Keyword` 型別的根本差異是什麼？**
<details>
<summary>答案</summary>
Text 型別在建立索引時會先分詞（如「Java 程式設計」→ ["Java", "程式設計"]），支援全文搜尋，但不能精確匹配、排序或聚合。Keyword 型別不分詞，作為整體儲存，支援精確匹配（term query）、排序、聚合（group by），但不支援模糊搜尋。實際上一個欄位可以同時是 Text 和 Keyword（用 fields 映射），text 用來搜尋，keyword 子欄位用來排序/聚合。
</details>

**Q2：搜尋「Java程式」為什麼能找到「Java 程式設計入門」？**
<details>
<summary>答案</summary>
因為中文分詞（如 IK 分詞器）。建立索引時，「Java 程式設計入門」被分成 ["Java", "程式設計", "入門"] 等 token。搜尋時「Java程式」也被分詞成 ["Java", "程式"]。ES 找到同時含有 "Java" 和 "程式" 的文件，並根據匹配程度計算相關性分數排序結果。
</details>

**Q3：為什麼不直接把 Elasticsearch 當主資料庫？**
<details>
<summary>答案</summary>
ES 不是為 ACID 事務設計的，不保證強一致性（預設 near-real-time，寫入後約 1 秒才可搜尋）。ES 不支援 JOIN、外鍵、複雜事務。主要適合：全文搜尋、日誌分析、時序資料、聚合統計。主資料（訂單、帳戶、庫存）應存在 MySQL/PostgreSQL，ES 只是搜尋索引。
</details>
