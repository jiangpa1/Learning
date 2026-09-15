# HANDOFF — Learning 项目交接说明

> 最后更新：2026-09-15
> 项目路径：`C:\Users\ASUS\Desktop\Learning`
> 笔记仓库：`jiangpa1/java-learning`

---

## 一、这是什么

一个 Java 后端学习项目，作者是计算机系大三学生，目标是 2026 年寒假（约 12 月—次年 1 月）找 Java 后端实习。

形态上是一个**简易博客后端**：用户、认证、文章，后续要加分类和评论。

需要说明的是，这**不是教学 demo**。接口分层、统一响应封装、JWT 鉴权、全局异常处理、分页、跨表查询、并发更新这些都是按真实项目的做法来的，代码里刻意避开了不少新手写法。接手或复看时，下面第七节的「关键设计决策」是最需要先读的部分——那些看起来"绕"的写法是为了解决具体问题，不要顺手改回简单版本。

---

## 二、技术栈

| 组件 | 版本 | 备注 |
| --- | --- | --- |
| Spring Boot | 2.7.18 | **2.x，不是 3.x**，下面很多坑都跟这个有关 |
| JDK | 17 | pom 里已设 `java.version=17` |
| MyBatis-Plus | 3.5.5 | |
| MySQL 驱动 | `com.mysql:mysql-connector-j`（8.0.33） | 坐标不能换回旧的那个 |
| MySQL | 8.x | 跑在虚拟机 `192.168.133.128:3306`，库名 `learning` |
| jjwt | 0.11.5 | api / impl / jackson 三件套 |
| spring-security-crypto | 由 Spring Boot 管理 | **只引 crypto，没引完整 starter** |
| Lombok | 1.18.30 | provided |
| spring-boot-starter-validation | | 参数校验 |

端口：`8080`

---

## 三、跑起来之前必须做的三件事

**1. 配 `JWT_SECRET` 环境变量**

值是 JWT 的签名密钥，**至少 32 个字符**（HS256 要求 256 位，短了会抛 `WeakKeyException`）。生成方式：

```powershell
(1..32 | ForEach-Object { '{0:x2}' -f (Get-Random -Maximum 256) }) -join ''
```

配在系统环境变量里。**改完必须把 IDEA 完全退出再打开**——Windows 上已经运行的进程读不到新加的环境变量，只重启项目没用。

**2. 重建 `application-local.yml`**

数据库账号密码在这个文件里，已被 `.gitignore` 忽略，换台机器克隆下来是没有的。格式：

```yaml
spring:
  datasource:
    driver-class-name: com.mysql.cj.jdbc.Driver
    url: jdbc:mysql://192.168.133.128:3306/learning?useUnicode=true&characterEncoding=utf-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true
    username: root
    password: 你的密码
jwt:
  secret: ${JWT_SECRET}
  expiration: 3600000
  issuer: learning
```

**3. 建表**

`tb_user`、`tb_article` 是必须的。建表语句见 `文章模块接口文档.md` 第二节。另外 `category` 表已经在设计里，`Category` 实体刚建了空类，但还没做。

> 顺带一提，路径风格要留意：`jwt.expiration` 写 `3600000` 会被 Spring Boot 的 Duration 转换器当成毫秒，也就是 1 小时。

---

## 四、目录结构与分层约定

```
com.jiangpa
├── common        Result、PageResult          —— 通用返回结构
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
- **Service 不依赖 Result**（`UserService` 和 `ArticleService` 里都不该出现 `Result` 的 import）。
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
- `GET /article/{id}` 详情，带作者昵称，**每次调用把 `view_count` 加一**
- `GET /article/list` 分页列表，按创建时间倒序，**只返回摘要不返回正文**
- `POST /article` 发布，作者 id 从 JWT 取
- `PUT /article/{id}` 修改，非作者返回 403
- `DELETE /article/{id}` 删除，非作者返回 403

### 基础设施
- `JwtInterceptor` 全局鉴权，放行 `/auth/**` 和 `/error`
- `GlobalExceptionHandler` 统一处理参数校验、唯一键冲突、业务异常、兜底异常
- `MybatisPlusConfig` 分页插件
- `Result` 支持 200 / 400 / 401 / 403 / 404 / 500

---

## 六、进行中

`com.jiangpa.pojo.Category` 刚建了一个空类，还没写字段。

分类模块的接口没做，评论模块（`comment` 表）也没做。

---

## 七、关键设计决策

**这一节最重要。** 下面每一条都是刻意的，看起来"多此一举"的写法背后都有原因。

### 1. HTTP 状态码一律返回 200

业务状态放在响应体的 `code` 字段里。**这意味着鉴权失败、参数错误、服务器出错，HTTP 层看到的都是 200。**

拦截器（`setStatus(SC_OK)`）和异常处理器都遵循这个约定，保持一致。前端判断成功失败要看 body 里的 `code`。

这个选择本身没有对错，但**必须贯穿到底**——如果哪天有人给某个接口单独设了真实的 HTTP 状态码，前端的两套判断逻辑就会打架。

### 2. 数据库不建物理外键

关联关系靠索引 + 应用层保证。这是互联网项目的普遍做法（《阿里巴巴 Java 开发手册》明确要求），外键会在写入时加锁、影响并发，分库分表后也无法维护。

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

### 5. 浏览量用 SQL 层自增

```java
wrapper.eq(Article::getId, id).setSql("view_count = view_count + 1");
```

**不能写成"先查出来加一再写回"**。后者是读-改-写三步，两个请求同时读到 43、各自加一都写回 44，实际该是 45——一次浏览凭空消失。这是更新丢失问题。

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

拦截器把 `userId` 塞进了 request attribute，Controller 用 `@RequestAttribute` 取。`ArticleDTO` 里**没有** `userId` 字段——如果允许前端传，任何人都能改个数字以别人名义发文。这是最典型的越权漏洞。

### 8. 权限校验顺序：先判断存在，再判断归属

```java
Article article = articleMapper.selectById(id);
if (article == null) throw new BusinessException(404, "文章不存在");
if (!Objects.equals(article.getUserId(), userId)) throw new BusinessException(403, "无权操作他人文章");
```

**顺序不能反**。反了的话，改一篇不存在的文章会返回 403，让人以为是没有权限，排查时容易绕远路。

### 9. 分页插件必须配置

`MybatisPlusConfig` 里的 `MybatisPlusInterceptor` + `PaginationInnerInterceptor`。

**没有它不是报错，而是静默失效**——LIMIT 不会拼进 SQL，查出全表再在内存里截取。数据少的时候完全看不出来，等表里几万条才会发现接口突然变慢。

### 10. 实体主键要标 `@TableId(type = IdType.AUTO)`

不标的话 MyBatis-Plus 会用默认的雪花算法在 Java 端生成 19 位 id，跟数据库的 `AUTO_INCREMENT` 对不上。功能和自增主键完全不同，回填回来的也是雪花值。

---

## 八、待办清单

按建议的优先级排：

| 优先级 | 事项 | 说明 |
| --- | --- | --- |
| 高 | 分类模块 | `Category` 实体是空壳，表已设计好（`id`、`name`，`name` 加唯一索引）。可参照文章模块的三层结构写 |
| 高 | 评论模块 | `comment` 表已设计（`id`、`article_id`、`user_id`、`content`、`create_time`），接口未做 |
| 中 | 用户模块列表加分页 | 现在是全表查，参照文章模块的 `PageResult` 写 |
| 中 | `HttpMessageNotReadableException` 单独处理 | JSON 格式写错、Content-Type 不对时，现在会掉到兜底返回 500，其实应该返 400 |
| 中 | 逻辑删除 | 现在文章、用户都是物理删除，删了不可恢复。加 `deleted` 字段 + MP 的 `@TableLogic` |
| 低 | 文章关联分类 | 现在 `categoryId` 可以存但不校验，分类模块做完后要校验分类是否存在 |
| 低 | 改密码接口 | `UserUpdateDTO` 只能改昵称，改密码要单独开接口 |
| 低 | 分页参数抽公共组件 | 每个列表接口都在重复写 `pageNum`/`pageSize` + 上限截断 |
| 低 | 提示语统一 | 现在有几处文案不一致，见第九节 |

---

## 九、已知的小瑕疵

**提示语不统一。** 同一个意思有几种写法：

- 文章不存在：详情接口抛 `"文章不存在！"`（全角感叹号），修改和删除抛 `"文章不存在"`（无标点）
- 用户名已存在：Service 里查重抛 `"用户名已存在!"`（半角），`GlobalExceptionHandler` 里唯一键冲突兜底返回 `"已存在！"`（全角且很笼统）

`DuplicateKeyException` 那条改成通用的 `"已存在！"` 是因为分类表也要加唯一索引了，但 `"已存在！"` 这个提示对用户来说太模糊，以后可以按异常信息里的索引名区分开。

**`PageResult` 有个没用的五参数构造器。** 全程用的是 setter，这个构造器是死代码，而且三个连续的 `Long` 参数很容易传错顺序还不会编译报错，建议删掉。

**JwtProperties 用 `@Component` + `@ConfigurationProperties` 绑定。** 能用，但更现代的写法是 `@EnableConfigurationProperties` 或 `@ConfigurationPropertiesScan`。

---

## 十、环境相关的坑

这些都是实际踩过的，复发概率高：

| 现象 | 原因 |
| --- | --- |
| 启动报 `Could not resolve placeholder 'JWT_SECRET'` | 环境变量没配，或配了但 IDEA 没完全重启 |
| 启动报 `WeakKeyException` | `JWT_SECRET` 短于 32 字符 |
| `Could not find or load main class main.java.org.example.Xxx` | IDEA 的运行配置还指向旧包名，去 Run → Edit Configurations 改回 `com.jiangpa.Xxx` |
| 报 `Table 'learning.tb_user' doesn't exist` | 表名是 `tb_user`，不是 `user` |
| 登录一直提示密码错误但密码是对的 | 表里那条记录是早期用 MD5 存的，BCrypt 的 `matches` 对 MD5 串只会返回 false，清掉重新注册 |
| Maven 报 `'dependencies.dependency.version' ... is missing` | MySQL 驱动坐标用了旧的 `mysql:mysql-connector-java`，Spring Boot 2.7.8 起改成了 `com.mysql:mysql-connector-j` |
| 一堆 `javax.servlet` 找不到符号 | 抄了面向 Spring Boot 3 的教程。**2.7 用 `javax`，不是 `jakarta`** |
| 拦截器报 `SignatureException` 捕获不到 | jjwt 有两个同名类，要用 `io.jsonwebtoken.security.SignatureException`，`io.jsonwebtoken` 包下那个已废弃 |

---

## 十一、怎么验证改动

每个模块都有一份 Postman 测试文档，按用例走一遍就行。

**测试时的核心前提：HTTP 状态码全是 200，看响应体里的 `code`。**

写新模块的测试时，有几类边界特别容易漏（真实踩过）：

- **空表 / 空结果**——`IN ()` 的语法错误只在表是空的时候出现，开发时表里总有数据，很容易一路测过去都没碰过
- **短输入**——摘要截取、字符串截取的越界，只在输入短的时候暴露
- **并发更新的字段**——更新后要回头确认 `viewCount` 没被重置、`createTime` 没被覆盖
- **权限分支**——**必须用第二个账号**。用同一个账号永远改自己的东西、永远成功，403 那条分支根本触发不到

---

## 十二、文档索引

项目根目录下：

| 文件 | 内容 |
| --- | --- |
| `用户模块接口文档.md` | 用户增删改查的接口定义、`Result` 与状态码约定 |
| `JWT鉴权拦截器文档.md` | 拦截器职责、放行规则、`preHandle` 各步骤、踩坑清单 |
| `全局异常处理器文档.md` | 五类异常的覆盖范围、`BusinessException` 的设计意图 |
| `Postman接口测试文档.md` | 用户与认证模块的测试用例 |
| `文章模块接口文档.md` | 四张表的建表 SQL 与索引设计、文章模块五个接口、关键实现点 |
| `文章模块Postman测试文档.md` | 文章模块的测试用例，含双账号权限测试 |
| `HANDOFF.md` | 本文件 |

> 注意 `文章模块接口文档.md` 里列表接口写的是 `GET /article/list`，代码已按此实现。但**用户模块的修改接口是 `PUT /user`（id 在 body 里）**，跟文章模块的 `PUT /article/{id}`（id 在路径里）风格不一致，建议统一成后者，顺便更新那份文档。
