# Redis 缓存设计文档

> 项目：Learning（Spring Boot 2.7.18 + Redis）
> 目标：给 `GET /article/{id}` 接入 Cache Aside 缓存
> 日期：2026-09-16

---

## 一、Redis 里存什么（数据格式）

### 1.1 key 命名

| key 模式 | 类型 | 存什么 | TTL |
| --- | --- | --- | --- |
| `learning:article:detail:{id}` | String | `ArticleDetailVO` 的 JSON 字符串，或空值哨兵 `__NULL__` | 正常 30 分钟 ± 5 分钟随机；空值 2 分钟 |
| `learning:article:views:{id}` | String | 该文章的浏览量（整数，用 `INCR` 累加） | 不设过期（或设很长） |

**为什么必须加 `learning:` 前缀。** 你那台 `192.168.133.128` 是**多个项目共用的虚拟机**（上面还有海南麻将的库）。Redis 默认 16 个 database、大家通常都用 `0`，所以别的项目写 `article:detail:1` 时会和你的撞 key。加项目前缀是零成本的习惯，在团队里更是必须。

### 1.2 value 的真实形态

`ArticleDetailVO` 的全部字段（`id` / `title` / `content` / `userId` / `authorNickname` / `categoryId` / `viewCount` / `createTime` / `updateTime`），序列化后是这样：

```
key   learning:article:detail:1
value {"id":1,"title":"MySQL 索引为什么用 B+","content":"正文内容……","userId":17,
       "authorNickname":"张三丰","categoryId":null,"viewCount":null,
       "createTime":"2026-09-16T10:30:00","updateTime":"2026-09-16T10:30:00"}
```

**注意 `viewCount` 是 `null`** —— 浏览量**不进缓存内容**，原因见第二节。

`LocalDateTime` 被序列化成 ISO-8601 字符串（`"2026-09-16T10:30:00"`）。**前提是使用 Spring 容器里的 `ObjectMapper`**，它默认注册了 `JavaTimeModule` 并关闭了时间戳格式。自己 `new ObjectMapper()` 会报 `InvalidDefinitionException`。

浏览量计数器：

```
key   learning:article:views:1
value 43          ← 纯数字字符串，用 INCR 累加
```

### 1.3 为什么用 String + JSON

| 方案 | `redis-cli` 里能直接看懂吗 | 说明 |
| --- | --- | --- |
| **String + JSON**（本方案） | ✅ 能 | 用 `StringRedisTemplate`，存的就是 JSON 文本，调试友好 |
| 默认 `RedisTemplate` | ❌ 乱码 | 默认 `JdkSerializationRedisSerializer` 存的是 Java 二进制序列化结果，且**强依赖类结构**，改字段名就可能反序列化失败 |
| Hash | ✅ 能 | 可做字段级更新，但取整个对象要 `HGETALL` 再手工拼，反而麻烦 |

**结论**：用 `StringRedisTemplate` + Jackson 手动序列化。这样**不需要**写 `RedisConfig` 去自定义序列化器 —— 少一个配置类，也少一类踩坑。

---

## 二、浏览量：今天最需要想清楚的一件事

### 2.1 为什么会冲突

看现有实现：

```java
articleMapper.update(null, viewWrapper.setSql("view_count = view_count + 1"));  // 每次访问都写 DB
articleDetailVO.setViewCount(article.getViewCount() + 1);
```

- **缓存的目的**：命中时**完全不查 DB**
- **浏览量的目的**：每次访问都要**写 DB**

这两个诉求是**直接冲突**的。如果把 `viewCount` 一起缓存进去，就会变成这样：

> 缓存命中 → 直接返回 → **浏览量不再累加**。只有每 30 分钟缓存过期那一次才 +1，浏览量会严重少计。

**所以浏览量必须从缓存里拆出来单独处理。** 这就是上面为什么把 `viewCount` 存成 `null`。

### 2.2 方案 A（推荐）：浏览量迁到 Redis，惰性回写

**Redis 持有权威值，DB 只做持久化副本。**

```
访问详情：
  1. 读 detail 缓存 → 命中就返回内容（0 次 DB 查询）
  2. INCR views:{id} → 得到当前浏览量，填进返回对象

缓存 miss 时（也就是每 30 分钟一次）：
  3. 查 DB 拿文章内容和作者
  4. setIfAbsent(views:{id}, DB 里的 view_count)   ← 只在这里给计数器"播种"
  5. INCR views:{id}
  6. 把当前浏览量回写 DB（UPDATE view_count = ?）
  7. 回填 detail 缓存
```

**关键点是第 4 步的 `setIfAbsent`**：

- 它只在 key **不存在**时写入 → 天然幂等，不会把已有计数覆盖掉
- 放在 miss 分支里，是因为**只有 miss 时才拿得到 DB 的当前值**
- 首次访问、Redis 重启后、TTL 过期后，都能自动重新"播种"

**为什么用"惰性回写"而不是定时任务**：省掉 `@Scheduled` + `@EnableScheduling`，DB 的 `view_count` 每次 miss（约 30 分钟）同步一次。对这个项目**完全够用**，而且实现简单、没有额外的调度器要维护。

**这个方案的代价（要能说出来）**：如果 Redis 挂了且没开 AOF 持久化，**最近一个周期内的浏览量会丢**。对浏览量这种非关键计数完全可以接受；换成订单金额就绝不能这么做。

### 2.3 方案 B（保守）：浏览量继续走 DB

- 缓存只管文章内容 + 作者昵称；`viewCount` 仍用一条 `UPDATE` 自增
- 问题：**拿不到自增后的值**（MySQL 的 UPDATE 不返回值），所以还得补一次查询

```java
articleMapper.update(null, wrapper.setSql("view_count = view_count + 1"));  // 写
Long vc = articleMapper.selectViewCountById(id);                            // 还得读
```

**结论**：缓存命中时仍然要 1 读 1 写打 DB，**缓存的收益被削掉一大半**。只在"暂时不想动浏览量逻辑"时才选它。

> **选 A。** 你 9.2 学过 Redis 计数器，9.5 做过场景实战，这里是真正用上。

---

## 三、需要改动的文件

| 文件 | 改动 | 现状 |
| --- | --- | --- |
| `pom.xml` | 加 `spring-boot-starter-data-redis` | ✅ 已完成 |
| `application-local.yml` | 加 `spring.redis.*` 连接配置 | ✅ 已完成（含 `password`） |
| `common/CacheKeys.java` | **新增**：key 常量与前缀拼接 | ⬜ 待做（可选但推荐） |
| `service/impl/ArticleServiceImpl.java` | 改 `selectArticleById` / `updateArticle` / `deleteArticle` | ⬜ 进行中 |
| ~~`config/RedisConfig.java`~~ | **不需要** | 用 `StringRedisTemplate` 就不必自定义序列化器 |

### 3.1 新增 `common/CacheKeys.java`（推荐）

key 散落在各处拼字符串，容易拼错、也容易改一处漏一处。集中管理：

```java
public final class CacheKeys {
    private CacheKeys() {}                       // 工具类不给实例化

    private static final String PREFIX = "learning:";
    public static final String NULL_SENTINEL = "__NULL__";

    public static String articleDetail(Long id) { return PREFIX + "article:detail:" + id; }
    public static String articleViews(Long id)  { return PREFIX + "article:views:"  + id; }
}
```

> 顺带养成习惯：**常量类用 `private` 构造器**防止被 `new`，是 Java 的通用约定。

### 3.2 `ArticleServiceImpl` 要注入什么

已有 `StringRedisTemplate`，**还要再加一个 `ObjectMapper`**：

```java
private final StringRedisTemplate stringRedisTemplate;
private final ObjectMapper objectMapper;

public ArticleServiceImpl(ArticleMapper articleMapper, UserMapper userMapper,
                          StringRedisTemplate stringRedisTemplate,
                          ObjectMapper objectMapper) { ... }
```

**必须注入 Spring 容器里的 `ObjectMapper`**，不要 `new ObjectMapper()`。容器里的那个已经配好 `JavaTimeModule`（能处理 `LocalDateTime`）并关闭了时间戳输出。

---

## 四、三个方法分别改成什么样（骨架）

> 下面是**结构与关键行**，具体补全（异常处理、常量提取）你自己写。

### 4.1 `selectArticleById` —— 读路径

```java
public ArticleDetailVO selectArticleById(Long id) {
    String detailKey = CacheKeys.articleDetail(id);
    String viewsKey  = CacheKeys.articleViews(id);

    // ① 先读缓存
    String cached = stringRedisTemplate.opsForValue().get(detailKey);
    if (cached != null) {
        // 空值哨兵：说明这个 id 确认不存在，直接拒绝，不打 DB
        if (CacheKeys.NULL_SENTINEL.equals(cached)) {
            throw new BusinessException(404, "文章不存在！");
        }
        ArticleDetailVO vo = objectMapper.readValue(cached, ArticleDetailVO.class);  // 处理 JsonProcessingException
        Long views = stringRedisTemplate.opsForValue().increment(viewsKey);          // 浏览量仍要 +1
        vo.setViewCount(views);
        return vo;
    }

    // ② miss：查 DB
    Article article = articleMapper.selectById(id);
    if (article == null) {
        // 缓存空值防穿透，TTL 要短
        stringRedisTemplate.opsForValue().set(detailKey, CacheKeys.NULL_SENTINEL, 2, TimeUnit.MINUTES);
        throw new BusinessException(404, "文章不存在！");
    }
    User author = userMapper.selectById(article.getUserId());
    ArticleDetailVO vo = new ArticleDetailVO();
    BeanUtils.copyProperties(article, vo);
    vo.setAuthorNickname(author == null ? null : author.getNickname());

    // ③ 浏览量：先播种（只在 key 不存在时生效），再自增
    stringRedisTemplate.opsForValue()
            .setIfAbsent(viewsKey, String.valueOf(article.getViewCount()));
    Long views = stringRedisTemplate.opsForValue().increment(viewsKey);
    vo.setViewCount(views);

    // ④ 回填缓存 —— 注意 viewCount 置 null，不进缓存内容
    vo.setViewCount(null);
    long ttl = 30 * 60 + ThreadLocalRandom.current().nextInt(300);   // 30分钟 ±5分钟，防雪崩
    stringRedisTemplate.opsForValue()
            .set(detailKey, objectMapper.writeValueAsString(vo), ttl, TimeUnit.SECONDS);

    // ⑤ 惰性回写 DB 浏览量
    articleMapper.update(null, new LambdaUpdateWrapper<Article>()
            .eq(Article::getId, id)
            .set(Article::getViewCount, views));

    vo.setViewCount(views);
    return vo;
}
```

**三个要点：**

1. **缓存里的 `viewCount` 是 `null`**，读出来之后统一用 Redis 计数器填 —— 这样缓存内容和计数器职责分明，不会互相打架。
2. **`setIfAbsent` 要在 `increment` 之前**，否则第一次访问会从 1 开始而不是从 DB 的值开始。
3. **`writeValueAsString` / `readValue` 会抛受检异常 `JsonProcessingException`**，必须处理（`try-catch` 包成 `BusinessException`，或者写个私有工具方法统一处理）。

### 4.2 `updateArticle` —— 写路径（删缓存）

文章内容改了，缓存必须失效。**加在更新 DB 之后**：

```java
articleMapper.update(null, wrapper);
stringRedisTemplate.delete(CacheKeys.articleDetail(id));   // ← 只加这一行
```

**为什么只删缓存、不删浏览量 key**：浏览量是独立计数，文章改标题不影响它，不该清掉。

### 4.3 `deleteArticle` —— 写路径（删缓存）

```java
articleMapper.deleteById(id);
stringRedisTemplate.delete(CacheKeys.articleDetail(id));   // 内容缓存要删
stringRedisTemplate.delete(CacheKeys.articleViews(id));    // 文章没了，计数也没意义，一起删
```

> 也可以不删浏览量 key 让它自然消亡，但既然文章都删了，一起清掉更干净。

---

## 五、当前代码的错误清单

`selectArticleById` 现在**编译不过**，而且有 5 个问题，逐个说清：

### ❌ 1. 编译错误：`articleDetailVO` 找不到符号

```java
/*Article article = articleMapper.selectById(id);
 ArticleDetailVO articleDetailVO = new ArticleDetailVO();   ← 定义被注释掉了
 ...*/
return articleDetailVO;                                    ← 但这里还在用
```

原来的实现整段被 `/* */` 注释，但 `return articleDetailVO;` 留在外面。**要么把变量声明留下，要么按新逻辑重写整个方法。**

### ❌ 2. `setIfAbsent` 的参数用反了

```java
stringRedisTemplate.opsForValue().setIfAbsent("article:", String.valueOf(id));
//                                              ↑ key        ↑ value
```

`setIfAbsent(key, value)` 的语义是 **`SETNX key value`** —— 第一个参数是 key，第二个是 value。你传的是：
- key = `"article:"`（所有文章共用一个 key！）
- value = 文章 id

**这会导致第一篇文章写入后，后面所有文章都"已存在"**，`setIfAbsent` 全部返回 `false`。

### ❌ 3. `get(id)` 和 `setIfAbsent("article:")` 用的不是同一个 key

```java
setIfAbsent("article:", String.valueOf(id))   // 写的是 key="article:"
get(id)                                        // 读的是 key=1（Long 会被转成 "1"）
```

**写的和读的完全不是一个 key**，所以 `get` 永远返回 `null`。而且**没有项目前缀**（应为 `learning:article:detail:{id}`）。

### ❌ 4. `SETNX` 不适合用来判断"是否命中缓存"

`setIfAbsent` 是**写操作**，不是读操作。它只能告诉你"这个 key 之前不存在"。

用它的**真正场景是分布式锁或缓存击穿的互斥重建**（只有一个请求抢到，去查 DB 重建缓存）。判断缓存是否命中的正确做法是 **`GET` 然后判 `null`**：

```java
String cached = stringRedisTemplate.opsForValue().get(detailKey);
if (cached == null) { /* miss */ }
```

### ❌ 5. 死变量 + 浏览量逻辑整体丢失

```java
String s = stringRedisTemplate.opsForValue().get(id);   // 声明了从没用过
```

`String s` 赋值后从未使用。而且原来的 `view_count = view_count + 1` 自增**被一起注释掉了** —— 现在浏览量完全不累加，这是功能回归，不是重构。

---

## 六、验证方法

改完之后按这个顺序验：

### 6.1 缓存生效

1. 启动应用，**先清掉 Redis 里的测试 key**（用 Java 客户端或 `redis-cli`）
2. `GET /article/1` → 看 IDEA 控制台，**应该有** `SELECT` 语句
3. **再** `GET /article/1` → 控制台**不应该**再出现 `SELECT`（说明走了缓存）
4. 看返回的 `viewCount`，**两次应该是递增的**（42 → 43），说明 Redis 计数器在工作

### 6.2 浏览量正确

```sql
SELECT id, view_count FROM tb_article WHERE id = 1;
```

- 第一次访问后，Redis 里有 `learning:article:views:1`
- 连续访问 5 次，第 6 次时 Redis 的值应该比 DB 大 5 左右（DB 只在 miss 时回写）

### 6.3 更新后缓存失效

1. `GET /article/1`（缓存已建立）
2. `PUT /article/1` 改标题
3. `GET /article/1` → **应该拿到新标题**，且控制台出现 `SELECT`（缓存被删，重新回源）

### 6.4 缓存穿透

```
GET /article/999999      → 404，控制台有 SELECT
GET /article/999999      → 404，控制台【没有】SELECT   ← 命中空值哨兵
```

### 6.5 检查 Redis 里到底存了什么

本机没装 `redis-cli`，两个办法：
- 在虚拟机上执行 `redis-cli -h 127.0.0.1 -a <密码> keys "learning:*"` 和 `get learning:article:detail:1`
- 或写个临时单测，用 `StringRedisTemplate` 打印出来

**确认 value 是可读的 JSON** —— 如果是乱码，说明序列化方式不对（用了 `RedisTemplate` 而非 `StringRedisTemplate`）。

---

## 七、今日验收清单

- [ ] `CacheKeys` 常量类已建（含 `learning:` 前缀、空值哨兵）
- [ ] `ObjectMapper` 已注入（用的是容器里的，不是 `new` 的）
- [ ] `selectArticleById`：读缓存 → 命中返回 → miss 回源 → 回填（`viewCount` 置 null）
- [ ] 浏览量走 Redis `INCR`，`setIfAbsent` 播种在 `increment` 之前
- [ ] TTL 加了随机偏移
- [ ] 空值哨兵生效（不存在的 id 第二次不打 DB）
- [ ] `updateArticle` / `deleteArticle` 里删了缓存
- [ ] 6.1 ~ 6.4 四组验证全部通过
- [ ] 能说清：**为什么 `viewCount` 不进缓存**、**为什么用 `setIfAbsent` 播种而不是 `set`**
