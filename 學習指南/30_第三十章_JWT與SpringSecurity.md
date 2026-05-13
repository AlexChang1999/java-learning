# 第三十章：JWT 與 Spring Security

> **前置知識**：本章預設你已讀完第二十一章（Spring Boot 基礎）與第二十二章（REST API 設計）。
> **核心專案連結**：撮合引擎的下單 API（`POST /api/orders`）需要認證，只有登入的交易者才能下單，管理員才能查看所有訂單。

---

## 一、為什麼需要認證？

### 1-1 HTTP 是無狀態協定（Stateless）

你在撮合引擎送出一筆買單：

```
POST /api/orders
{ "symbol": "BTCUSDT", "side": "BUY", "price": 65000 }
```

伺服器收到這個請求——**它完全不知道這是誰送的**。

HTTP 協定的設計哲學是「每一次請求都是獨立的」，伺服器不保留任何前一次的記憶。這讓系統容易擴展，但也帶來一個根本問題：

> **問題**：伺服器要怎麼知道 `/api/orders` 的呼叫者是已登入的交易者，而不是任何路人？

### 1-2 兩種傳統解法

**方案一：Session（伺服器存狀態）**

```
登入時：伺服器產生 SessionID，存在記憶體，回傳 Cookie
每次請求：瀏覽器自動帶 Cookie → 伺服器比對 SessionID → 知道是誰

┌─────────────────────────────────────────────────────────┐
│                     Session 方案                        │
│                                                         │
│  用戶端                        伺服器                   │
│  ┌────────┐  POST /login       ┌──────────────────────┐ │
│  │        │ ────────────────▶  │ 1. 驗證帳密          │ │
│  │        │ ◀────────────────  │ 2. 建立 Session      │ │
│  │        │  Set-Cookie:       │    sessions["abc123"] │ │
│  │        │  JSESSIONID=abc123 │    = { user: "alice"} │ │
│  │        │                    └──────────────────────┘ │
│  │        │  GET /api/orders                            │ │
│  │        │  Cookie: JSESSIONID=abc123                  │ │
│  │        │ ────────────────▶  ┌──────────────────────┐ │
│  │        │                    │ 3. 查 sessions["abc"] │ │
│  │        │ ◀────────────────  │ 4. 找到 → 允許        │ │
│  └────────┘  200 OK            └──────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

Session 的**缺點**：
- 伺服器要存所有在線用戶的 Session，記憶體壓力大
- 如果你有多台伺服器（水平擴展），Session 要共享（需要 Redis 等方案）
- 撮合引擎這種高並發系統，Session 同步是瓶頸

**方案二：JWT Token（客戶端存狀態）**

```
┌─────────────────────────────────────────────────────────┐
│                     JWT Token 方案                      │
│                                                         │
│  用戶端                        伺服器                   │
│  ┌────────┐  POST /login       ┌──────────────────────┐ │
│  │        │ ────────────────▶  │ 1. 驗證帳密          │ │
│  │        │ ◀────────────────  │ 2. 產生 JWT，         │ │
│  │        │  { token: "eyJ.." }│    不存任何 Session   │ │
│  │        │                    └──────────────────────┘ │
│  │ 存 JWT │  GET /api/orders                            │ │
│  │ 在本機 │  Authorization: Bearer eyJ...              │ │
│  │        │ ────────────────▶  ┌──────────────────────┐ │
│  │        │                    │ 3. 驗證 JWT 簽名      │ │
│  │        │ ◀────────────────  │ 4. 簽名正確 → 允許   │ │
│  └────────┘  200 OK            └──────────────────────┘ │
│                                  ↑                      │
│                          不需要查資料庫！                 │
└─────────────────────────────────────────────────────────┘
```

**關鍵差異**：JWT 方案中，伺服器不存任何狀態。它只需要用密鑰驗證 Token 簽名是否合法即可。

---

## 二、JWT 原理

### 2-1 JWT 的三段結構

一個真實的 JWT 長這樣：

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
.
eyJzdWIiOiJhbGljZSIsInJvbGUiOiJUUkFERVIiLCJleHAiOjE3MDAwMDAwMDB9
.
SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

用點（`.`）分隔成三段：**Header.Payload.Signature**

```
┌─────────────────────────────────────────────────────────┐
│                    JWT 三段結構                         │
│                                                         │
│  eyJhbGci...    eyJzdWIi...    SflKxwRJ...              │
│  ─────────────  ─────────────  ─────────────            │
│    HEADER         PAYLOAD       SIGNATURE               │
│                                                         │
│  Base64 解碼 ↓   Base64 解碼 ↓   ↓ 不能反解             │
│                                                         │
│  {               {                                      │
│    "alg":"HS256"   "sub":"alice"   HMAC-SHA256(         │
│    "typ":"JWT"     "role":"TRADER"   Base64(header)     │
│  }                 "exp":1700000000  + "."              │
│                  }                  + Base64(payload),  │
│                                     secret_key          │
│                                   )                     │
└─────────────────────────────────────────────────────────┘
```

**重要觀念**：
- Header 和 Payload 只是 **Base64 編碼**，不是加密。任何人都能解碼看到內容。
- **絕對不能把密碼、信用卡號等敏感資料放進 Payload！**
- Signature 是用密鑰做的雜湊（HMAC-SHA256），**伺服器才知道密鑰**，所以別人無法偽造

### 2-2 JWT 驗證流程

```
┌─────────────────────────────────────────────────────────┐
│                   JWT 驗證流程                          │
│                                                         │
│  請求帶著 JWT 進來                                      │
│  Authorization: Bearer eyJhbGci...SflKx                 │
│                          │                              │
│                          ▼                              │
│              ┌─────────────────────┐                   │
│              │  1. 拆分三段         │                   │
│              │  header.payload.sig  │                   │
│              └──────────┬──────────┘                   │
│                         │                               │
│                         ▼                               │
│              ┌─────────────────────┐                   │
│              │  2. 重新計算簽名     │                   │
│              │  HMAC(header+payload,│                   │
│              │       my_secret_key) │                   │
│              └──────────┬──────────┘                   │
│                         │                               │
│                         ▼                               │
│              ┌─────────────────────┐                   │
│              │  3. 比對簽名         │                   │
│              │  計算結果 == sig?    │                   │
│              └──────┬──────┬───────┘                   │
│                   是 │      │ 否                        │
│                     ▼      ▼                            │
│                  繼續    拒絕 401                        │
│                     │                                   │
│                     ▼                                   │
│              ┌─────────────────────┐                   │
│              │  4. 檢查 exp 過期    │                   │
│              └──────┬──────┬───────┘                   │
│                 未過期│      │已過期                    │
│                     ▼      ▼                            │
│                  放行    拒絕 401                        │
└─────────────────────────────────────────────────────────┘
```

### 2-3 JWT 適合分散式系統的原因

撮合引擎可能同時有多台 matching-engine 實例在跑。JWT 的優勢在於：

- **無狀態驗證**：每台伺服器只需要知道 secret key，就能獨立驗證 Token
- **不需要集中的 Session 存儲**：水平擴展時不用擔心 Session 同步問題
- **效能好**：驗證是純計算（雜湊），不需要查資料庫

### 2-4 JWT 的缺點：無法主動失效

JWT 一旦發出，在過期前都是有效的。假設 alice 的帳號被盜，你想立刻讓她的 Token 失效——**做不到**，因為伺服器沒有存任何 Token 清單。

**常見處理方式**：
1. **縮短過期時間**：Token 只有 15 分鐘，搭配 refresh token 機制
2. **Token 黑名單**（折衷方案）：在 Redis 存已撤銷的 Token JTI（JWT ID）
3. **接受限制**：對安全要求不那麼高的系統，過期時間短就夠了

---

## 三、Spring Security 核心概念

### 3-1 SecurityFilterChain：請求的關卡

Spring Security 把驗證邏輯放在一系列 **Filter** 裡，每個請求進來都要依序通過這些 Filter，就像機場的安檢通道：

```
┌─────────────────────────────────────────────────────────┐
│              SecurityFilterChain 示意圖                 │
│                                                         │
│  HTTP 請求                                              │
│     │                                                   │
│     ▼                                                   │
│  ┌──────────────────────────────────────────────────┐  │
│  │  Filter 1: CorsFilter                            │  │
│  │  → 處理跨域（CORS）請求                          │  │
│  └──────────────────────────┬───────────────────────┘  │
│                             ▼                           │
│  ┌──────────────────────────────────────────────────┐  │
│  │  Filter 2: JwtAuthenticationFilter（我們自訂的） │  │
│  │  → 從 Header 取 JWT，驗證後設定 SecurityContext  │  │
│  └──────────────────────────┬───────────────────────┘  │
│                             ▼                           │
│  ┌──────────────────────────────────────────────────┐  │
│  │  Filter 3: AuthorizationFilter                   │  │
│  │  → 檢查這個 URL 需要什麼權限？用戶有嗎？         │  │
│  └──────────────────────────┬───────────────────────┘  │
│                             ▼                           │
│                       Controller                        │
└─────────────────────────────────────────────────────────┘
```

### 3-2 認證 vs 授權

這兩個概念很容易混淆，用現實比喻：

| 概念 | 英文 | 問的問題 | 撮合引擎例子 |
|------|------|---------|------------|
| 認證 | Authentication | **你是誰？** | alice 輸入帳密，確認她的身份 |
| 授權 | Authorization | **你能做什麼？** | alice 是 TRADER，只能下自己的單；admin 才能看全部訂單 |

```
┌─────────────────────────────────────────────────────────┐
│              認證（Authentication）流程                 │
│                                                         │
│  POST /api/auth/login                                   │
│  { username: "alice", password: "secret" }              │
│                │                                        │
│                ▼                                        │
│  ┌─────────────────────────┐                           │
│  │  UserDetailsService      │                           │
│  │  從 DB 載入 alice 的資料 │                           │
│  └─────────────┬───────────┘                           │
│                │                                        │
│                ▼                                        │
│  ┌─────────────────────────┐                           │
│  │  BCrypt 驗證密碼         │                           │
│  │  matches(輸入, DB雜湊)?  │                           │
│  └──────┬──────────┬───────┘                           │
│       是 │          │ 否                                │
│         ▼          ▼                                    │
│    產生 JWT     回傳 401                                 │
│                                                         │
│─────────────────────────────────────────────────────── │
│              授權（Authorization）流程                  │
│                                                         │
│  GET /api/orders/admin/all                              │
│  Authorization: Bearer eyJ...(alice 的 Token)           │
│                │                                        │
│                ▼                                        │
│  ┌─────────────────────────┐                           │
│  │  從 JWT 取出 alice 角色  │                           │
│  │  role = "TRADER"         │                           │
│  └─────────────┬───────────┘                           │
│                │                                        │
│                ▼                                        │
│  ┌─────────────────────────┐                           │
│  │  此端點需要 ROLE_ADMIN   │                           │
│  │  alice 是 TRADER → 不符 │                           │
│  └─────────────┬───────────┘                           │
│                │                                        │
│                ▼                                        │
│            回傳 403 Forbidden                           │
└─────────────────────────────────────────────────────────┘
```

### 3-3 UserDetailsService

Spring Security 需要一個「知道如何載入用戶資料」的元件。你要實作這個介面，告訴 Spring 怎麼從你的資料庫找用戶：

```java
// Spring Security 要求你實作這個介面
public interface UserDetailsService {
    UserDetails loadUserByUsername(String username)
        throws UsernameNotFoundException;
}
```

---

## 四、完整實作：撮合引擎 API 認證

### 4-1 加入依賴

```xml
<!-- pom.xml -->
<dependencies>
    <!-- Spring Security -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>

    <!-- JJWT：Java 的 JWT 函式庫（分三個套件） -->
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-api</artifactId>
        <version>0.12.3</version>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-impl</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>io.jsonwebtoken</groupId>
        <artifactId>jjwt-jackson</artifactId>
        <version>0.12.3</version>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

### 4-2 application.yml 設定

```yaml
# application.yml
# JWT 相關設定
jwt:
  # 密鑰：用環境變數覆蓋，不要把真實密鑰 commit 到 Git
  # 本地開發用預設值，正式環境設定 JWT_SECRET 環境變數
  secret: ${JWT_SECRET:dev-only-secret-key-change-in-production}
  # Token 有效時間（毫秒）：86400000 = 24 小時
  expiration: 86400000
```

> **安全提醒**：`${JWT_SECRET:預設值}` 這個語法表示「優先讀環境變數 JWT_SECRET，如果沒有就用預設值」。正式環境必須透過環境變數設定真實的密鑰。

### 4-3 JwtUtil — JWT 工具類

```java
package com.example.matchingengine.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * JWT 工具類：負責生成 Token、驗證 Token、從 Token 取出資訊
 *
 * @Component 讓 Spring 把它納入管理，可以用 @Autowired 注入
 */
@Component
public class JwtUtil {

    // 從 application.yml 讀取密鑰字串
    @Value("${jwt.secret}")
    private String secretString;

    // 從 application.yml 讀取過期時間（毫秒）
    @Value("${jwt.expiration}")
    private long expirationMs;

    /**
     * 把字串密鑰轉成 JJWT 需要的 SecretKey 物件
     * 私有方法，只在這個類內部使用
     */
    private SecretKey getSigningKey() {
        // HMAC-SHA256 需要至少 256 bits 的密鑰
        // Keys.hmacShaKeyFor 會幫我們把字串轉成符合規格的密鑰
        return Keys.hmacShaKeyFor(secretString.getBytes());
    }

    /**
     * 生成 JWT Token
     *
     * @param username 要存入 Token 的用戶名（放在 subject 欄位）
     * @param role     用戶角色（放在自訂 claim 裡）
     * @return 簽名後的 JWT 字串，例如 "eyJhbGci..."
     */
    public String generateToken(String username, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
            .subject(username)             // sub: 誰的 Token
            .claim("role", role)           // 自訂欄位：角色
            .issuedAt(now)                 // iat: 發行時間
            .expiration(expiry)            // exp: 過期時間
            .signWith(getSigningKey())     // 用密鑰簽名
            .compact();                    // 產生最終的字串
    }

    /**
     * 驗證 Token 是否合法（簽名正確且未過期）
     *
     * @param token JWT 字串
     * @return true = 合法，false = 不合法
     */
    public boolean validateToken(String token) {
        try {
            // parseSignedClaims 會同時驗證簽名和過期時間
            // 如果任何一項不符，就會拋出例外
            Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            // JwtException 涵蓋：簽名錯誤、Token 過期、格式錯誤等
            return false;
        }
    }

    /**
     * 從 Token 取出用戶名
     * 注意：呼叫前請先用 validateToken 確認 Token 合法
     *
     * @param token JWT 字串
     * @return 用戶名字串
     */
    public String getUsernameFromToken(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload()
            .getSubject();  // 取 sub 欄位
    }

    /**
     * 從 Token 取出角色
     *
     * @param token JWT 字串
     * @return 角色字串，例如 "TRADER" 或 "ADMIN"
     */
    public String getRoleFromToken(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload()
            .get("role", String.class);  // 取自訂的 role claim
    }
}
```

### 4-4 JwtFilter — 每次請求都執行的過濾器

```java
package com.example.matchingengine.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 驗證 Filter：每一個 HTTP 請求都會經過這裡
 *
 * OncePerRequestFilter 保證每個請求只執行一次（避免重複驗證）
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 核心方法：攔截請求，驗證 JWT，設定認證資訊
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        // 1. 從 HTTP Header 取出 Authorization 欄位
        //    格式：Bearer eyJhbGci...
        String authHeader = request.getHeader("Authorization");

        // 2. 如果沒有 Header，或格式不對，直接放行
        //    （沒有 Token 的請求會在後面的授權 Filter 被擋下來）
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. 取出 Token 字串（去掉 "Bearer " 前綴）
        String token = authHeader.substring(7);

        // 4. 驗證 Token
        if (jwtUtil.validateToken(token)) {
            // 5. Token 合法，取出用戶資訊
            String username = jwtUtil.getUsernameFromToken(token);
            String role = jwtUtil.getRoleFromToken(token);

            // 6. 建立 Spring Security 的認證物件
            //    SimpleGrantedAuthority 需要 "ROLE_" 前綴
            UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                    username,    // principal（用戶識別）
                    null,        // credentials（密碼，這裡不需要）
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
                    // 例如 role="TRADER" → "ROLE_TRADER"
                );

            // 7. 把認證資訊存入 SecurityContext
            //    後續的 Filter 和 Controller 都能讀到
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        // 8. 繼續往下一個 Filter 傳遞
        filterChain.doFilter(request, response);
    }
}
```

### 4-5 SecurityConfig — 安全設定主體

```java
package com.example.matchingengine.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 主設定類
 *
 * @EnableWebSecurity      啟用 Web 安全功能
 * @EnableMethodSecurity   啟用方法層級的安全（@PreAuthorize）
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Autowired
    private JwtFilter jwtFilter;  // 注入我們自訂的 JWT Filter

    /**
     * 定義哪些路徑需要認證，哪些可以公開存取
     *
     * SecurityFilterChain 是 Spring Security 的核心設定
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 關閉 CSRF 保護
            // REST API 用 JWT 不需要 CSRF（CSRF 是針對 Cookie/Session 的攻擊）
            .csrf(csrf -> csrf.disable())

            // 設定哪些端點需要認證
            .authorizeHttpRequests(auth -> auth
                // 登入和註冊端點：公開，不需要 Token
                .requestMatchers("/api/auth/**").permitAll()

                // 管理員端點：需要 ADMIN 角色
                .requestMatchers("/api/admin/**").hasRole("ADMIN")

                // 其他所有 API：需要登入（有效 Token 即可）
                .anyRequest().authenticated()
            )

            // 設定 Session 政策：STATELESS = 完全不使用 Session
            // 因為我們用 JWT，不需要 Session
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // 把我們的 JwtFilter 插入到 Spring Security 預設 Filter 的前面
            // UsernamePasswordAuthenticationFilter 是 Spring 預設的帳密驗證 Filter
            // 我們要在它之前執行 JWT 驗證
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 密碼加密器：使用 BCrypt 演算法
     *
     * BCrypt 的特點：
     * 1. 每次雜湊結果不同（加鹽），相同密碼雜湊後不一樣
     * 2. 計算速度故意很慢，讓暴力破解更困難
     * 3. 業界標準，Spring Security 預設推薦
     *
     * @Bean 讓 Spring 管理這個物件，其他地方可以 @Autowired
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

### 4-6 AuthController — 登入端點

```java
package com.example.matchingengine.controller;

import com.example.matchingengine.security.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 認證相關端點
 * 這個 Controller 的路徑在 SecurityConfig 設定為公開（permitAll）
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // 實際專案中這裡會注入 UserRepository
    // 為了簡潔，這裡用假資料示範

    /**
     * 登入端點
     * POST /api/auth/login
     * Body: { "username": "alice", "password": "secret" }
     *
     * 成功回傳：{ "token": "eyJhbGci..." }
     * 失敗回傳：401 Unauthorized
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        // TODO: 從 UserRepository 查詢用戶
        // User user = userRepository.findByUsername(request.getUsername())
        //     .orElseThrow(() -> new RuntimeException("用戶不存在"));

        // 示意：假設從 DB 取出的雜湊密碼
        String storedHashedPassword = "$2a$10$...";  // BCrypt 雜湊

        // 驗證密碼（BCrypt 比對）
        // passwordEncoder.matches(明文輸入, DB裡的雜湊)
        if (!passwordEncoder.matches(request.getPassword(), storedHashedPassword)) {
            return ResponseEntity.status(401)
                .body(Map.of("error", "帳號或密碼錯誤"));
        }

        // 密碼正確，生成 JWT
        // 第二個參數是角色，從 DB 的用戶資料取得
        String token = jwtUtil.generateToken(request.getUsername(), "TRADER");

        // 回傳 Token 給前端
        return ResponseEntity.ok(Map.of("token", token));
    }

    /**
     * 登入請求的資料結構（內部 record，簡潔）
     */
    public record LoginRequest(String username, String password) {}
}
```

### 4-7 完整請求流程總覽

```
┌─────────────────────────────────────────────────────────┐
│                 完整 JWT 認證流程                        │
│                                                         │
│  步驟一：登入取得 Token                                  │
│                                                         │
│  前端          AuthController      資料庫               │
│   │                                                     │
│   │  POST /api/auth/login                               │
│   │  { username, password }                             │
│   │──────────────────────▶│                             │
│   │                       │──查詢用戶──▶│              │
│   │                       │◀──用戶資料──│              │
│   │                       │  BCrypt 驗證密碼            │
│   │                       │  生成 JWT                   │
│   │◀──────────────────────│                             │
│   │  { "token": "eyJ..." }│                             │
│   │ 存入 localStorage      │                            │
│                                                         │
│  步驟二：帶 Token 呼叫 API                               │
│                                                         │
│  前端        JwtFilter    OrderController               │
│   │                                                     │
│   │  POST /api/orders                                   │
│   │  Authorization: Bearer eyJ...                       │
│   │──────────────▶│                                     │
│   │               │ 取出 Token                          │
│   │               │ 驗證簽名 ✓                          │
│   │               │ 驗證未過期 ✓                        │
│   │               │ 設定 SecurityContext                 │
│   │               │──────────────────────▶│            │
│   │               │                       │ 處理下單    │
│   │◀──────────────│◀──────────────────────│            │
│   │  { 訂單建立成功 }                      │            │
└─────────────────────────────────────────────────────────┘
```

---

## 五、Role-Based Access Control（RBAC）

### 5-1 撮合引擎的角色設計

```
TRADER（交易者）：
  - 可以下自己的買/賣單
  - 可以查自己的訂單歷史
  - 不能看其他人的訂單

ADMIN（管理員）：
  - 可以查看所有訂單（市場監控）
  - 可以強制取消任何訂單
  - 可以管理用戶帳號
```

### 5-2 用 @PreAuthorize 保護端點

```java
package com.example.matchingengine.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * 訂單控制器
 * 展示如何用 @PreAuthorize 做細粒度的授權控制
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    /**
     * 下單：所有已登入的交易者都可以
     * Authentication 物件由 Spring 自動注入，包含當前用戶資訊
     */
    @PostMapping
    @PreAuthorize("hasRole('TRADER') or hasRole('ADMIN')")
    public ResponseEntity<?> createOrder(
            @RequestBody OrderRequest request,
            Authentication authentication) {

        // 從 Authentication 取得當前用戶名
        String currentUser = authentication.getName();

        // 確保只能以自己的身份下單
        // ...
        return ResponseEntity.ok("訂單建立成功");
    }

    /**
     * 查詢自己的訂單：TRADER 只能查自己的
     */
    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")  // 只要登入就能用
    public ResponseEntity<?> getMyOrders(Authentication authentication) {
        String currentUser = authentication.getName();
        // 只查詢 currentUser 的訂單
        return ResponseEntity.ok(List.of());
    }

    /**
     * 查詢所有訂單：只有 ADMIN 能用
     *
     * @PreAuthorize 在方法執行前檢查權限
     * 如果不符合，直接回傳 403 Forbidden
     */
    @GetMapping("/admin/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getAllOrders() {
        // 這裡的程式碼只有 ADMIN 能執行到
        return ResponseEntity.ok("所有訂單資料");
    }

    /**
     * 強制取消訂單：只有 ADMIN 能用
     */
    @DeleteMapping("/admin/{orderId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> forceCancel(@PathVariable Long orderId) {
        return ResponseEntity.ok("訂單已強制取消");
    }
}
```

---

## 六、常見安全漏洞與最佳實踐

### 6-1 不要把 Secret 寫死在程式碼裡

```java
// ❌ 錯誤：密鑰直接寫在程式碼裡
private String secret = "my-super-secret-key";

// ✅ 正確：從環境變數或 application.yml 讀取
@Value("${jwt.secret}")
private String secret;
```

在正式環境透過 Docker 或 CI/CD 設定環境變數：
```bash
# Docker
docker run -e JWT_SECRET=真實密鑰超過32個字元 your-app

# 或 .env 檔（不要 commit 到 Git！）
JWT_SECRET=真實密鑰超過32個字元
```

### 6-2 Token 過期時間設定原則

| 場景 | 建議過期時間 |
|------|------------|
| 一般 Web 應用 | 15 分鐘（搭配 refresh token） |
| 撮合引擎交易 | 1~4 小時（平衡安全與使用體驗） |
| 後台管理系統 | 30 分鐘（高敏感操作） |
| 開發測試 | 24 小時（方便調試） |

### 6-3 敏感資料絕對不進 Payload

```java
// ❌ 錯誤：把敏感資訊放進 JWT
String token = Jwts.builder()
    .claim("password", rawPassword)    // 絕對不行！
    .claim("creditCard", "4111-...")   // 絕對不行！
    .compact();

// ✅ 正確：只放非敏感的識別資訊
String token = Jwts.builder()
    .subject(username)        // 用戶名（可公開）
    .claim("role", role)      // 角色（可公開）
    .claim("userId", userId)  // 用戶 ID（可公開）
    .compact();
```

**記住**：JWT Payload 只是 Base64，任何人拿到 Token 都能解碼讀到內容。

### 6-4 HTTPS 強制使用

JWT 在網路傳輸時是明文（只有簽名保護完整性，但不保護機密性）。**正式環境必須用 HTTPS**，否則中間人攻擊可以直接竊取 Token。

---

## 七、本章重點整理

```
HTTP 無狀態
    │
    ├── Session 方案（伺服器存狀態）
    │       優點：可主動失效
    │       缺點：水平擴展困難
    │
    └── JWT 方案（客戶端存狀態）
            優點：無狀態、水平擴展友好
            缺點：無法主動失效

JWT 結構：Header.Payload.Signature
    Payload 是 Base64（非加密）→ 不存敏感資料
    Signature 用密鑰簽名 → 防竄改

Spring Security 核心：
    SecurityFilterChain → 請求的關卡
    Authentication（認證）→ 你是誰？
    Authorization（授權）→ 你能做什麼？

實作順序：
    JwtUtil → JwtFilter → SecurityConfig → AuthController
```

---

## 八、練習題

### 練習一：Token 過期後應該怎麼辦？

前端送出請求時收到 `401 Unauthorized`，後端日誌顯示 `JwtException: JWT expired`。

請問：
1. 這個錯誤發生在哪個元件裡？
2. 前端應該怎麼處理這個情況？
3. 如果要實作「無感刷新 Token」，大概需要什麼機制？

<details>
<summary>查看解答</summary>

**1. 錯誤發生在 `JwtFilter`。**
`jwtUtil.validateToken(token)` 呼叫 `Jwts.parser().parseSignedClaims()` 時，JJWT 函式庫發現 `exp` 已過期，拋出 `ExpiredJwtException`，被 `catch (JwtException e)` 捕捉，`validateToken` 回傳 `false`，Filter 不設定 `SecurityContext`，後續授權 Filter 發現沒有認證就回傳 401。

**2. 前端處理方式：**
```javascript
// 攔截所有 axios 回應
axios.interceptors.response.use(
  response => response,
  async error => {
    if (error.response?.status === 401) {
      // Token 過期，跳轉到登入頁
      localStorage.removeItem('token');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);
```

**3. 無感刷新 Token 機制（Refresh Token 方案）：**
- 登入時回傳兩個 Token：`accessToken`（短效，15分鐘）和 `refreshToken`（長效，7天）
- `refreshToken` 存在 HttpOnly Cookie（防止 XSS 竊取）
- 當 `accessToken` 過期（401），自動用 `refreshToken` 呼叫 `/api/auth/refresh` 換新的 `accessToken`
- 如果 `refreshToken` 也過期，才跳轉到登入頁

</details>

---

### 練習二：新增一個「查詢市場深度」的公開 API

撮合引擎需要一個端點 `GET /api/market/depth?symbol=BTCUSDT`，回傳買賣盤掛單資訊。這個 API **不需要登入**就能呼叫（像公開行情一樣）。

請修改 `SecurityConfig`，讓這個端點可以公開存取，但其他 `/api/**` 端點仍然需要認證。

<details>
<summary>查看解答</summary>

修改 `SecurityConfig` 的 `authorizeHttpRequests` 部分：

```java
.authorizeHttpRequests(auth -> auth
    // 登入/註冊：公開
    .requestMatchers("/api/auth/**").permitAll()

    // 市場行情：公開（不需要登入）
    .requestMatchers("/api/market/**").permitAll()

    // 管理員端點：需要 ADMIN 角色
    .requestMatchers("/api/admin/**").hasRole("ADMIN")

    // 其他所有 API：需要登入
    .anyRequest().authenticated()
)
```

**注意**：`requestMatchers` 的**順序很重要**，Spring Security 由上往下匹配，第一個符合的規則生效。要把具體路徑放在通用規則（`anyRequest()`）前面。

</details>

---

### 練習三：從 JWT 取出當前登入用戶的 ID

`OrderController.createOrder()` 需要把訂單與下單者的 `userId` 關聯起來。目前 JWT 的 Payload 裡只有 `username` 和 `role`，沒有 `userId`。

請修改 `JwtUtil.generateToken()` 加入 `userId`，並在 Controller 中取出使用。

<details>
<summary>查看解答</summary>

**Step 1：修改 `JwtUtil.generateToken()` 加入 userId**

```java
// 修改方法簽名，增加 userId 參數
public String generateToken(String username, String role, Long userId) {
    return Jwts.builder()
        .subject(username)
        .claim("role", role)
        .claim("userId", userId)   // 新增這行
        .issuedAt(new Date())
        .expiration(new Date(System.currentTimeMillis() + expirationMs))
        .signWith(getSigningKey())
        .compact();
}
```

**Step 2：新增 `getUserIdFromToken()` 方法**

```java
public Long getUserIdFromToken(String token) {
    return Jwts.parser()
        .verifyWith(getSigningKey())
        .build()
        .parseSignedClaims(token)
        .getPayload()
        .get("userId", Long.class);
}
```

**Step 3：在 JwtFilter 中把 userId 存入 Authentication 的 details**

```java
// JwtFilter.doFilterInternal() 中
UsernamePasswordAuthenticationToken authentication =
    new UsernamePasswordAuthenticationToken(
        username, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + role))
    );
// 把額外資訊存進 details
authentication.setDetails(jwtUtil.getUserIdFromToken(token));
```

**Step 4：在 Controller 中取出**

```java
@PostMapping
@PreAuthorize("isAuthenticated()")
public ResponseEntity<?> createOrder(
        @RequestBody OrderRequest request,
        Authentication authentication) {

    // 取出 userId（從 details）
    Long userId = (Long) authentication.getDetails();
    String username = authentication.getName();

    // 用 userId 建立訂單
    // orderService.createOrder(userId, request);

    return ResponseEntity.ok("訂單建立成功");
}
```

</details>
