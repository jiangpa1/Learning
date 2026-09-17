# JWT 双 Token 实现设计文档（决策已定版）

> 项目：Learning（Spring Boot 2.7.18 + Redis）
> 决策：**A2（access token 也加黑名单）+ B2（refresh token 轮转）+ fail-closed（黑名单查询失败即拒绝）**
> 日期：2026-09-17
> 前置阅读：`md/JWT双Token设计文档.md`（方案对比版）

---

## 〇、决策记录（为什么要这么选）

| 决策 | 选择 | 一句话理由 |
| --- | --- | --- |
| **A** | **A2**：登出时把 access token 也加入黑名单 | "点了退出但 30 分钟内还能操作"在用户视角是 bug，不是可接受的权衡 |
| **B** | **B2**：refresh token 每次续期都轮转 | 一次性凭证，用过即废；能限制泄露窗口 |
| **C** | **fail-closed**：黑名单查询失败就拒绝请求 | 安全控制的默认值必须是"失败时拒绝"，不是"失败时放行" |

**这三个选择共同决定了本设计的性格：安全优先于可用性。**

代价要在文档里写下来（不是缺陷，是知情的选择）：

- 每次业务请求多一次 Redis 查询（本地 Redis 亚毫秒级）
- Redis 不可用时，**已登录用户也访问不了接口**（会收到 503 而不是 401）
- refresh 轮转要求前端每次更新本地 refreshToken，漏了就掉线

---

## 一、Token 结构

两个 token **用同一个密钥签名**（`jwt.secret`），靠 `type` claim 区分。这是为了保证"验签逻辑只有一份"。

### 1.1 access token 的 payload

```json
{
  "id": 17,                  // userId（业务字段，方便直接取）
  "username": "zhangsan",
  "type": "access",          // ★ 必须校验
  "sub": "17",               // subject 也放 userId（拦截器现有逻辑就是从 sub 取）
  "iss": "learning",
  "iat": 1758000000,
  "exp": 1758001800          // +30 分钟
}
```

### 1.2 refresh token 的 payload

```json
{
  "id": 17,
  "username": "zhangsan",
  "type": "refresh",         // ★
  "sub": "17",
  "iss": "learning",
  "iat": 1758000000,
  "exp": 1758604800          // +7 天
}
```

### 1.3 ⚠️ 为什么 `type` 是必须的

两个 token 同密钥、同结构。**如果不校验 type，攻击者拿有效期 7 天的 refreshToken 去访问业务接口，验签和验期全部通过** —— 双 token 的设计当场作废，refreshToken 变成万能通行证。

所以：**签发时打标记，验证时两处都要查。**

| 验证位置 | 要求 |
| --- | --- |
| `JwtInterceptor`（业务接口） | `type` 必须等于 `"access"` |
| `/auth/refresh` | `type` 必须等于 `"refresh"` |

> 更严格的做法是两个 token 用**不同密钥**签名（连"结构相似"都不存在）。本项目用 `type` claim —— 实现简单，且能讲清原理。

---

## 二、Redis 数据结构与 TTL

### 2.1 Key 表

| key | 类型 | 值 | TTL | 作用 |
| --- | --- | --- | --- | --- |
| `learning:token:refresh:{userId}` | String | **refreshToken 的 SHA-256 哈希** | **= refresh 有效期（7 天）** | 当前唯一有效的 refresh 凭证 |
| `learning:token:blacklist:{tokenHash}` | String | `"1"` | **= 该 access token 的剩余有效期** | 已吊销的 access token |

### 2.2 三个设计要点

**① refresh key 用 `userId` 做 key，值是哈希**

- 用 `userId` 做 key → **一个用户只有一条有效 refresh token** → 新登录自动覆盖旧的 → **天然实现单端登录**
- 值是哈希而不是 token 本身 → 万一 Redis 被 dump，攻击者拿到的是哈希，**不能直接拿去用**

**② 黑名单的 TTL 是"剩余有效期"，不是固定值**

access token 一旦自然过期，黑名单条目就毫无意义。所以：

```java
long remaining = claims.getExpiration().getTime() - System.currentTimeMillis();
if (remaining > 0) {
    redis.set(tokenBlacklist(hash), "1", remaining, TimeUnit.MILLISECONDS);
}
```

⚠️ **两个边界**：
- `remaining <= 0`（token 本来就要过期了）→ **不用写黑名单**，写了也是立刻失效
- TTL 用 `MILLISECONDS`，别和 `SECONDS` 混用 —— 这是最容易写错的地方

**③ 哈希用 SHA-256（`MessageDigest`）**

```java
private String hashToken(String token) {
    try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    } catch (NoSuchAlgorithmException e) {
        throw new IllegalStateException("SHA-256 不可用", e);   // JDK 保证有，不可能发生
    }
}
```

> **为什么不用 MD5**：MD5 在这里只是"缩短 key"，不承担安全职责（token 本身高熵，不存在碰撞利用空间），所以用 `DigestUtils.md5DigestAsHex` 也完全够。
> **但面试时看到 MD5 容易引出"MD5 已不安全"的追问**，用 SHA-256 省事。这是"避免不必要的解释成本"。

### 2.3 `CacheKeys` 要加的方法

```java
public static String tokenRefresh(Long userId)   { return PREFIX + "token:refresh:"   + userId; }
public static String tokenBlacklist(String hash) { return PREFIX + "token:blacklist:" + hash;   }
```

---

## 三、类设计

### 3.1 `JwtProperties`（改）

```java
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
    private String secret;
    private Duration accessExpiration;    // 新：替代原来的 expiration
    private Duration refreshExpiration;   // 新
    private String issuer;
}
```

⚠️ **删掉 `expiration` 会让 `JwtUtils` 编译不过** —— 所以这一步和 3.2 必须同时改。

### 3.2 `JwtUtils`（改）

保留现有的构造器注入、`@PostConstruct` 初始化密钥、`parseToken` 逻辑。新增/调整：

```java
/** 签发 access token（有效期 = jwt.accessExpiration） */
public String generateAccessToken(Long userId, String username)

/** 签发 refresh token（有效期 = jwt.refreshExpiration） */
public String generateRefreshToken(Long userId, String username)

/** 解析并验签验期（保持不变） */
public Claims parseToken(String token)

/** 校验 token 类型 —— 两个地方都要用，所以抽出来 */
public boolean isAccessToken(Claims claims)
public boolean isRefreshToken(Claims claims)

/** 从 claims 取 userId（拦截器现有逻辑就是从 sub 取） */
public Long getUserId(Claims claims)

/** access token 的剩余毫秒数 —— 算黑名单 TTL 用 */
public long getRemainingMillis(Claims claims)

/** token 哈希 —— 给 TokenService 当 Redis key 用 */
public String hashToken(String token)
```

**内部实现建议**：把原来 `generateToken` 的主体抽成一个私有方法，两个公开方法传不同的有效期和 type：

```java
private String buildToken(Long userId, String username, Duration ttl, String type) { ... }
```

> 这样"签发逻辑只有一份"，以后加字段不会漏改一边。

### 3.3 `TokenPair`（新增，放 `vo` 或 `dto`）

登录和续期都返回它：

```java
@Data
public class TokenPair {
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;        // access token 有效毫秒数，前端据此提前刷新
}
```

> **为什么返回 `expiresIn`**：让前端能**在过期前主动刷新**，而不是等 401 才被动处理。体验差别很大 —— 用户不会感觉到"卡了一下"。

### 3.4 `TokenService`（新增，接口 + 实现）

**它是这次改造的核心，把"JWT 解析"和"Redis 状态"两件事合起来，对外只暴露业务语义。**

```java
public interface TokenService {

    /** 登录成功：签发双 token，并把 refreshToken 存入 Redis（覆盖旧值 = 单端登录） */
    TokenPair issue(Long userId, String username);

    /** 续期：校验并轮转 refreshToken，返回新的双 token。校验失败抛 401 */
    TokenPair refresh(String refreshToken);

    /** 登出：删 refreshToken（立即无法续期）+ 当前 accessToken 入黑名单（立即失效） */
    void logout(String accessToken);

    /** 拦截器用：这个 accessToken 是否已被吊销。
     *  ⚠️ 依赖 Redis 不可用时抛 503（fail-closed），不要返回 false */
    boolean isRevoked(String accessToken);
}
```

**职责边界**：

| 谁来 | 做什么 |
| --- | --- |
| `JwtUtils` | 只负责 JWT 本身的生成/解析/校验 |
| `TokenService` | 只负责 Redis 里的状态：存 refresh、比对、吊销、查黑名单 |
| `AuthController` | 只做参数接收和 `Result` 包装 |
| `JwtInterceptor` | 调 `jwtUtils` 解析 + 调 `tokenService` 查吊销 |

### 3.5 `AuthController`（改）

```java
@PostMapping("/login")
public Result<?> login(@Valid @RequestBody UserLoginDTO dto) {
    // 现有：Service 校验用户名密码 → 返回 UserVO
    // 改成：拿到 userId/username 后
    return Result.success(tokenService.issue(user.getId(), user.getUsername()));
}

@PostMapping("/logout")
public Result<?> logout(HttpServletRequest request) {
    String token = 从 Authorization 头剥离 "Bearer ";
    tokenService.logout(token);
    return Result.success();
}

@PostMapping("/refresh")
public Result<?> refresh(@RequestBody RefreshDTO dto) {     // { refreshToken }
    return Result.success(tokenService.refresh(dto.getRefreshToken()));
}
```

**`/auth/**` 已经在 `WebMvcConfig` 的放行名单里** ✅ —— `/logout` 和 `/refresh` 都能进去，不用改配置。

> ⚠️ **`/logout` 放行是必须的**：如果它走拦截器，那么"用已过期的 token 调登出"会被拦下，用户的 refreshToken 永远删不掉 —— 登不出去。放行 + 在 Controller 里自己解析，更健壮。

### 3.6 `JwtInterceptor`（改）

在现有"取 token → 验 token → 挂 attribute"之间，插入两步：

```java
try {
    Claims claims = jwtUtils.parseToken(token);

    // ★ 新增 ①：类型校验，防止 refreshToken 当 accessToken 用
    if (!jwtUtils.isAccessToken(claims)) {
        writeUnauthorized(response, "token 类型错误");
        return false;
    }

    // ★ 新增 ②：查黑名单（TokenService 内部对 Redis 异常抛 BusinessException(503)）
    if (tokenService.isRevoked(token)) {
        writeUnauthorized(response, "登录已失效，请重新登录");
        return false;
    }

    request.setAttribute(ATTR_USER_ID, jwtUtils.getUserId(claims));
    request.setAttribute(ATTR_USERNAME, claims.get("username", String.class));
    return true;

} catch (ExpiredJwtException e) { ... }     // 现有
  catch (SignatureException | MalformedJwtException e) { ... }   // 现有
  catch (JwtException e) { ... }             // 现有
```

**两个注意点：**

1. **`BusinessException` 不会被上面的 `catch (JwtException)` 吃掉** —— 它不继承 `JwtException`，会正常向上抛 ✅
2. **它会一路抛到 `GlobalExceptionHandler`，被统一处理成 `Result.build(503, ...)`** —— 这是**故意**的（见第六节）。拦截器里抛异常是能被 `@RestControllerAdvice` 接住的，因为它在 `DispatcherServlet.doDispatch` 的 try 块内。

### 3.7 `Result`（不用改，但要会用）

```java
Result.unauthorized(msg)   // 401 —— token 无效/过期/已登出  → 前端跳登录页
Result.build(503, msg, null)  // 503 —— 认证服务不可用        → 前端提示重试，不跳登录页
```

---

## 四、四条流程的详细时序

### 4.1 登录

```
POST /auth/login { username, password }
 ↓
AuthController.login
 ↓ UserService 校验密码（BCrypt.matches），拿到 UserVO
 ↓
TokenService.issue(userId, username)
 ├─ access  = jwtUtils.generateAccessToken(userId, username)
 ├─ refresh = jwtUtils.generateRefreshToken(userId, username)
 ├─ redis.set( tokenRefresh(userId),                 ← key: 用 userId
 │             jwtUtils.hashToken(refresh),           ← 值: 哈希
 │             refreshExpiration )                    ← TTL: 7 天
 │   ↑ 直接 set（不是 setIfAbsent）→ 覆盖旧值 → 单端登录
 └─ return new TokenPair(access, refresh, accessExpiration.toMillis())
 ↓
返回 { code:200, data:{ accessToken, refreshToken, expiresIn } }
```

### 4.2 访问业务接口

```
GET /article/1   Authorization: Bearer <access>
 ↓
JwtInterceptor.preHandle
 ├─ 取头 + 剥 "Bearer " + 非空校验            （现有）
 ├─ jwtUtils.parseToken(token)                （现有，验签+验期）
 ├─ jwtUtils.isAccessToken(claims) ?          ★ 新增
 │     否 → 401 "token 类型错误"
 ├─ tokenService.isRevoked(token) ?           ★ 新增
 │     是 → 401 "登录已失效"
 │     Redis 异常 → 抛 BusinessException(503) → 全局处理器 → 503
 ├─ request.setAttribute("userId", ...)       （现有）
 └─ return true
 ↓
Controller
```

### 4.3 续期（轮转）

```
POST /auth/refresh { refreshToken: R1 }
 ↓
TokenService.refresh(R1)
 ├─ claims = jwtUtils.parseToken(R1)
 │     失效/篡改 → 401
 ├─ jwtUtils.isRefreshToken(claims) ? 否 → 401 "token 类型错误"
 ├─ userId = jwtUtils.getUserId(claims)
 ├─ String stored = redis.get( tokenRefresh(userId) )
 ├─ stored != null && stored.equals( hashToken(R1) ) ?
 │     否 → 401 "登录已失效，请重新登录"        ★ 见第五节：这可能是登出/被顶/已轮转
 ├─ A2 = generateAccessToken(...)              ← 新 access
 ├─ R2 = generateRefreshToken(...)             ← 新 refresh（轮转）
 ├─ redis.set( tokenRefresh(userId), hashToken(R2), refreshExpiration )
 │     ↑ 覆盖 R1 的哈希 → R1 立即作废，即使用它自身还没过期
 └─ return new TokenPair(A2, R2, accessTtl)
 ↓
返回新的一对 token
```

### 4.4 登出

```
POST /auth/logout   Authorization: Bearer <access>
 ↓（/auth/** 已放行，不受拦截器影响）
AuthController.logout
 ├─ token = 从请求头剥离 "Bearer "
 ├─ claims = jwtUtils.parseToken(token)
 │     ⚠️ 已过期会抛 ExpiredJwtException —— 见下面"登出的两个边界"
 ├─ userId = jwtUtils.getUserId(claims)
 ↓
TokenService.logout(token)
 ├─ redis.delete( tokenRefresh(userId) )                  ← 立即无法续期
 ├─ long remaining = jwtUtils.getRemainingMillis(claims)
 ├─ if (remaining > 0)
 │     redis.set( tokenBlacklist(hashToken(token)), "1", remaining, MILLISECONDS )
 └─ （剩余 <= 0 就不用写黑名单了）
 ↓
200
```

**登出的两个边界要处理：**

| 情况 | 处理 |
| --- | --- |
| 带了**已过期**的 access token 来登出 | 应该**仍然成功**（用户的意图是登出，不是"证明 token 有效"）→ 捕获 `ExpiredJwtException`，**从异常里取出 claims**（`e.getClaims()`），照样删 refreshToken |
| 完全没带 token | 返回 401 或直接 200 都行 —— 建议 401，让前端知道要清本地状态 |

> **为什么"过期 token 也能登出"很重要**：用户通常是在"被踢出去"之后才想起点登出。如果这时返回 401、不删 refreshToken，那他的 refreshToken 还活着，**下次打开页面又自动登录回来了** —— 用户会觉得"退出登录根本没用"。

---

## 五、轮转的语义与「盗用检测」的边界（**重要，别写错**）

轮转后 Redis 里的值被覆盖，所以**任何人拿旧的 refreshToken 来续期都会失败**。这个"失败"可能来自三种完全不同的原因：

| 原因 | 场景 | 应该怎么处理 |
| --- | --- | --- |
| **① 正常轮转** | 用户续期过一次，旧值已被 R2 覆盖。但前端如果正确地存了 R2，就不会再用 R1 | 拒绝 ✅ |
| **② 新设备登录** | 用户在手机登录 → 覆盖了 Redis → 电脑上的 refreshToken 失效 | 拒绝 ✅ **这是单端登录的预期行为** |
| **③ 凭证被盗** | 攻击者偷了 R1，在用户轮转之后拿 R1 来续期 | 拒绝 ✅ **而且理应告警** |

**⚠️ 关键判断：第 ② 和 ③ 在服务端【无法区分】。**

两者的表现完全一样（传入的哈希 ≠ Redis 里的哈希），所以：

> **不要"因为不匹配就吊销该用户的所有会话"。** 那会把「用户在手机登录」这种正常操作，误判成「凭证泄露」并强制全端下线 —— 用户会莫名其妙地被踢。

**那"盗用检测"怎么落地？**

真正的重放检测需要**记住已用过的 token 哈希**（而不只是当前值），这样：
- 收到 `current` → 正常轮转
- 收到 `prev`（已用过的） → **说明是重放** → 判定泄露，吊销全部会话
- 都不匹配 → 普通失效

**本项目的取舍：不做重放检测。**

理由：
1. 要额外维护"已用 token 的历史"，还要处理**并发刷新**与**重放检测**的冲突 —— 前者要求"宽限"，后者要求"立即判定为攻击"，两者在同一个短时间窗里是矛盾的，需要引入"宽限期 + 相同新凭证复用"这类复杂机制
2. 对博客系统，**并发刷新用前端去重就解决了**（见第七节），没必要为它引入后端复杂度
3. 轮转本身已经把泄露窗口从"7 天"压到"下一次续期之前"，收益已经拿到了

> **面试时这样讲**："我实现了 refresh token 轮转 —— 每次续期用一次性的新凭证替换旧的，把泄露窗口压到最小。更完整的做法是记录已用凭证的历史来做重放检测，但要和并发刷新的宽限期共存，复杂度不低；本项目用前端刷新去重解决了并发问题，没有引入这套机制。"

**知道边界在哪、为什么不做** —— 比"我做了重放检测"但说不清竞态处理更有说服力。

---

## 六、fail-closed 的实现，以及「为什么必须区分 401 和 503」

### 6.1 `isRevoked` 的实现要点

```java
@Override
public boolean isRevoked(String accessToken) {
    String key = CacheKeys.tokenBlacklist(jwtUtils.hashToken(accessToken));
    try {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
    } catch (Exception e) {
        // ⚠️ 与缓存降级相反：这里是安全控制，查不到就【拒绝】，不能放行
        log.error("黑名单查询失败，按已吊销处理（fail-closed），key={}", key, e);
        throw new BusinessException(503, "认证服务暂时不可用，请稍后重试");
    }
}
```

### 6.2 ⚠️ 为什么不能用 401

这是本设计里**最容易忽略、但影响最大**的一处：

| 情况 | 返回 | 前端该做什么 |
| --- | --- | --- |
| token 真的无效/过期/已登出 | **401** | 用 refreshToken 换新的；换不到就**跳登录页** |
| **Redis 挂了，黑名单查不了** | **401** | ↑ 同上 → **用户被莫名登出，跳到登录页** ❌ |
| **Redis 挂了，黑名单查不了** | **503** | 提示"服务暂时不可用，请稍后重试"，**不跳登录页** ✅ |

**如果返回 401**：用户明明什么都没做错，只是恰好赶上 Redis 抖动，就被强制踢到登录页。他重新登录时又会失败（因为登录也要写 Redis）—— 体验是"整个系统坏了"。

**返回 503**：前端能明确区分"我的凭证有问题"和"服务端暂时有问题"，做出正确的提示。

> **这就是"降级策略"这一课的延伸**：不只是"失败时拒绝还是放行"，还要**把"失败"这件事准确地告诉调用方** —— 否则调用方会因为错误的信息做出错误的动作。

### 6.3 与缓存降级的对照（**这一条最值钱**）

同样一个 `try/catch`，两种相反的策略：

| | **缓存**（`ArticleServiceImpl`） | **黑名单**（`TokenService`） |
| --- | --- | --- |
| 依赖的性质 | **性能层** | **安全层** |
| 失败时 | **放行**（回源查 DB） | **拒绝**（抛 503） |
| 理由 | 缓存只是加速，挂了不该影响功能 | 安全控制挂了，放行等于控制失效 |
| 代价 | 接口慢一点 | 用户暂时访问不了 |
| 怎么选 | 问"这个依赖坏了，业务还能不能正常做？" | 问"这个依赖坏了，放行会不会造成安全问题？" |

**一句话版本（面试可以直接说）**：

> **"降级策略取决于依赖的性质 —— 性能层失败要放行保可用，安全层失败要拒绝保安全。同样是 try/catch，方向正好相反。"**

---

## 七、前端配合（后端接口定好了，前端要这么用）

后端只提供接口，但前端不做对，整套设计体验会很差。要点：

### 7.1 本地存什么

```
accessToken            → 内存（或 sessionStorage）
refreshToken           → localStorage（要跨刷新存活）
accessTokenExpiresAt   → localStorage（或内存）
```

### 7.2 请求拦截器：提前刷新

```
发请求前：
  if (now >= accessTokenExpiresAt - 60秒) {
      先刷新 → 拿到新 token → 再发请求
  }
```

**提前 60 秒刷新**，用户完全感觉不到。这比"等 401 再处理"体验好得多（后者会让用户感受到一次失败+重试）。

### 7.3 收到 401：刷新去重 ⭐ 必须做

```
收到 401：
  if (已经有刷新请求在进行中) {
      把当前请求挂起，等刷新结果 → 用新 token 重试
  } else {
      发起刷新
      成功 → 更新本地 token → 重试所有挂起的请求
      失败(401) → 清本地 token → 跳登录页
  }
```

**为什么必须去重**：页面上 5 个请求同时 401 → 5 次并发刷新 → **轮转下只有第一个成功，其余全失败** → 用户莫名掉线。

### 7.4 收到 503：**不要跳登录页**

```
收到 503：
  提示"服务暂时不可用，请稍后重试"
  保留本地 token（凭证没坏！）
  可做有限次数的自动重试
```

---

## 八、配置

```yaml
# application-local.yml
jwt:
  secret: ${JWT_SECRET}
  issuer: learning
  access-expiration: 30m      # Spring Boot 的 Duration：支持 30m / 7d / PT30M
  refresh-expiration: 7d
```

> 原来的 `expiration: 3600000` 靠"纯数字被当毫秒"这条隐式规则 —— 写成 `30m` 一眼就懂，也不容易改错。

**取值建议**：

| 参数 | 建议 | 理由 |
| --- | --- | --- |
| access 有效期 | **30 分钟** | 短到泄露可控；长到不会频繁刷新（配合前端提前刷新，用户无感） |
| refresh 有效期 | **7 天** | 对应"一周内不用重新登录"的体验预期 |

---

## 九、踩坑清单

| # | 坑 | 后果 | 避免方式 |
| --- | --- | --- | --- |
| 1 | **不校验 `type`** | refreshToken 能当 access 用，双 token 设计白做 | 两处都校验 |
| 2 | **黑名单 TTL 用了固定值** | key 一直堆积到 7 天，Redis 白占内存 | TTL = `exp - now`；`remaining <= 0` 就不写 |
| 3 | **TTL 单位写错**（`SECONDS` vs `MILLISECONDS`） | 黑名单条目瞬间消失（黑名单失效）或存活 7 天（白占内存） | 算出来的是毫秒，就传 `MILLISECONDS` |
| 4 | **`/logout` 走拦截器** | 用过期的 token 登不出去，refreshToken 删不掉 | `/auth/**` 放行（已满足），Controller 里自己解析 |
| 5 | **登出时 token 已过期就抛异常返回 401** | 用户点了登出，但 refreshToken 还在 → 刷新页面又自动登录回来 | 捕获 `ExpiredJwtException`，从 `e.getClaims()` 取信息继续删 |
| 6 | **`hashToken` 里 `NoSuchAlgorithmException` 没处理** | 编译不过（受检异常） | try/catch 包成 `IllegalStateException` |
| 7 | **`refresh` 用 `setIfAbsent` 而不是 `set`** | 第二次登录存不进去，轮转也覆盖不了 → 旧 token 一直有效 | 用 `set`（覆盖是**期望行为**：单端登录 + 轮转） |
| 8 | **`isRevoked` 写成 fail-open** | Redis 一抖，所有已登出的 token 复活 | 捕获异常 → 抛 503，**不要 return false** |
| 9 | **`isRevoked` 返回 401 而不是 503** | Redis 抖动时用户被强制跳登录页 | 用 503，让前端能区分 |
| 10 | **`JWT_SECRET` 改了** | 所有 token 立即失效 = 强制全员下线 | 知道这个后果再改；换 secret 前先通知 |
| 11 | **`JwtUtils` 里 `secretKey` 初始化晚于使用** | `keys.hmacShaKeyFor` 拿到 null → NPE | 保持现有的 `@PostConstruct` 初始化 |
| 12 | **HS256 密钥短于 32 字节** | 启动抛 `WeakKeyException` | 保持现有的环境变量方案，并在文档里写明"至少 32 字符" |

---

## 十、验证清单

### 10.1 基础流程 + type 校验（⭐ 最容易错）

```
① 登录 → 拿到 accessToken / refreshToken / expiresIn
② GET /article/1  携带 accessToken            → 200
③ GET /article/1  携带 refreshToken           → 【401 应】★ 验证 type 校验
④ POST /auth/refresh  传 accessToken          → 【401 应】★ 反向验证
⑤ POST /auth/refresh  传 refreshToken         → 200，拿到新的一对
⑥ GET /article/1  携带【新】accessToken        → 200
⑦ GET /article/1  携带【旧】accessToken        → 200（access 还没到期，正常）
```

### 10.2 轮转生效

```
① 登录 → A1 / R1
② refresh(R1) → A2 / R2          （R1 应已作废）
③ refresh(R1) 再来一次            → 【401 应】★ R1 已轮转，不能再用了
④ refresh(R2)                     → 200 ✅
```

### 10.3 登出（A2 的验证）

```
① 登录 → A1 / R1
② GET /article/1 携带 A1          → 200
③ POST /auth/logout 携带 A1
④ GET /article/1 携带 A1          → 【401 应】★ 黑名单生效（这是 A2 vs A1 的分界）
⑤ refresh(R1)                     → 【401 应】★ refresh 已删
⑥ 【边界】用一个已过期的 access 调 /auth/logout → 【200 应】★ 且 R 被删掉
```

### 10.4 单端登录

```
① 设备A 登录 → A_a / R_a
② 设备B 登录 → A_b / R_b（覆盖 Redis）
③ 设备B 访问接口 → 200
④ 设备A 用 A_a 访问 → 200（access 未过期，正常）
⑤ 设备A 用 R_a 调 refresh → 【401 应】★ R_a 已被覆盖
```

### 10.5 ⭐ fail-closed（决策 C 的验证）

```
① 登录 → 然后登出（确保黑名单里有条目）
② 确认 Redis 里存在 learning:token:blacklist:*
③ 【停掉 Redis】或把 spring.redis.port 改成 9999
④ 用任意 accessToken 访问 GET /article/1
     → 【应 503「认证服务暂时不可用」】，【不应】是 401、更不应是 200
⑤ 恢复 Redis，确认功能恢复
⑥ 【反例检查】如果你写成 fail-open，第 ④ 步会是 200 —— 登出的 token 复活了
```

**第 ④ 步同时验证了两件事**：fail-closed 生效了（不是 200）、且错误码是 503 不是 401。

### 10.6 Redis 里到底存了什么

在虚拟机上（本机没 redis-cli）：

```bash
redis-cli -h 127.0.0.1 -a <密码> KEYS "learning:token:*"
redis-cli -h 127.0.0.1 -a <密码> GET learning:token:refresh:17
redis-cli -h 127.0.0.1 -a <密码> TTL learning:token:refresh:17       # 应接近 7 天
redis-cli -h 127.0.0.1 -a <密码> TTL learning:token:blacklist:<hash> # 应 ≈ access 剩余有效期
```

**重点看黑名单的 TTL** —— 如果它是 604800（7 天）说明你写成了固定值；如果是 1800 左右就对了。

### 10.7 回归

**拦截器是所有接口的入口，改完必须回归现有接口**：

```
用户模块 4 个接口、文章 5 个、分类 4 个、评论 3 个 —— 逐个确认还能正常调
```

---

## 十一、动手顺序

| 步 | 做什么 | 为什么这个顺序 |
| --- | --- | --- |
| 1 | `JwtProperties` 拆两个有效期 + `application-local.yml` 改配置 | 会短暂编译不过 |
| 2 | `JwtUtils`：拆 `generateAccessToken`/`generateRefreshToken` + `type` claim + 4 个辅助方法 | 和第 1 步**同时完成**才能编译过 |
| 3 | `CacheKeys` 加两个 key 方法 | 无依赖，随时可加 |
| 4 | `TokenPair` + `TokenService` 接口与实现 | 此时还没有调用方，编译能过 |
| 5 | `AuthController`：改 `login` + 加 `logout`/`refresh` | 登录接口先跑通，拿到双 token |
| 6 | **先验证 10.1 的 ①②⑤⑥**（还不改拦截器）—— 确认签发/解析/续期都对 | 把问题隔离在 TokenService 里 |
| 7 | `JwtInterceptor` 加 type 校验 + 黑名单查询 | 影响所有接口，单独一步 |
| 8 | 验证 10.1 全文 + 10.2 ~ 10.6 全部 | |
| 9 | 回归 10.7 现有接口 | |
| 10 | 文档补一节「实现记录」（实际遇到什么、怎么解决的） | 面试素材 |

---

## 十二、这套设计能撑起的面试问答

| 问题 | 答案要点 |
| --- | --- |
| JWT 和 Session 的区别？ | 无状态、服务端不存、可跨服务；**代价就是退不掉、续不了** |
| **JWT 怎么实现登出？** | 无状态本身做不到 → refreshToken 存 Redis 变成可吊销；accessToken 加黑名单（**TTL = 剩余有效期**，自动清理） |
| 为什么要双 Token？ | 短期凭证（泄露可控、无需服务端存储）+ 长期凭证（必须可吊销）分开 |
| **refresh 为什么要轮转？** | 一次性凭证，用过即废 → 泄露窗口从"7 天"压到"下一次续期之前" |
| 轮转怎么防重放？ | 记录已用 token 的历史，收到 `prev` 即判定泄露并吊销会话。**但要和并发刷新的宽限期共存**，本项目用前端去重解决并发、没引入这套机制 |
| 黑名单会不会撑爆 Redis？ | **不会**：TTL = token 剩余有效期，过期即自动清理，条目数上界 ≈ "最近 30 分钟内登出的人数" |
| **Redis 挂了鉴权怎么办？** | ⭐ **fail-closed**：查不到就拒绝，返回 503 而不是 401 —— 因为黑名单是**安全控制**，放行等于控制失效；且 401 会让前端误判为"凭证无效"而跳登录页 |
| **你的降级策略是什么？** | ⭐ **分开讲**：缓存失败 **fail-open**（性能层，保可用），黑名单失败 **fail-closed**（安全层，保安全）。**降级方向取决于依赖的性质** |
| 并发刷新怎么办？ | 前端**刷新去重**：第一个 401 触发刷新，其他请求挂起等结果，避免多次轮转互相作废 |
| 为什么返回 `expiresIn`？ | 让前端**提前刷新**，用户无感；比"等 401 再重试"体验好 |

---

## 附：本次改造涉及的文件清单

| 文件 | 类型 |
| --- | --- |
| `properties/JwtProperties.java` | 改（拆两个有效期） |
| `utils/JwtUtils.java` | 改（两个签发方法 + type + 辅助方法） |
| `common/CacheKeys.java` | 改（加两个 key 方法） |
| `vo/TokenPair.java`（或 `dto/`） | **新增** |
| `service/TokenService.java` | **新增** |
| `service/impl/TokenServiceImpl.java` | **新增** |
| `controller/AuthController.java` | 改（login + logout + refresh） |
| `interceptor/JwtInterceptor.java` | 改（type 校验 + 黑名单） |
| `dto/RefreshDTO.java` | **新增**（`{ refreshToken }`） |
| `src/main/resources/application-local.yml` | 改（jwt 配置） |
| `md/JWT双Token设计文档.md` | 补充「实现记录」一节 |
