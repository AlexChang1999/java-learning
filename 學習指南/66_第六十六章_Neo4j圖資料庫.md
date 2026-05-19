# 第六十六章：Neo4j 圖資料庫

## 前言：關係本身也是資料

```
場景：電商推薦系統，要找「和你購買行為相似的用戶，他們還買了什麼」

MySQL 的做法（6 度分隔查詢的噩夢）：
  SELECT DISTINCT p.id FROM products p
  JOIN order_items oi1 ON p.id = oi1.product_id
  JOIN orders o1 ON oi1.order_id = o1.id
  JOIN orders o2 ON o1.customer_id != o2.customer_id
  JOIN order_items oi2 ON o2.id = oi2.order_id
  WHERE oi2.product_id IN (
    SELECT product_id FROM order_items WHERE order_id IN (
      SELECT id FROM orders WHERE customer_id = ?
    )
  );
  → 多層 JOIN，數據量大時極慢，SQL 難以維護

Neo4j 的做法（Cypher 查詢語言）：
  MATCH (me:User {id: $userId})-[:PURCHASED]->(:Product)<-[:PURCHASED]-(similar:User)
        -[:PURCHASED]->(recommend:Product)
  WHERE NOT (me)-[:PURCHASED]->(recommend)
  RETURN recommend, COUNT(*) AS score
  ORDER BY score DESC
  LIMIT 10;
  → 圖遍歷，無論幾層都一樣直觀，性能幾乎不受關係深度影響
```

---

## 一、圖資料庫核心概念

```
圖（Graph）= 節點（Node）+ 邊（Relationship）+ 屬性（Property）

Neo4j 的資料模型：

  (Alice:User {name:"Alice", age:28})
   ↑ 節點       ↑ 標籤       ↑ 屬性

  (Alice)-[:FOLLOWS {since:"2023-01-15"}]->(Bob)
           ↑ 關係類型  ↑ 關係屬性            ↑ 另一個節點

  關係的特點：
  ✅ 有方向（但查詢時可以忽略方向）
  ✅ 有類型（FOLLOWS, PURCHASED, LIKES...）
  ✅ 可以有屬性（since, weight, score...）
  ✅ 關係是一等公民，不是外鍵！（有自己的索引）
```

---

## 二、Cypher 查詢語言

```cypher
// ===== 建立節點 =====
CREATE (alice:User {id: "U001", name: "Alice", age: 28, city: "Taipei"})
CREATE (bob:User {id: "U002", name: "Bob", age: 32, city: "Kaohsiung"})
CREATE (p1:Product {id: "P001", name: "Java 入門書", category: "書籍", price: 450})
CREATE (p2:Product {id: "P002", name: "筆記型電腦", category: "電子", price: 35000})

// ===== 建立關係 =====
MATCH (a:User {id: "U001"}), (b:User {id: "U002"})
CREATE (a)-[:FOLLOWS {since: date("2023-01-15")}]->(b)

MATCH (u:User {id: "U001"}), (p:Product {id: "P001"})
CREATE (u)-[:PURCHASED {quantity: 1, purchasedAt: datetime()}]->(p)

// ===== 查詢：基本 MATCH =====
// 找 Alice 關注的所有用戶
MATCH (alice:User {name: "Alice"})-[:FOLLOWS]->(following:User)
RETURN following.name, following.city

// 找某商品的所有購買者
MATCH (u:User)-[:PURCHASED]->(p:Product {name: "Java 入門書"})
RETURN u.name, u.city ORDER BY u.name

// ===== 查詢：多跳遍歷 =====
// 朋友的朋友（2 跳）
MATCH (alice:User {name: "Alice"})-[:FOLLOWS*2]->(fof:User)
WHERE alice <> fof   // 排除自己
RETURN DISTINCT fof.name

// 1 到 3 跳之內的所有關係（可變長度路徑）
MATCH (alice:User {name: "Alice"})-[:FOLLOWS*1..3]->(u:User)
RETURN DISTINCT u.name, u.city

// ===== 推薦查詢：協同過濾 =====
// 找和 Alice 買了相同商品的用戶，推薦他們買的其他商品
MATCH (alice:User {name: "Alice"})-[:PURCHASED]->(p:Product)
      <-[:PURCHASED]-(similar:User)-[:PURCHASED]->(recommend:Product)
WHERE NOT (alice)-[:PURCHASED]->(recommend)  // 排除 Alice 已買過的
RETURN recommend.name,
       COUNT(DISTINCT similar) AS supporters,  // 有幾個相似用戶買過
       COLLECT(DISTINCT similar.name) AS who_bought
ORDER BY supporters DESC
LIMIT 10

// ===== 最短路徑 =====
// 找 Alice 和 Charlie 之間的最短社交路徑
MATCH path = shortestPath(
    (alice:User {name: "Alice"})-[:FOLLOWS*]-(charlie:User {name: "Charlie"})
)
RETURN path, length(path) AS degrees_of_separation

// ===== 更新節點屬性 =====
MATCH (u:User {name: "Alice"})
SET u.city = "Taichung", u.updatedAt = datetime()
RETURN u

// ===== 刪除 =====
MATCH (u:User {name: "Alice"})-[r:FOLLOWS]->(:User)
DELETE r   // 只刪關係

MATCH (u:User {id: "U001"})
DETACH DELETE u   // 連同關係一起刪除節點
```

---

## 三、Spring Boot 整合

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-neo4j</artifactId>
</dependency>
```

```yaml
# application.yml
spring:
  neo4j:
    uri: bolt://localhost:7687
    authentication:
      username: neo4j
      password: password
```

```java
// 定義節點實體
@Node("User")
public class UserNode {

    @Id
    @GeneratedValue
    private Long id;

    @Property("userId")
    private String userId;

    private String name;
    private Integer age;
    private String city;

    // 定義關係：Alice FOLLOWS Bob
    @Relationship(type = "FOLLOWS", direction = Relationship.Direction.OUTGOING)
    private List<UserNode> following = new ArrayList<>();

    // 定義帶屬性的關係
    @Relationship(type = "PURCHASED")
    private List<PurchasedRelationship> purchases = new ArrayList<>();
}

// 帶屬性的關係實體
@RelationshipProperties
public class PurchasedRelationship {

    @RelationshipId
    private Long id;

    @TargetNode
    private ProductNode product;

    private Integer quantity;
    private LocalDateTime purchasedAt;
}

// 定義商品節點
@Node("Product")
public class ProductNode {
    @Id @GeneratedValue
    private Long id;
    private String productId;
    private String name;
    private String category;
    private BigDecimal price;
}

// Repository
public interface UserRepository extends Neo4jRepository<UserNode, Long> {

    Optional<UserNode> findByUserId(String userId);

    // 使用自訂 Cypher 查詢
    @Query("""
        MATCH (u:User {userId: $userId})-[:PURCHASED]->(:Product)
              <-[:PURCHASED]-(similar:User)-[:PURCHASED]->(rec:Product)
        WHERE NOT (u)-[:PURCHASED]->(rec)
        RETURN rec, COUNT(similar) AS score
        ORDER BY score DESC
        LIMIT $limit
        """)
    List<ProductNode> findRecommendations(String userId, int limit);

    @Query("""
        MATCH path = shortestPath(
            (a:User {userId: $fromId})-[:FOLLOWS*]-(b:User {userId: $toId})
        )
        RETURN length(path) AS distance
        """)
    Optional<Integer> findShortestDistance(String fromId, String toId);
}

// Service
@Service
public class RecommendationService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<ProductNode> recommend(String userId) {
        return userRepository.findRecommendations(userId, 10);
    }

    @Transactional
    public void recordPurchase(String userId, String productId) {
        // 建立購買關係
        UserNode user = userRepository.findByUserId(userId)
            .orElseThrow(() -> new UserNotFoundException(userId));

        // ... 建立關係並儲存
        userRepository.save(user);
    }
}
```

---

## 四、典型應用場景

### 欺詐偵測（最高價值場景之一）

```cypher
// 找出共用同一個銀行帳號的多個「不同」用戶（可能是欺詐環）
MATCH (u1:User)-[:HAS_ACCOUNT]->(acc:BankAccount)<-[:HAS_ACCOUNT]-(u2:User)
WHERE u1 <> u2
WITH acc, COLLECT(DISTINCT u1) + COLLECT(DISTINCT u2) AS users
WHERE SIZE(users) > 3    // 超過 3 個用戶共用同一帳號，可疑！
RETURN acc.accountNumber, SIZE(users) AS userCount

// 找出設備指紋相同的用戶（同一台機器創多個帳號）
MATCH (u:User)-[:USED_DEVICE]->(d:Device)<-[:USED_DEVICE]-(other:User)
WHERE u <> other
RETURN d.fingerprint, COLLECT(u.userId) AS suspiciousUsers
```

### 知識圖譜查詢

```cypher
// 找出「Spring Boot」相關的所有技術（2 跳內的相關概念）
MATCH (t:Technology {name: "Spring Boot"})-[:RELATED_TO*1..2]->(related:Technology)
RETURN DISTINCT related.name, related.category
ORDER BY related.category
```

---

## 五、Neo4j vs RDBMS 選型

| 查詢類型 | MySQL JOIN | Neo4j 圖遍歷 |
|---------|-----------|-------------|
| 1 跳關係（直接好友）| 快 | 快 |
| 2 跳關係（朋友的朋友）| 慢（二次 JOIN）| 快 |
| 3+ 跳（深度遍歷）| 極慢 / 超時 | 依然快 |
| 最短路徑 | 非常困難 | 原生支援 |
| 圖算法（PageRank, 社群偵測）| 無法做 | 原生支援 |

**選 Neo4j 的情況：**
- 核心業務是「關係網路」（社交、推薦、欺詐偵測、知識圖譜）
- 需要多跳圖遍歷（2 跳以上）
- 需要圖算法（最短路徑、PageRank、社群發現）

**不選 Neo4j 的情況：**
- 關係是固定的（只有 1 跳 JOIN），RDBMS 就夠
- 主要是聚合分析（SUM/GROUP BY），用 ClickHouse
- 大多數業務邏輯不涉及網路關係

---

## 本章練習題

**Q1：Neo4j 的關係為什麼比 MySQL 的外鍵更適合多跳查詢？**
<details>
<summary>答案</summary>
MySQL 的外鍵本質是一個欄位值，多跳查詢需要多次 JOIN，每次 JOIN 都要掃描對應的索引，隨著跳數增加，掃描量是指數級增長。Neo4j 的關係是真正的「指針」：每個節點直接存儲它的所有關係的物理地址（指針），遍歷關係只需要跟著指針走，不需要索引查找。所以圖資料庫的多跳查詢複雜度是 O(關係數量)，而 MySQL 的多跳 JOIN 是 O(表大小 × 跳數)。
</details>

**Q2：設計一個「職業社交網路（LinkedIn 類似）」需要哪些節點和關係？**
<details>
<summary>答案</summary>
節點：(Person) {name, headline, location}，(Company) {name, industry, size}，(Skill) {name, category}，(Job) {title, salary, location}。關係：(Person)-[:WORKS_AT {from, to, title}]->(Company)，(Person)-[:KNOWS]->(Person)（人脈）；(Person)-[:HAS_SKILL {level}]->(Skill)，(Job)-[:REQUIRES]->(Skill)，(Company)-[:POSTED]->(Job)。典型查詢：找「我的二度人脈中在 Google 工作的人」→ MATCH (me)-[:KNOWS*2]->(target)-[:WORKS_AT]->(:Company {name:"Google"})；找「和我技能相符的工作」→ MATCH (me:Person {id:$id})-[:HAS_SKILL]->(s:Skill)<-[:REQUIRES]-(job:Job)。
</details>

---

<!-- NAV_FOOTER_START -->
> 學習順序第 40 章 | Phase 5：進階後端技術
> 下一章（第 41 章）：[第三十二章：Docker 容器化](32_第三十二章_Docker容器化.md)
