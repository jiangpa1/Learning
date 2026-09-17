# HANDOFF — Learning 项目交接说明

> 最后更新：2026-09-17
> 项目路径：`C:\Users\ASUS\Desktop\Learning`
> 项目仓库：`jiangpa1/Learning`（分支 `main`）
> 笔记仓库：`jiangpa1/java-learning`（每日练习与知识库）
> 文档目录：`md/`

---

## 一、这是什么

一个 Java 后端学习项目，作者是计算机系大三学生，目标是 2026 年寒假（约 12 月—次年 1 月）找 Java 后端实习。

形态上是一个**简易博客后端**：用户、认证、文章、分类、评论，文章详情带 Redis 缓存。**共 19 个接口**。

需要说明的是，这**不是教学 demo**。接口分层、统一响应封装、JWT 鉴权、全局异常处理、分页、跨表查询、并发更新、缓存与缓存一致性这些都是按真实项目的做法来的，代码里刻意避开了不少新手写法。接手或复看时，下面第七节的「关键设计决策」是最需要先读的部分——那些看起来"绕"的写法是为了解决具体问题，不要顺手改回简单版本。

---

## 二、技术栈

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| Spring Boot | 2.7.18 | **2.x，不是 3.x**，下面很多坑都跟这个有关 |
| JDK | 17 | pom 里已设 `java.version=17` |
| MyBatis-Plus | 3.5.5 | |
| MySQL 驱动 | `com.mysql:mysql-connector-j`（8.0.33） | 坐标不能换回旧的那个 |
| MySQL | 8.x | 跑在虚拟机 `192.168.133.128:3306`，库名 `learning` |
| **Redis** | — | 同一台虚拟机 `192.168.133.128:6379`，**有密码**；`spring-boot-starter-data-redis` |
| jjwt | 0.11.5 | api / impl / jackson 三件套 |
| spring-security-crypto | 由 Spring Boot 管理 | **只引 crypto，没引完整 starter** |
| Lombok | 1.18.30 | provided |
| spring-boot-starter-validation | | 参数校验 |

端口：`8080`

> ⚠️ **这台虚拟机是多个项目共用的**（上面还有海南麻将的库）。所以 Redis 的 key 一律带 `learning:` 前缀，避免撞 key。

---

## 三、跑起来之前必须做的四件事

**1. 配 `JWT_SECRET` 环境变量**

值是 JWT 的签名密钥，**至少 32 个字符**（HS256 要求 256 位，短了会抛 `WeakKeyException`）。生成方式：

```powershell
(1..32 | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) }) -join ''
```

配在系统环境变量里。**改完必须把 IDEA 完全退出再打开**——Windows 上已经运行的进程读不到新加的环境变量，只重启项目没用。

**2. 重建 `application-local.yml`**

数据库和 Redis 的账号密码都在这个文件里，已被 `.gitignore` 忽略，换台机器克隆下来是没有的。格式：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://192.168.133.128:3306/learning?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: 你的密码
  redis:
    host: 192.168.133.128
    port: 6379
    password: 你的Redis密码
    database: 0
    timeout: 3000ms
jwt:
  secret: ${JWT_SECRET}
  expiration: 3600000
  issuer: learning
```

> `jwt.expiration` 写 `3600000` 会被 Spring Boot 的 Duration 转换器当成毫秒，也就是 1 小时。

**3. 建表**

四张表：`tb_user`、`tb_article`、`tb_category`、`tb_comment`。建表语句见 `md/文章模块接口文档.md` 第二节。

**注意实际表名带 `tb_` 前缀**——那份文档里写的是 `category` / `comment`（无前缀），是早期的设计稿，**以库里的 `tb_category` / `tb_comment` 为准**。

`tb_comment` 需要复合索引（见第七节第 13 条）：

```sql
ALTER TABLE tb_comment ADD INDEX idx_article_create (article_id, create_time);
```

**4. 确认虚拟机和两个服务都起来了**

```powershell
Test-Connection 192.168.133.128 -Count 1 -Quiet      # 应为 True
```

MySQL 和 Redis 都在这台虚拟机上。**它经常处于关机状态** —— 动手前先确认能连上，否则写完代码既建不了表也跑不起来测。

---

## 四、目录结构与分层约定

```
com.jiangpa
├── common        Result、PageResult、CacheKeys   —— 通用返回结构 + 缓存 key 常量
├── config        SecurityConfig、WebMvcConfig、MybatisPlusConfig
├── controller    接口层
├── dto           接收请求参数（带校验注解）
├── exception     GlobalExceptionHandler、BusinessException
├── interceptor   JwtInterceptor
├── mapper        XxxMapper extends BaseMapper<Xxx>
├── pojo          实体
├── properties    JwtProperties
├── service       接口
├── service.impl  实现
├── utils         JwtUtils
└── vo            返回给前端
```

**分层铁律**（改代码时别破坏）：

- **Controller** 只做三件事：接参数、调 Service、用 `Result.success(...)` 包装。不写业务逻辑。
- **Service** 失败时 `throw new BusinessException(code, "提示语")`，**不返回 Result**。方法签名返回业务类型（`UserVO`、`List<UserVO>`、`Long`、`void`）。
- **Service 不依赖 Result**（各 Service 接口里都不该出现 `Result` 的 import）。
- **Service 接口不声明受检异常**。Jackson 的 `JsonProcessingException` 属实现细节，必须就地转成 `BusinessException` 或降级处理，不能污染接口签名。
- **Mapper** 只管读写数据库，不判断业务规则。

---

## 五、已完成

### 认证（`/auth/**`）
- `POST /auth/register` 注册，密码用 BCrypt 加密后存
- `POST /auth/login` 登录，返回 JWT
- 登录失败时"用户不存在"和"密码错误"返回**完全相同**的提示和状态码，防止用户名枚举

### 用户模块（`/user/**`）
- 查询单个、查询列表、修改、删除
- 返回给前端的是 `UserVO`，**不含 password**

### 文章模块（`/article/**`）
- `GET /article/{id}` 详情，带作者昵称，**走 Redis 缓存**
- `GET /article/list` 分页列表，按创建时间倒序，**只返回摘要不返回正文**
- `POST /article` 发布，作者 id 从 JWT 取
- `PUT /article/{id}` 修改，非作者返回 403，**并清理缓存**
- `DELETE /article/{id}` 删除，非作者返回 403，**并清理内容与浏览量两个 key**

### 分类模块（`/category/**`）
- `GET /category/list` 分类列表，**不分页**（分类数量有限），按 id 升序
- `POST /category` 新增，**先查重**（重名返回 400「分类名已存在」）
- `PUT /category/{id}` 修改，查重时**排除自己**（`.ne(Category::getId, id)`）
- `DELETE /category/{id}` 删除，**分类下有文章则拒绝删除**（返回具体篇数）

### 评论模块（`/comment/**`）
- `POST /comment` 发表，**发表前校验文章存在**（无外键，数据库不会拦）
- `GET /comment/list` 分页查询，按 `create_time` 倒序，带作者昵称
- `DELETE /comment/{id}` 删除，非作者返回 403

### Redis 缓存（2026-09-16 接入并验证）
- `GET /article/{id}` 走 **Cache Aside**：读缓存 → 命中返回 → miss 查 DB → 回填
- key：`learning:article:detail:{id}`（内容，TTL 30 分钟 ± 5 分钟随机）、`learning:article:views:{id}`（浏览量计数，不设 TTL）
- 空值哨兵 `__NULL__` 防穿透（TTL 2 分钟）
- 浏览量改 Redis `INCR`，缓存 miss 时**惰性回写** DB

### 基础设施
- `JwtInterceptor` 全局鉴权，放行 `/auth/**` 和 `/error`
- `GlobalExceptionHandler` 统一处理参数校验、唯一键冲突、业务异常、兜底异常
- `MybatisPlusConfig` 分页插件
- `Result` 支持 200 / 400 / 401 / 403 / 404 / 500
- `CacheKeys` 集中管理缓存 key 前缀（`learning:`）与空值哨兵

---

## 六、进行中 / 已知缺陷

**`selectArticleById` 的缓存击穿防护有实现缺陷**（2026-09-17 审查发现），三处会真的出错：

1. **锁加在 DB 查询之后** → 击穿防护实际未生效，每个并发请求仍会查一次 DB
2. **`wait(1000)` 会抛 `IllegalMonitorStateException`** → `wait()` 必须在 `synchronized` 上下文里调用，该项目没有，走到这个分支必抛异常
3. **递归调用没 `return`** → `selectArticleById(id);` 的返回值被丢弃，该分支最终返回 `null`

详见第七节第 14 条。

---

## 七、关键设计决策

**这一节最重要。** 下面每一条都是刻意的，看起来"多此一举"的写法背后都有原因。

### 1. HTTP 状态码一律返回 200

业务状态放在响应体的 `code` 字段里。**这意味着鉴权失败、参数错误、服务器出错，HTTP 层看到的都是 200。**

拦截器（`setStatus(SC_OK)`）和异常处理器都遵循这个约定，保持一致。前端判断成功失败要看 body 里的 `code`。

这个选择本身没有对错，但**必须贯穿到底**——如果哪天有人给某个接口单独设了真实的 HTTP 状态码，前端的两套判断逻辑就会打架。

### 2. 数据库不建物理外键

关联关系靠索引 + 应用层保证。这是互联网项目的普遍做法（《阿里巴巴 Java 开发手册》明确要求），外键会在写入时加锁、影响并发，分库分表后也无法维护。

**代价必须自己扛**：数据库不挡了，应用层就得校验。所以发表评论前要校验文章存在、删除分类前要统计引用数。

### 3. 列表查询用 `wrapper.select(...)` 指定列

```java
wrapper.select(Article::getId, Article::getTitle, Article::getSummary,
               Article::getUserId, Article::getViewCount, Article::getCreateTime)
```

**不要改成 `SELECT *`**。文章正文是 `TEXT`，列表一页查 10 条会把几十 KB 的正文全拉出来，这些数据用户根本没看。列表只给 `summary`，正文只在详情接口返回——这是"列表页轻量、详情页完整"的落地。

### 4. 跨表查作者昵称用批量查询，不要在循环里查

```java
List<Long> userIds = records.stream().map(Article::getUserId).distinct().toList();
Map<Long, String> nicknameMap = userMapper.selectBatchIds(userIds).stream()...
```

整个列表接口只查两次数据库，跟页大小无关。**如果改成在 `map` 里逐条 `selectById`，一页 10 条就是 11 次查询**——这就是 N+1 问题，是线上接口变慢最常见的原因。

另外注意这里有个**空集合判空**（`userIds.isEmpty() ? new HashMap<>() : ...`）。空表时如果不判空，拼出来的 SQL 是 `WHERE id IN ()`，MySQL 里是语法错误。

评论列表复用了同一套写法。

### 5. 浏览量用 SQL 层自增（已被 Redis 计数取代）

原来写法：

```java
wrapper.eq(Article::getId, id).setSql("view_count = view_count + 1");
```

**不能写成"先查出来加一再写回"**。后者是读-改-写三步，两个请求同时读到 43、各自加一都写回 44，实际该是 45——一次浏览凭空消失。这是更新丢失问题。

**2026-09-16 起改为 Redis `INCR`**（见第 12 条），原因见下条。

### 6. 更新用 `LambdaUpdateWrapper` 显式指定列

```java
wrapper.eq(Article::getId, id)
       .set(Article::getTitle, ...)
       .set(Article::getContent, ...)
       .set(Article::getUpdateTime, LocalDateTime.now());
```

**不要改回 `updateById`。** 实体里 `viewCount` 是基本类型（或者即使是包装类），新建一个对象只 set 要改的字段再 `updateById`，很容易把 `view_count` 重置成 0、或者意外覆盖 `create_time`。显式列出要更新的列，没列到的列根本不会出现在 SQL 里，最安全。

### 7. 作者 id 只从 JWT 取，绝不从请求体读

```java
public Result<?> addArticle(@Valid @RequestBody ArticleDTO dto,
                            @RequestAttribute("userId") Long userId) {
```

拦截器把 `userId` 塞进了 request attribute，Controller 用 `@RequestAttribute` 取。`ArticleDTO` 里**没有** `userId` 字段——如果允许前端传，任何人都能改个数字以别人名义发文。这是最典型的越权漏洞。评论模块同理。

### 8. 权限校验顺序：先判断存在，再判断归属

```java
Article article = articleMapper.selectById(id);
if (article == null) throw new BusinessException(404, "文章不存在");
if (!Objects.equals(article.getUserId(), userId)) throw new BusinessException(403, "无权操作他人文章");
```

**顺序不能反**。反了的话，改一篇不存在的文章会返回 403，让人以为是没有权限，排查时容易绕远路。同时反了还会 NPE——`article` 为 null 时 `getUserId()` 直接空指针。

### 9. 分页插件必须配置

`MybatisPlusConfig` 里的 `MybatisPlusInterceptor` + `PaginationInnerInterceptor`。

**没有它不是报错，而是静默失效**——LIMIT 不会拼进 SQL，查出全表再在内存里截取。数据少的时候完全看不出来，等表里几万条才会发现接口突然变慢。

### 10. 实体主键要标 `@TableId(type = IdType.AUTO)`

不标的话 MyBatis-Plus 会用默认的雪花算法在 Java 端生成 19 位 id，跟数据库的 `AUTO_INCREMENT` 对不上。功能和自增主键完全不同，回填回来的也是雪花值。

### 11. 缓存用 Cache Aside，更新时**删**缓存而不是更新缓存

读：查缓存 → 命中返回 → miss 查 DB → 回填。
写：**先更新 DB，再删缓存**。

**为什么删而不是更新**（三条）：

1. **更新缓存在并发下会写脏且无法自愈。** 两个请求并发改同一条数据，DB 依次变成 v1、v2，但"写缓存"的到达顺序可能反过来——缓存里留下 v1，与 DB 长期不一致，直到 TTL 过期才纠正。"删除"是幂等的，谁先谁后结果一样。
2. **避免无效更新。** 写时更新缓存意味着每次都产生一次缓存写，但这份数据可能压根没人读。
3. **降低更新成本。** 缓存值往往是多表聚合的结果（文章详情要拼作者昵称），"更新"就得重新算一遍全部聚合。

**已接受的代价**：`ArticleDetailVO` 里含 `authorNickname`，用户改昵称时**不知道该失效哪些缓存**（key 是文章 id，反查不出该用户有哪些文章被缓存了）。**选择接受 TTL 内的不一致** —— 昵称变更极低频，为它引入反向索引或双缓存不值得。

### 12. 浏览量迁到 Redis，缓存 miss 时惰性回写

**为什么不能把 `viewCount` 一起缓存**：缓存的目标是命中时不查 DB，而浏览量要求每次访问都写 DB——**两个诉求直接冲突**。若把 `viewCount` 缓存进去，命中时浏览量就不再累加，只有 TTL 过期那一次 +1，会严重少计。

**所以缓存里的 `viewCount` 一律存 `null`**，读出来后统一用 Redis 计数器填：

```
每次访问：读 detail 缓存 → 命中返回内容 → INCR views:{id} → 填进返回对象
缓存 miss：查 DB → setIfAbsent(views:{id}, DB 的 view_count) → INCR → 回写 DB → 回填缓存
```

**两个关键点，不要改**：

- **播种必须用 `setIfAbsent` 而不是 `set`。** miss 时从 DB 读到的是**旧值**，而 Redis 里可能已累积到很大（如 10000）。用 `set` 会把 Redis 覆盖成旧值，**累积的浏览当场全丢**。`setIfAbsent` 只在 key 不存在时写入，天然幂等。
- **`views` key 不能设 TTL（或必须远长于 detail 的 TTL）。** 因为命中分支是直接 `increment` 而没有播种逻辑——key 一旦过期，`increment` 会从 0 创建并返回 1，**把真实计数抹掉**。

**回写时机与代价**：只在缓存 miss（约 30 分钟一次）时回写，所以 1 万次访问可能只写 2 次库。代价是：① 回写节奏跟着读流量走，冷文会长期滞后（但会自愈）；② **Redis 崩溃会丢一个周期的增量**。浏览量这种非关键计数可以接受，**换成订单金额就绝不能这么做**。

### 13. 评论表要加复合索引，并删掉被覆盖的单列索引

```sql
ALTER TABLE tb_comment ADD INDEX idx_article_create (article_id, create_time);
DROP INDEX idx_article_id ON tb_comment;   -- 被上一条的最左前缀完全覆盖，冗余
```

查询是 `WHERE article_id = ? ORDER BY create_time DESC`。单列索引只能过滤，之后 MySQL 还要 filesort 排序；复合索引（等值列在前 + 排序列在后）让过滤和排序一次扫描完成。

`EXPLAIN` 验证（用 `IGNORE INDEX` 可在同一张表上对比）：

| 写法 | `key` | `Extra` |
| --- | --- | --- |
| 有复合索引 | `idx_article_create` | **`Backward index scan`**（无 filesort） |
| `IGNORE INDEX (idx_article_create)` | `idx_article_id` | **`Using filesort`** |

**加完复合索引要把被覆盖的单列索引删掉**——索引不是越多越好，每个索引都要在写入时同步维护。

### 14. 缓存击穿要用互斥锁，且锁必须包住 DB 查询

缓存失效瞬间，N 个并发请求会同时查 DB。用 `setIfAbsent` 做互斥锁，只让一个请求去重建：

```java
String lockKey = "learning:lock:article:detail:" + id;
Boolean locked = stringRedisTemplate.opsForValue()
        .setIfAbsent(lockKey, "1", 10, TimeUnit.SECONDS);
if (Boolean.TRUE.equals(locked)) {
    try {
        // ★ 抢到锁之后才查 DB —— 锁必须包住 DB 查询，否则等于没加
        Article article = articleMapper.selectById(id);
        ...组装 VO、自增浏览量、回填缓存、回写 DB
        return vo;
    } finally {
        stringRedisTemplate.delete(lockKey);   // ★ 必须 finally，否则异常就死锁
    }
} else {
    // 没抢到：短暂等待后重试读缓存
}
```

**四个坑，一个都不能漏**：

| 坑 | 后果 |
| --- | --- |
| **锁必须包住 DB 查询** | 锁加在 DB 查询之后 → 每个并发请求仍查一次 DB，**击穿防护完全失效** |
| 锁必须设过期时间 | 进程崩了 → 死锁，这个 key 永远重建不了 |
| 解锁必须放 `finally` | 抛异常就死锁 |
| 重试要限制次数 | 递归重试不加限制 → `StackOverflowError` |

**另外两个 Java 基础点**：

- **等待用 `Thread.sleep(毫秒)`，不是 `wait(毫秒)`。** `wait()` 是 `Object` 的方法，**必须在 `synchronized` 上下文里调用**，否则抛 `IllegalMonitorStateException`。`sleep()` 是 `Thread` 的静态方法，不需要锁。
- **递归重试必须 `return` 递归调用的结果。** `selectArticleById(id);` 这样写返回值会被丢弃，方法最终返回 `null`，`Result.success(null)` 直接发给前端。

> 想清楚互斥锁的**代价**：抢不到锁的请求要等待，增加了响应时间。所以工业界还有「逻辑过期」方案——不设 Redis TTL，把过期时间放进 value，发现逻辑过期就返回旧值 + 异步重建。两种方案要能对比着讲。

---

## 八、待办清单

按建议的优先级排：

| 优先级 | 事项 | 说明 |
| --- | --- | --- |
| **高** | **修缓存击穿的三个缺陷** | 锁位置、`wait()` 误用、递归丢返回值 —— 见第七节第 14 条 |
| 中 | 用户模块列表加分页 | 现在是全表查，参照文章模块的 `PageResult` 写 |
| 中 | `HttpMessageNotReadableException` 单独处理 | JSON 格式写错、Content-Type 不对时，现在会掉到兜底返回 500，其实应该返 400 |
| 中 | 逻辑删除 | 现在文章、用户都是物理删除，删了不可恢复。加 `deleted` 字段 + MP 的 `@TableLogic` |
| 中 | 文章关联分类的校验 | `POST/PUT /article` 的 `categoryId` 可以存但不校验分类是否存在，会产生悬空引用 |
| 低 | 列表页浏览量不一致 | `selectArticlesList` 从 DB 读 `view_count`，而 DB 每 30 分钟才回写 → 列表与详情会不一致。要么接受，要么列表也从 Redis 取 |
| 低 | 改密码接口 | `UserUpdateDTO` 只能改昵称，改密码要单独开接口 |
| 低 | 分页参数抽公共组件 | 每个列表接口都在重复写 `pageNum`/`pageSize` + 上限截断 |
| 低 | 提示语统一 | 见第九节 |

---

## 九、已知的小瑕疵

**提示语不统一。** 同一个意思有几种写法：

- 文章不存在：详情接口抛 `"文章不存在！"`（全角感叹号），修改和删除抛 `"文章不存在"`（无标点）
- 用户名已存在：Service 里查重抛 `"用户名已存在!"`（半角），`GlobalExceptionHandler` 里唯一键冲突兜底返回 `"数据已存在！"`（笼统）
- **作者昵称兜底文案有三处不同**：`ArticleServiceImpl` 的 `toMap` 里是 `"默认昵称"`、`getOrDefault` 里是 `"未知作者"`；`CommentServiceImpl` 里是 `"未知"`。建议统一成两个语义清晰的常量：**用户存在但昵称为空** → 一个文案；**用户查不到（脏数据）** → 另一个文案。

**`PageResult` 有个没用的五参数构造器。** 全程用的是 setter，这个构造器是死代码，而且三个连续的 `Long` 参数很容易传错顺序还不会编译报错，建议删掉。

**JwtProperties 用 `@Component` + `@ConfigurationProperties` 绑定。** 能用，但更现代的写法是 `@EnableConfigurationProperties` 或 `@ConfigurationPropertiesScan`。

**`selectArticleById` 里保留了大段注释掉的旧实现。** 项目已有 git，`git show` 就能看历史，注释掉的大段代码会让 review 的人分不清哪段是活的，建议删掉。

**魔法数字散落。** 空值哨兵的 TTL（2 分钟）、锁的过期时间（10 秒）、detail 的 TTL 基数（30 分钟）都直接写在方法里，建议提到 `CacheKeys` 或常量类里。

---

## 十、环境相关的坑

这些都是实际踩过的，复发概率高：

| 现象 | 原因 |
| --- | --- |
| 启动报 `Could not resolve placeholder 'JWT_SECRET'` | 环境变量没配，或配了但 IDEA 没完全重启 |
| 启动报 `WeakKeyException` | `JWT_SECRET` 短于 32 字符 |
| 连不上 MySQL / Redis | 虚拟机 `192.168.133.128` 没开机。**它经常是关着的** |
| Redis 报 `NOAUTH Authentication required` | 没配 `spring.redis.password` |
| Redis 配置不生效、一直连 localhost | 前缀写错。**2.x 是 `spring.redis.*`，3.x 才改成 `spring.data.redis.*`**，抄了 3.x 的教程不会报错，只会静默用默认值 |
| 缓存里的值是乱码 | 用了 `RedisTemplate` 而非 `StringRedisTemplate`，默认走 JDK 二进制序列化 |
| 序列化报 `InvalidDefinitionException`（`LocalDateTime`） | 自己 `new ObjectMapper()` 了。必须注入 Spring 容器里的那个（已注册 `JavaTimeModule`） |
| Redis 报 `ERR value is not an integer or out of range` | 对一个非数字的值执行了 `INCR`。常见于 `String.valueOf(null)` 得到字符串 `"null"` 被播种进计数 key |
| `Could not find or load main class main.java.org.example.Xxx` | IDEA 的运行配置还指向旧包名，去 Run → Edit Configurations 改回 `com.jiangpa.Xxx` |
| 报 `Table 'learning.tb_user' doesn't exist` | 表名是 `tb_user`，不是 `user` |
| 登录一直提示密码错误但密码是对的 | 表里那条记录是早期用 MD5 存的，BCrypt 的 `matches` 对 MD5 串只会返回 false，清掉重新注册 |
| Maven 报 `'dependencies.dependency.version' ... is missing` | MySQL 驱动坐标用了旧的 `mysql:mysql-connector-java`，Spring Boot 2.7.8 起改成了 `com.mysql:mysql-connector-j` |
| 一堆 `javax.servlet` 找不到符号 | 抄了面向 Spring Boot 3 的教程。**2.7 用 `javax`，不是 `jakarta`** |
| 拦截器报 `SignatureException` 捕获不到 | jjwt 有两个同名类，要用 `io.jsonwebtoken.security.SignatureException`，`io.jsonwebtoken` 包下那个已废弃 |

---

## 十一、怎么验证改动

每个模块都有一份 Postman 测试文档（在 `md/` 下），按用例走一遍就行。

**测试时的核心前提：HTTP 状态码全是 200，看响应体里的 `code`。**

写新模块的测试时，有几类边界特别容易漏（真实踩过）：

- **空表 / 空结果**——`IN ()` 的语法错误只在表是空的时候出现，开发时表里总有数据，很容易一路测过去都没碰过
- **短输入**——摘要截取、字符串截取的越界，只在输入短的时候暴露
- **并发更新的字段**——更新后要回头确认 `viewCount` 没被重置、`createTime` 没被覆盖
- **权限分支**——**必须用第二个账号**。用同一个账号永远改自己的东西、永远成功，403 那条分支根本触发不到
- **过滤条件是否真的用上了**——评论列表按 `articleId` 过滤，如果只测"有评论的文章"是测不出来的（过滤丢了也返回数据）。**必须查一篇没有评论的文章**，期望 `total = 0` 而不是全表数量

### 缓存怎么验证

本机没装 `redis-cli`，可以在虚拟机上执行，或写临时单测用 `StringRedisTemplate` 打印。

```
① 删掉 detail key 强制 miss
   redis-cli -h 127.0.0.1 -a <密码> DEL learning:article:detail:1

② GET /article/1 → 控制台【应该有】 SELECT
③ 立刻再 GET /article/1 → 控制台【不应该有】 SELECT          ← 命中验证
④ 对比：Redis 的 views 应 +1，而 DB 的 view_count 【不变】    ← 只动缓存不动库
```

**第 ③④ 步是关键判据** —— 两项同时成立，才说明真的走了缓存且没回源。

---

## 十二、文档索引

**全部在 `md/` 目录下**（2026-09-16 从根目录整理进来）：

| 文件 | 内容 |
| --- | --- |
| `用户模块接口文档.md` | 用户增删改查的接口定义、`Result` 与状态码约定 |
| `JWT鉴权拦截器文档.md` | 拦截器职责、放行规则、`preHandle` 各步骤、踩坑清单 |
| `全局异常处理器文档.md` | 五类异常的覆盖范围、`BusinessException` 的设计意图 |
| `Postman接口测试文档.md` | 用户与认证模块的测试用例 |
| `文章模块接口文档.md` | 四张表的建表 SQL 与索引设计、文章模块五个接口、关键实现点 |
| `文章模块Postman测试文档.md` | 文章模块的测试用例，含双账号权限测试 |
| `分类与评论模块接口文档.md` | 分类与评论七个接口的定义、状态码、关键实现点 |
| `分类与评论模块Postman测试文档.md` | 分类与评论的 24 条测试用例，含并发重名、空值缓存、N+1 验证 |
| `Redis缓存设计文档.md` | 缓存 key 设计、浏览量方案取舍、序列化要点、六组验证方法 |

本文件 `LearningHANDOFF.md` 留在**仓库根目录**。

> **已知的文档不一致**：`文章模块接口文档.md` 里的表名写的是 `category` / `comment`（无 `tb_` 前缀），与实际库中的 `tb_category` / `tb_comment` 不符，那份文档是早期设计稿，未回改。
>
> 另外「用户模块的修改接口是 `PUT /user`（id 在 body 里）」，跟文章模块的 `PUT /article/{id}`（id 在路径里）风格不一致，建议统一成后者。
