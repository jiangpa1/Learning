# JWT 双 Token 设计文档

> 项目：Learning（Spring Boot 2.7.18）
> 目标：给现有的单 Token 鉴权补上**登出**与**续期**能力
> 日期：2026-09-17

---

## 一、先看现状有什么问题

当前实现（`JwtUtils` + `JwtInterceptor` + `WebMvcConfig`）：

```java
// 登录时签发一个 token，有效期 1 小时
jwt:
  secret: ${JWT_SECRET}
  expiration: 3600000      # 被当成毫秒 → 1 小时
  issuer: learning
```

`JwtInterceptor` 取 `Authorization: Bearer xxx` → `parseToken` 验签验期 → 把 `userId` 挂到 request。

**能跑，但有两个绕不过去的问题：**

| 问题 | 现象 | 根因 |
| --- | --- | --- |
| **① 退不掉** | 点了"退出登录"，旧 token 依然能访问接口直到自然过期 | JWT 是**无状态**的 —— 签发之后服务端不存任何东西，"注销"这个概念在服务端不存在 |
| **② 续不了** | 1 小时一到，用户正在写文章也被踢出去 | token 的有效期**签发时写死**，服务端无法延长 |

**② 的两种错误解法**（面试时值得提，说明你想过）：

- ❌ **把 expiration 设成 7 天**：等于把风险窗口也放大到 7 天 —— token 一旦泄露，攻击者能用一周
- ❌ **每次请求都重新签发 token**：新 token 怎么给前端？放在响应头里？前端每个请求都要检查响应头？而且**旧 token 依然有效到过期**，没有解决问题

**正确解法：双 Token（access token + refresh token）。**

---

## 二、两个 Token 的分工

| | **access token** | **refresh token** |
| --- | --- | --- |
| 用途 | 访问业务接口 | **只用来换新的 access token** |
| 有效期 | **短**（30 分钟） | **长**（7 天） |
| 放在哪 | `Authorization: Bearer xxx` 请求头 | 单独发给 `/auth/refresh`（body 或请求头） |
| 服务端存不存 | **不存**（保持无状态） | **存 Redis**（为了能吊销） |
| 泄露后果 | 最多被滥用 30 分钟 | 后果大，所以**必须能吊销** |

**核心思路**：把"长期凭证"和"日常通行证"分开。

- 日常通行证（access）短期有效，泄露了损失可控，所以**不需要服务端存**
- 长期凭证（refresh）必须**服务端可控** —— 登出、改密码、检测到盗用时能**立即作废**

> **为什么 refresh token 要存 Redis？** 因为 JWT 的无状态性正是"退不掉"的原因。让 refresh token 变成**有状态**的（存 Redis），就把"能吊销"这个能力找回来了 —— 而代价只有一次 Redis 查询（7 天一次，而不是每次请求）。

---

## 三、Redis 里存什么

### 3.1 Key 设计

| key | 类型 | 值 | TTL |
| --- | --- | --- | --- |
| `learning:token:refresh:{userId}` | String | 当前有效的 refresh token（或其哈希） | 7 天（= refresh 有效期） |
| `learning:token:blacklist:{tokenHash}` | String | `"1"` | **该 access token 的剩余有效期** |

**两条设计要点：**

1. **refresh key 用 `userId` 而不是 token 本身做 key。** 这样"一个用户只有一条有效 refresh token" —— 新登录会**覆盖**旧的，旧设备自动失效。这是**单端登录**语义。

   > 如果要支持**多端同时登录**（手机 + 电脑），key 要改成 `learning:token:refresh:{userId}:{deviceId}`，或者用 Redis Set 存多个。
   > **本设计选单端登录** —— 实现简单，而且"改密码 / 登出会踢掉所有设备"对博客系统是合理的安全默认值。

2. **黑名单存 token 的哈希，不存 token 本身。** JWT 动辄 200-300 字节，哈希（如 SHA-256 十六进制 64 字符、或 MD5 32 字符）短得多，而且**避免把可用凭证明文堆在 Redis 里**（万一 Redis 被 dump，哈希不能直接拿去用）。

3. **黑名单的 TTL = 该 token 的剩余有效期**，不是固定值。因为 access token 一过期，黑名单条目就没意义了 —— 让 Redis 自动清理，不需要定时任务。

### 3.2 Key 常量

沿用你已有的 `CacheKeys`（它已经管了文章缓存和锁）：

```java
public static String tokenRefresh(Long userId)     { return PREFIX + "token:refresh:" + userId; }
public static String tokenBlacklist(String hash)   { return PREFIX + "token:blacklist:" + hash; }
```

> 判断标准还记得吗：**同一个前缀不该出现在两处**。

---

## 四、四条完整流程

### 4.1 登录

```
POST /auth/login { username, password }
  → 校验用户名密码（现有逻辑，BCrypt.matches）
  → 签发 accessToken（30 分钟）+ refreshToken（7 天）
  → 把 refreshToken 写入 Redis：set(tokenRefresh(userId), refreshToken, 7天)
      ↑ 新登录会覆盖旧值 → 旧设备的 refreshToken 立即失效
  → 返回 { accessToken, refreshToken, expiresIn }
```

**返回什么给前端**：建议连 `expiresIn`（access token 的有效毫秒数）一起返回，前端就能**在过期前主动刷新**，而不是等到 401 才被动处理。

### 4.2 访问业务接口（现有流程 + 两处新增校验）

```
GET /article/1  Authorization: Bearer <accessToken>
  → JwtInterceptor：
       ① 解析 token（验签 + 验期）—— 现有逻辑
       ② ★ 校验 type 必须是 "access"    ← 新增，见第六节
       ③ ★ 查黑名单，命中则 401          ← 新增
       ④ 挂 userId / username 到 request —— 现有逻辑
```

### 4.3 续期

```
POST /auth/refresh  { refreshToken }
  → 解析 refreshToken（验签 + 验期）
  → 校验 type 必须是 "refresh"
  → 从 Redis 取出 tokenRefresh(userId)，比对是否与传入的一致
       不一致 → 401（说明已被登出 / 被新登录顶掉 / 或被轮转作废）
  → 签发新的 accessToken
  → ★ 是否同时签发新的 refreshToken 并覆盖 Redis？（见第六节决策 B）
  → 返回 { accessToken, (refreshToken), expiresIn }
```

### 4.4 登出

```
POST /auth/logout  Authorization: Bearer <accessToken>
  → 从 token 里取 userId → 删除 Redis 的 tokenRefresh(userId)   ← 立即无法续期
  → ★ 把当前 accessToken 加入黑名单，TTL = 剩余有效期              ← 见第六节决策 A
  → 返回 200
```

⚠️ **`/auth/logout` 必须放行**（加到 `WebMvcConfig` 的 `excludePathPatterns`）—— 否则"用过期 token 调登出"会被拦在外面，用户的 refreshToken 永远删不掉。

> 也可以让 logout 走拦截器（这样能拿到已解析的 userId），但要接受"token 过期就登不出去"的边界。**推荐放行 + 自己在 Controller 里解析**，更健壮。

---

## 五、需要改动的文件

| 文件 | 改动 | 说明 |
| --- | --- | --- |
| `JwtProperties` | `expiration` → 拆成 `accessExpiration` + `refreshExpiration` | |
| `JwtUtils` | 拆 `generateAccessToken` / `generateRefreshToken`；`parseToken` 保持不变；新增按 type 取值的辅助方法 | 两个 token 用**同一个密钥**签名，靠 `type` claim 区分 |
| **新增** `service/TokenService` | 管 refreshToken 的写入 / 校验 / 吊销 + 黑名单读写 | 把 Redis 细节从 Controller 里隔离出来 |
| `CacheKeys` | 加两个 key 方法 | |
| `JwtInterceptor` | 加 type 校验 + 黑名单查询 | |
| `AuthController` | 加 `/auth/logout`、`/auth/refresh` | |
| `WebMvcConfig` | 放行名单加 `/auth/**`（已包含）| 确认即可，不用改 |
| `application-local.yml` | 改 jwt 配置（见下） | |

### 配置改成显式单位（比原来更不容易出错）

```yaml
jwt:
  secret: ${JWT_SECRET}
  issuer: learning
  access-expiration: 30m      # Spring Boot 的 Duration 支持 30m / 7d / PT30M
  refresh-expiration: 7d
```

> 原来的 `expiration: 3600000` 靠"纯数字被当成毫秒"这一条隐式规则，很容易看错（是 1 小时还是 1 秒？）。写成 `30m` 一眼就懂。**这是个免费的改进。**

---

## 六、三个关键设计决策（要你自己定）

### 决策 A：access token 要不要也加黑名单？

| 方案 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **A1. 不管它** | 登出只删 refreshToken，access token 靠自身 30 分钟过期 | 实现最简单，Redis 零额外开销 | **登出后 30 分钟内，旧 access token 依然能用** |
| **A2. 加黑名单**（推荐） | 登出时把 access token 哈希写进黑名单，TTL = 剩余有效期 | 登出**立即生效** | 每次业务请求多一次 Redis 查询；Redis 里多一类 key |

**我的建议是 A2。** 理由：

- "点了退出登录，但 30 分钟内还能继续操作" —— 这在**用户视角是 bug**，不是"可接受的权衡"。用户改了密码、在公共电脑上登出，都期望**立刻**失效。
- 代价可控：每次请求多一次 Redis 查询（本地/内网 Redis 是亚毫秒级），而且条目随 TTL 自动清理，不会堆积。
- 面试价值：能讲清"access 短有效期"和"黑名单"**是互补的两道防线**，不是二选一。

> **如果你选 A1**，也要能在面试里说清代价——"我接受 30 分钟的风险窗口，换取零额外查询"。**知道自己在放弃什么**比选了哪个更重要。

### 决策 B：续期时要不要轮转 refreshToken？

**轮转（rotation）** = 每次续期都签发**新的** refreshToken 并让旧的作废。

| 方案 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| B1. 不轮转 | 7 天内一直用同一个 refreshToken | 简单 | 泄露后**攻击者能续期 7 天**，而且你发现不了 |
| **B2. 轮转**（推荐） | 每次续期发新的、旧的立即作废（覆盖 Redis） | **能检测盗用**（见下） | 前端必须每次更新本地存的 refreshToken |

**轮转最妙的地方是「盗用检测」**：

```
正常用户 A：refresh1 → 换到 refresh2（refresh1 作废）
             refresh2 → 换到 refresh3（refresh2 作废）

如果攻击者偷了 refresh1：
  攻击者用 refresh1 去续期 → Redis 里已经是 refresh3 了，不匹配 → 拒绝
  ↑ 而且这时候你可以判定「refresh1 被重放了」→ 直接把该用户整个会话全部吊销
                                           → 强制重新登录
```

**这就是"一次性的刷新凭证"** —— 用过即废，重复使用就意味着泄露。这是 OAuth 2.0 的推荐做法。

**代价**：前端每收到新 refreshToken 就必须覆盖本地的，漏了就掉线。而且要处理**并发刷新的竞态**（见第七节）。

### 决策 C：⭐ Redis 挂了，黑名单查不到怎么办？

**这是本设计里最值得想清楚的一点，因为它和你在缓存里做的降级策略正好相反。**

回顾你在 `ArticleServiceImpl` 里的降级原则：

> **缓存是"加速层"，不该变成"故障点"** → Redis 挂了就走 DB（**fail-open**）

但黑名单是**安全控制**，不是性能优化。同一个 try/catch，两种截然相反的策略：

| | 缓存降级 | 黑名单降级 |
| --- | --- | --- |
| 依赖的性质 | **性能层** | **安全层** |
| Redis 挂了怎么办 | **放行**（查 DB） | ？ |
| 选 fail-open | 接口慢一点，但可用 ✅ 对的 | **所有已登出的 token 全部复活** ❌ |
| 选 fail-closed | 缓存不可用时接口 500 ❌ 过度保守 | **拒绝请求**，安全 ✅ |

**建议：黑名单查询用 fail-closed（查不到/查询异常就拒绝）。**

理由：
- 放行的代价是**安全控制整体失效** —— 用户以为登出了，实际没有
- 拒绝的代价是"用户需要重新登录"，而 access token 只有 30 分钟，用户本来就要定期刷新，**重新登录的成本很低**
- 安全默认值应该是"失败时拒绝"，而不是"失败时放行"

**而且这个异常几乎不会发生** —— 你的 Redis 和 MySQL 在同一台虚拟机上，要么都通要么都不通。

> **这一条是整份文档最有面试价值的地方。** 面试时如果能把"同样是 try/catch，为什么缓存要 fail-open 而鉴权要 fail-closed"讲清楚，说明你理解的是**设计原则**，不是照抄模板。
>
> 一句话版本：**"降级策略取决于依赖的性质 —— 性能层失败要放行保可用，安全层失败要拒绝保安全。"**

---

## 七、踩坑清单

### 7.1 ⚠️ 必须校验 token 的 `type`，否则 refresh token 能当 access 用

如果两个 token 用同一个密钥签名、结构也完全一样，那么**拿 refreshToken 去访问业务接口，验签和验期都会通过** —— 一个有效期 7 天的凭证就变成了万能通行证，双 token 的设计彻底白做。

**必须在签发时打标记、在验证时检查：**

```java
// 签发
claims.put("type", "access");     // 或 "refresh"

// 校验（两处都要）
String type = claims.get("type", String.class);
if (!"access".equals(type)) → 401      // JwtInterceptor 里
if (!"refresh".equals(type)) → 401     // /auth/refresh 里
```

> 更严格的做法是**两个 token 用不同密钥**签名，这样连"结构相似"都不存在。但对本项目，`type` claim 够用且更容易讲清。

### 7.2 ⚠️ 并发刷新的竞态（B2 轮转下必须处理）

前端如果同时发了 5 个请求，access token 都过期了 → 5 个请求同时收到 401 → 前端同时发起 5 次 `/auth/refresh`：

```
请求1: 用 refresh2 换到 refresh3  ✅
请求2: 用 refresh2 换取 → Redis 里已是 refresh3 → 不匹配 → 401 ❌
请求3~5: 同上 ❌
```

**结果：用户莫名掉线。**

两个层面的处理：

- **前端（主要）**：加"刷新去重" —— 第一个 401 触发刷新，其他请求**挂起等结果**，刷新完成后统一用新 token 重试。这是标准做法。
- **后端（辅助，可选）**：给旧 refreshToken 一个**很短的宽限期**（如 30 秒内允许旧值再用一次），避免误杀。

> 本设计建议**只做前端去重**，后端不引入宽限期（会让"盗用检测"变模糊）。知道这个取舍在哪就行。

### 7.3 其他

- **`/auth/logout` 和 `/auth/refresh` 都要放行**（`/auth/**` 已在放行名单里 ✅）
- **refreshToken 也要有 `exp`**，不能只靠 Redis 的 TTL —— 两道保险，Redis 数据异常时还有 JWT 自身的过期兜底
- **改密码时也要删 refreshToken**（否则旧会话还能续期）
- **redis key 的 TTL 要显式设置** —— `set(key, value, duration, unit)`，别用无参 `set` 然后忘了 expire
- **`JWT_SECRET` 改了会导致所有 token 失效** —— 这在生产是"强制全员下线"，操作前要知道

---

## 八、验证方法

### 8.1 正常流程

```
① 登录 → 拿到 accessToken + refreshToken + expiresIn
② 用 accessToken 访问 GET /article/1      → 200
③ 用 refreshToken 访问 GET /article/1     → 【应 401】★ 关键，验证 type 校验
④ 用 accessToken 调 POST /auth/refresh    → 【应 401】★ 反向验证
⑤ 用 refreshToken 调 POST /auth/refresh   → 200，拿到新 accessToken
⑥ 用新 accessToken 访问 GET /article/1    → 200
```

**第 ③④ 步是这套设计最容易出错的地方 —— 一定要测。**

### 8.2 登出

```
① 登录拿到 token
② 访问 GET /article/1                     → 200
③ POST /auth/logout
④ 用【同一个】accessToken 访问 GET /article/1  → 【应 401】   ★ 黑名单生效
⑤ 用 refreshToken 调 /auth/refresh             → 【应 401】   ★ refresh 已被删
```

**第 ④ 步决定你选的是 A1 还是 A2** —— A1 下这里会是 200（还能用 30 分钟）。

### 8.3 单端登录（同账号互斥）

```
① 设备A 登录 → accessA / refreshA
② 设备B 登录 → accessB / refreshB（覆盖了 Redis）
③ 设备B 访问接口 → 200
④ 设备A 用 accessA 访问 → 200（access 还没过期，正常）
⑤ 设备A 用 refreshA 调 /auth/refresh → 【应 401】★ refreshA 已被覆盖
```

### 8.4 Redis 里到底存了什么

本机没 `redis-cli`，在虚拟机上执行：

```bash
redis-cli -h 127.0.0.1 -a <密码> KEYS "learning:token:*"
redis-cli -h 127.0.0.1 -a <密码> TTL learning:token:refresh:17
redis-cli -h 127.0.0.1 -a <密码> TTL learning:token:blacklist:<hash>
```

**重点确认黑名单的 TTL ≈ access token 的剩余有效期**（不是固定 7 天）。TTL 不对说明算剩余时间的逻辑写错了。

### 8.5 ⭐ 验证"安全层 fail-closed"

```
① 登录，然后登出
② 确认黑名单里有这条 key
③ 停掉 Redis
④ 用那个已登出的 accessToken 访问 GET /article/1
     → 【应 401（拒绝）】，而不是 200
⑤ 恢复 Redis
```

**如果你写成 fail-open，第 ④ 步会是 200 —— 登出的 token 复活了。** 这就是第 6.3 节那个决策的实际后果，值得亲眼看一次。

---

## 九、动手顺序

1. **改配置**（`JwtProperties` 拆两个有效期 + `application-local.yml` 用 `30m`/`7d`）
2. **改 `JwtUtils`**：拆两个签发方法 + 加 `type` claim（此时旧代码还能跑）
3. **`CacheKeys` 加两个 key 方法**
4. **新增 `TokenService`**：refreshToken 的写入/校验/吊销 + 黑名单
5. **改 `AuthController`**：登录时同时签发两个 token；加 `/logout`、`/refresh`
6. **改 `JwtInterceptor`**：加 type 校验 + 黑名单查询
7. Rebuild → 按第 8 节逐组验证（**8.1 的 ③④ 和 8.2 的 ④ 是重点**）
8. 写 `JWT双Token设计文档.md` 的实现补充（或直接在本文档补一节"实现记录"）

⚠️ **第 6 步改完必须回归现有接口** —— 拦截器是所有接口的入口，改错了会全线 401。

---

## 十、这套设计能撑起的面试问题

| 可能的问题 | 你的答案要点 |
| --- | --- |
| JWT 和 Session 的区别？ | 无状态、不存服务端、可跨服务；代价是**退不掉、续不了** |
| **JWT 怎么实现登出？** | 无状态本身做不到 → 引入有状态的 refreshToken 存 Redis；accessToken 可选加黑名单（TTL = 剩余有效期） |
| 为什么要双 Token？ | 短期凭证（泄露可控、无需存储）+ 长期凭证（必须可吊销）分开 |
| refreshToken 为什么要存服务端？ | 为了**能吊销** —— 登出、改密码、检测盗用时立即作废 |
| **refresh 轮转有什么用？** | 一次一换，重复使用即说明泄露 → 能**检测盗用**并吊销整个会话 |
| access token 过期了怎么办？ | 前端用 refreshToken 换新的并重试原请求；并发 401 要做**刷新去重** |
| 你的降级策略是什么？ | ⭐ 分开讲：**缓存失败 fail-open（保可用），黑名单失败 fail-closed（保安全）** —— 取决于依赖是性能层还是安全层 |

---

## 附：与你现有代码的衔接

- **`CacheKeys`** 已经管了 `article:detail` / `article:views` / `article:lock`，再加两个 token key 就齐了 —— 保持"前缀只出现在一处"
- **`JwtInterceptor`** 现在的结构很清晰（取 token → 验 token → 挂 attribute），新增的两步（type 校验、黑名单）正好插入第 3 步和第 4 步之间，不用推翻重写
- **`Result.unauthorized`** 已经有 401 了；如果想区分"token 过期"和"token 无效"，可以再加一个细分 code（如 4011 / 4012），前端据此决定"重新登录"还是"自动刷新后重试"
- **Redis 降级封装**的写法可以复用（`try/catch` + `log.warn`），但**策略要反过来**（fail-closed）
