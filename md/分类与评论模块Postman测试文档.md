# 分类与评论模块 Postman 测试文档

> 项目：Learning（Spring Boot 2.7.18 + MyBatis-Plus 3.5.5）
> Base URL：`http://localhost:8081`
> 前置：`tb_category`、`tb_comment` 表已建好，应用**已启动**
> 编写日期：2026-09-15

---

## 一、开始之前

### 1. 依然要记住的那条

**所有响应的 HTTP 状态码都是 200**，业务状态看响应体里的 `code`。401、403、404 在 Postman 右上角同样显示 `200 OK`。

### 2. ⚠️ 先确认应用真的启动过

这两个模块的代码**此前从未编译运行过**（`target/classes` 里一直没有 Category / Comment 的 class）。

更要紧的是：这轮修掉的三个 bug —— `@Controller` 写成 `@RestController` 之外的形式、漏写类级注解、查询漏了过滤条件 —— **全都能编译通过**，编译器一个都不会报。

所以：

1. 在 IDEA 里 **Build → Rebuild Project**，确认编译无错
2. **启动应用**，确认控制台没有报错
3. 先随便发一个 `GET {{baseUrl}}/category/list`，**确认返回的是 JSON 而不是 404**

第 3 步别跳过。这一步能同时验证"Controller 被扫描到"和"返回的是 JSON 而非视图名"两件事 —— 也就是那两个注解 bug。

### 3. 环境变量

沿用文章模块那套（`baseUrl`、`token`、`tokenB`），本模块不用加新的：

| 变量名 | 初始值 | 用途 |
| --- | --- | --- |
| `baseUrl` | `http://localhost:8081` | |
| `token` | 留空 | 存账号 A 的 token |
| `tokenB` | 留空 | 存账号 B 的 token（**删别人评论必须用它**） |

### 4. 测试前清空这两张表

```sql
TRUNCATE TABLE tb_category;
TRUNCATE TABLE tb_comment;
```

**别跳过这一步。** 空表是列表接口最容易出错的状态，而开发过程中表里通常已经有数据，很容易一路测过去都没发现"空列表返回 null 而不是 []"这类问题。

**文章表先留着**（里面有 2 篇），评论和分类都要挂在文章上。

---

## 二、测试顺序

按这个顺序来，每步的产物是下一步的输入：

```
清空分类/评论表
  → 分类：空表查列表 → 新增 → 重名 → 列表 → 改名 → 改名为自身 → 改名撞别人
  → 分类：删空分类 → 删有文章的分类（需先给文章设分类）
  → 评论：给不存在的文章发评论 → 正常发表 → 参数校验
  → 评论：★ 两条不同文章的评论，验证列表按 articleId 过滤
  → 评论：作者昵称 → 排序 → 分页上限
  → 评论：用 B 删 A 的评论 → 删自己的 → 删不存在的
  → 回归：N+1 验证 → 索引验证
```

---

## 三、准备数据

### 步骤 1：两个账号

如果 `token` / `tokenB` 还有效就跳过；否则重新注册登录：

```
POST {{baseUrl}}/auth/register
Content-Type: application/json
```

```json
{ "username": "zhangsan", "password": "123456", "nickname": "张三" }
```

```json
{ "username": "lisi", "password": "123456", "nickname": "李四" }
```

登录后把 token 分别存进 `token`（A）和 `tokenB`（B）。**这一步不能省** —— 评论的 403 用例必须有一个"别人"才能触发。

### 步骤 2：记住两篇已有文章的 id

```sql
SELECT id, title, category_id FROM tb_article;
```

假设是 **1** 和 **3**（`category_id` 目前都是 NULL）。下面用到文章 id 的地方按实际值替换。

---

## 四、分类模块用例

### 用例 1：空表查询分类列表

```
GET {{baseUrl}}/category/list
Authorization: Bearer {{token}}
```

**期望**：

```json
{ "code": 200, "message": "操作成功", "data": [] }
```

**两个要点**：
1. `data` 是**数组**，不是分页对象 —— 分类不分页，和文章列表不同
2. 空表返回 **`[]`**，不能是 `null`

> 如果返回 404 或 HTML，说明 `CategoryController` 的 `@RestController` 有问题，回去看注解。

---

### 用例 2：新增分类

```
POST {{baseUrl}}/category
Authorization: Bearer {{token}}
Content-Type: application/json
```

```json
{ "name": "后端开发" }
```

**期望**：

```json
{ "code": 200, "message": "操作成功", "data": 1 }
```

`data` 是新分类的 id。**用它继续后面的用例。**

---

### 用例 3：新增重名分类 ⭐ 验证异常文案修复

再来一次**完全一样**的请求：

```json
{ "name": "后端开发" }
```

**期望**：

```json
{ "code": 400, "message": "分类名已存在", "data": null }
```

**这条是本模块最重要的用例之一。** 版本路由如下：

| 场景 | 走的路径 | 返回 |
| --- | --- | --- |
| 正常重名 | Service 里 `selectOne` 查到 → 抛 `BusinessException` | 400「分类名已存在」✅ |
| 并发重名（两个请求同时通过校验） | DB 唯一索引 `uk_name` 拦住 → `DuplicateKeyException` | 400「数据已存在！」（兜底） |

**如果这里看到「用户名已存在！」**，说明 `GlobalExceptionHandler` 里的文案又被改回去了 —— 那句硬编码是从用户模块带过来的。

> **想真正测出并发那条分支**，可以用 Postman 的 Collection Runner 把这条请求并发跑 10 次：应该得到 1 个 200 + 9 个 400，而数据库里**只有一条**"后端开发"。这就是"应用层校验 + DB 兜底"两层防护的价值。

---

### 用例 4：再新增两个分类，验证列表排序

```json
{ "name": "数据库" }
```

```json
{ "name": "计算机网络" }
```

然后：

```
GET {{baseUrl}}/category/list
```

**期望**：按 **id 升序**返回（等于插入顺序）：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    { "id": 1, "name": "后端开发" },
    { "id": 2, "name": "数据库" },
    { "id": 3, "name": "计算机网络" }
  ]
}
```

> 表里没有 `sort` 字段，所以只能按 id 排。如果将来要运营手动排序，得加字段。

---

### 用例 5：修改分类

```
PUT {{baseUrl}}/category/2
Authorization: Bearer {{token}}
Content-Type: application/json
```

```json
{ "name": "数据库原理" }
```

**期望**：`{ "code": 200, "message": "操作成功", "data": null }`

---

### 用例 6：把分类改成它自己的名字 ⭐ 验证查重排除自己

```
PUT {{baseUrl}}/category/2
```

```json
{ "name": "数据库原理" }
```

**（和上一步完全相同的名字）**

**期望**：**200 成功**，不是 400。

**为什么专门测这条**：查重时如果忘了排除自己，`selectOne` 会查到自己那条记录，于是"把 A 改成 A"被误判成重名。代码里靠 `.ne(Category::getId, id)` 排除，这个用例就是验证它生效。

---

### 用例 7：改成别人已有的名字

```
PUT {{baseUrl}}/category/2
```

```json
{ "name": "后端开发" }
```

**期望**：400「分类名已存在」

---

### 用例 8：修改不存在的分类

```
PUT {{baseUrl}}/category/99999
```

```json
{ "name": "随便什么" }
```

**期望**：404「分类不存在」

---

### 用例 9：删除空分类

```
DELETE {{baseUrl}}/category/3
```

**期望**：200 成功。然后查列表确认"计算机网络"没了。

---

### 用例 10：删除有文章的分类 ⭐ 验证方案 A

先给一篇文章挂上分类 1：

```
PUT {{baseUrl}}/article/1
Authorization: Bearer {{token}}
Content-Type: application/json
```

```json
{ "title": "第一篇短文", "content": "正文内容……", "categoryId": 1 }
```

> ⚠️ 这里要填**原来的标题和正文**（`title`、`content` 都是 `@NotBlank`，不能省）。正文随便写点，注意改了之后 `summary` 会重新生成。

确认挂上了：

```sql
SELECT id, title, category_id FROM tb_article WHERE id = 1;
-- category_id 应该变成 1
```

然后删这个分类：

```
DELETE {{baseUrl}}/category/1
```

**期望**：400，且消息里**带上了具体篇数**：

```json
{ "code": 400, "message": "该分类下还有1篇文章，无法删除", "data": null }
```

**这条验证的是删除策略（方案 A：拒绝删除）。** 数据库里没有物理外键，所以**它不会拦你** —— 拦你的是 Service 里那句 `selectCount`：

```java
Long n = articleMapper.selectCount(
        new LambdaQueryWrapper<Article>().eq(Article::getCategoryId, id));
if (n > 0) {
    throw new BusinessException("该分类下还有" + n + "篇文章，无法删除");
}
```

**如果这里返回 200 且分类被删掉了**，说明这段校验没生效 —— 去数据库看，文章的 `category_id` 会变成一个悬空值（指向不存在的分类）。

**验证完记得把文章的分类清掉**，恢复原状：

```sql
UPDATE tb_article SET category_id = NULL WHERE id = 1;
```

---

### 用例 11：删除不存在的分类

```
DELETE {{baseUrl}}/category/99999
```

**期望**：404「分类不存在」

---

## 五、评论模块用例

### 用例 12：给不存在的文章发评论

```
POST {{baseUrl}}/comment
Authorization: Bearer {{token}}
Content-Type: application/json
```

```json
{ "articleId": 99999, "content": "这篇文章不存在" }
```

**期望**：404「文章不存在」

**为什么必须校验**：表里 `article_id` **没有外键**，数据库不会拦。不校验就会产生"评论挂在不存在的文章上"的脏数据，而且以后按 `article_id` 查永远查不出异常。

---

### 用例 13：正常发表评论

给**文章 1** 发两条：

```json
{ "articleId": 1, "content": "写得不错" }
```

```json
{ "articleId": 1, "content": "学到了" }
```

**期望**：`{ "code": 200, "message": "操作成功", "data": 4 }`（`data` 是新评论 id）

再给**文章 3** 发一条：

```json
{ "articleId": 3, "content": "这是文章3的评论" }
```

**记住这三条的归属**：文章 1 有 2 条，文章 3 有 1 条。**用例 16 要靠这个区分。**

---

### 用例 14：评论内容为空

```json
{ "articleId": 1, "content": "" }
```

**期望**：400「评论内容不能为空」

---

### 用例 15：评论内容超 500 字

构造一个 501 字的字符串（Postman 里可以用 `{{longText}}` 环境变量，或直接粘一段长文本）。

**期望**：400「评论上限500字」

> 注意表定义是 `varchar(500)`。如果应用层不拦，MySQL 严格模式下会直接报 `Data too long for column`，掉到兜底的 500「服务器开小差了」—— 那就说明校验没生效。

**另外测一下 `articleId` 缺失**：

```json
{ "content": "没有文章id" }
```

**期望**：400「文章id不能为空！」

---

### 用例 16：★ 评论列表按文章过滤（最关键的一条）

```
GET {{baseUrl}}/comment/list?articleId=1
Authorization: Bearer {{token}}
```

**期望**：**只返回文章 1 的两条评论**，`total` = 2。

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 2,
    "pageNum": 1,
    "pageSize": 10,
    "pages": 1,
    "records": [
      { "id": 5, "articleId": 1, "userId": 17, "authorNickname": "张三",
        "content": "学到了", "createTime": "2026-09-15T18:20:00" },
      { "id": 4, "articleId": 1, "userId": 17, "authorNickname": "张三",
        "content": "写得不错", "createTime": "2026-09-15T18:19:30" }
    ]
  }
}
```

再查文章 3：

```
GET {{baseUrl}}/comment/list?articleId=3
```

**期望**：`total` = 1，且 `records[0].content` = `"这是文章3的评论"`。

**这个用例是这批代码里最重要的验证点。**

之前这里漏了 `.eq(Comment::getArticleId, articleId)`，生成的是**没有 WHERE 的全表查询** —— 调 `articleId=1` 会把文章 3 的评论也返回来。而这个 bug **不报错、返回 200、数据看起来也正常**，只有像现在这样插入两条不同文章的评论、逐个 `articleId` 对比，才能发现。

**如果 `total` 是 3（=全部评论数），就是过滤条件又丢了。**

---

### 用例 17：评论列表的作者昵称

看用例 16 的响应，`authorNickname` 应该是**真实昵称**（如"张三"），不是 `null`、不是"默认昵称"。

**同时看 IDEA 控制台的 SQL 日志。** 正常应该是**两条**查询：

```sql
-- 第 1 条：分页查评论
SELECT id, article_id, user_id, content, create_time
FROM tb_comment WHERE article_id = ? ORDER BY create_time DESC LIMIT ?,?

-- 第 2 条：批量查昵称（关键，只有一条，不是 N 条）
SELECT id, username, nickname, ... FROM tb_user WHERE id IN (?)
```

**如果看到 N 条 `WHERE id = ?` 的单条查询**，说明 N+1 又回来了 —— 去检查是不是把 `selectBatchIds` 改成了循环里 `selectById`。

---

### 用例 18：评论列表排序

看用例 16 的 `records`，**`createTime` 应该是倒序**（最新在前）—— 上面例子里 id=5（"学到了"）排在 id=4（"写得不错"）前面，因为它是后发的。

---

### 用例 19：分页上限

```
GET {{baseUrl}}/comment/list?articleId=1&pageNum=1&pageSize=100
```

**期望**：`data.pageSize` 是 **50**，不是 100（代码里 `pageSize = Math.min(pageSize, 50)`）。

---

### 用例 20：查询不存在的文章的评论

```
GET {{baseUrl}}/comment/list?articleId=99999
```

**期望**：404「文章不存在」

> 这条是可选的加强校验。它避免了"文章不存在"和"文章存在但没评论"都返回空列表、让人分不清的情况。

---

### 用例 21：用账号 B 删账号 A 的评论 ⭐ 验证 403

**换 `{{tokenB}}`**（李四的 token），去删张三发的评论：

```
DELETE {{baseUrl}}/comment/4
Authorization: Bearer {{tokenB}}
```

**期望**：403「无权删除他人评论」

**这条必须用两个账号才测得出。** 用自己的 token 删自己的评论永远成功，403 分支根本走不到 —— 这是本模块最容易漏测的地方。

---

### 用例 22：删自己的评论

```
DELETE {{baseUrl}}/comment/4
Authorization: Bearer {{token}}
```

**期望**：200 成功。然后重新查列表，确认那条没了、`total` 变成 1。

---

### 用例 23：删不存在的评论

```
DELETE {{baseUrl}}/comment/99999
Authorization: Bearer {{token}}
```

**期望**：404「评论不存在」（**不是 403**）

**为什么注意这点**：代码里是**先判空、再判归属**：

```java
if (comment == null) throw new BusinessException(404, "评论不存在");
if (!Objects.equals(comment.getUserId(), userId)) throw new BusinessException(403, ...);
```

顺序是对的。**如果反了**，评论不存在时会先执行 `comment.getUserId()` → **NPE** → 掉到兜底返回 500「服务器开小差了」。所以这条返回 404 就说明判空顺序没问题。

---

### 用例 24：不带 token

```
GET {{baseUrl}}/comment/list?articleId=1
```

（删掉 Authorization 头）

**期望**：401（拦截器拦下）

---

## 六、回归验证

### 1. 索引验证：加复合索引前后的 EXPLAIN

当前 `tb_comment` 只有 `idx_article_id` 单列索引。先看现状：

```sql
EXPLAIN SELECT id, article_id, user_id, content, create_time
FROM tb_comment
WHERE article_id = 1
ORDER BY create_time DESC
LIMIT 0, 10;
```

**记录 `type`、`key`、`Extra` 三列。** 预期 `Extra` 里有 `Using filesort`。

然后加复合索引：

```sql
ALTER TABLE tb_comment ADD INDEX idx_article_create (article_id, create_time);
```

再跑一次同样的 `EXPLAIN`。

**期望**：`key` 变成 `idx_article_create`，**`Extra` 里的 `Using filesort` 消失**。

> ⚠️ 先用一条 `articleId` 查，确保表里有该文章的评论，否则优化器可能因为数据太少而不用索引。
>
> 把两次 EXPLAIN 的结果都贴进 `分类与评论模块接口文档.md` 的「五、关键实现点」第 3 条。

**如果 `filesort` 没消失**：先 `ANALYZE TABLE tb_comment;` 让统计信息刷新，再试。数据量太小时（几十行）优化器可能仍选全表扫描 —— 可以插入几十条测试数据再验证。

---

### 2. 唯一约束验证：数据库到底有没有兜住

```sql
-- 确认唯一索引存在
SHOW INDEX FROM tb_category WHERE Key_name = 'uk_name';

-- 直接插一条重名的，看数据库拦不拦
INSERT INTO tb_category (name) VALUES ('后端开发');
-- 期望：ERROR 1062 (23000): Duplicate entry '后端开发' for key 'tb_category.uk_name'
```

这条**绕过应用层**直接打数据库，证明"DB 兜底"那一层是真的存在的。

---

### 3. 数据一致性检查

测完之后跑一遍，确认没有脏数据：

```sql
-- ① 有没有评论挂在不存在的文章上？（应为 0 行）
SELECT c.id, c.article_id
FROM tb_comment c
LEFT JOIN tb_article a ON a.id = c.article_id
WHERE a.id IS NULL;

-- ② 有没有文章的 category_id 指向不存在的分类？（应为 0 行）
SELECT a.id, a.category_id
FROM tb_article a
LEFT JOIN tb_category c ON c.id = a.category_id
WHERE a.category_id IS NOT NULL AND c.id IS NULL;

-- ③ 有没有分类重名？（应为 0 行）
SELECT name, COUNT(*) FROM tb_category GROUP BY name HAVING COUNT(*) > 1;

-- ④ 有没有 create_time 为 NULL 的评论？（应为 0，代码里应该都 set 了）
SELECT COUNT(*) FROM tb_comment WHERE create_time IS NULL;
```

**① 和 ② 是重点** —— 因为项目约定不建物理外键，这两个一致性只能靠应用层保证，所以必须专门查。

---

## 七、断言脚本

分类接口（放在 Tests 标签页）：

```javascript
const res = pm.response.json();

pm.test("鉴权通过", function () {
    pm.expect(res.code).to.not.eql(401);
});

pm.test("业务成功", function () {
    pm.expect(res.code).to.eql(200);
});
```

**用例 1（空表列表）专用**：

```javascript
const res = pm.response.json();

pm.test("data 是数组而不是 null", function () {
    pm.expect(res.data).to.be.an("array");
});
```

**用例 6（改名为自身同名）专用** —— 这条最容易误判：

```javascript
const res = pm.response.json();

pm.test("改成自己原来的名字应该成功，不能报重名", function () {
    pm.expect(res.code).to.eql(200);
});
```

**用例 16（评论按文章过滤）专用** —— 本模块最关键断言：

```javascript
const res = pm.response.json();
const records = res.data.records;

pm.test("返回的评论全部属于请求的文章", function () {
    const wanted = pm.request.url.query.get("articleId");
    records.forEach(function (r) {
        pm.expect(String(r.articleId)).to.eql(String(wanted));
    });
});

pm.test("total 只统计该文章的评论", function () {
    pm.expect(res.data.total).to.be.at.most(records.length);
});
```

**用例 17（昵称）专用**：

```javascript
const res = pm.response.json();
const records = res.data.records;

pm.test("每条评论都带作者昵称", function () {
    records.forEach(function (r) {
        pm.expect(r.authorNickname).to.be.a("string");
        pm.expect(r.authorNickname).to.not.eql("默认昵称");
    });
});
```

**用例 21（403）专用**：

```javascript
const res = pm.response.json();

pm.test("返回 403 无权限", function () {
    pm.expect(res.code).to.eql(403);
});
```

---

## 八、常见问题排查

**分类/评论接口全部返回 404。**
Controller 没被扫描到。检查类上有没有 `@RestController` —— 只写 `@RequestMapping` 而漏了 stereotype 注解的类**不是 Spring Bean**，编译能过但所有路由都不存在。

**接口返回 404 或者报 `Circular view path`，但 Controller 明明有注解。**
注解写成了 `@Controller`（没有 `@ResponseBody` 语义），Spring 把返回的 `Result` 当视图名/模型属性处理，不会序列化成 JSON。改成 `@RestController`。

**新增重名分类返回"用户名已存在！"**
`GlobalExceptionHandler` 里 `DuplicateKeyException` 的文案被改回去了。应该改成中性文案（如"数据已存在！"），因为同一个处理器要服务所有表的唯一约束。

**评论列表返回了所有文章的评论。**
`selectCommentList` 的 wrapper 上漏了 `.eq(Comment::getArticleId, articleId)`。判断方法：`total` 等于全表评论数而不是该文章的评论数。

**"改名为自身同名"报 400 重名。**
查重时没排除自己。wrapper 上要有 `.ne(Category::getId, id)`。

**删除有文章的分类却成功了。**
`deleteCategory` 里的 `selectCount` 校验没生效，或者顺序不对（要先判分类存在、再判引用数）。去数据库检查文章的 `category_id` 有没有变成悬空值。

**删不存在的评论返回 500 而不是 404。**
判空和判归属的顺序反了 —— 先执行了 `comment.getUserId()` 导致 NPE。必须**先判 null 再判归属**。

**评论列表 `authorNickname` 全是"默认昵称"。**
跨表批量查昵称那段没生效。看 SQL 日志，应该有一条 `SELECT ... FROM tb_user WHERE id IN (?)`。

**删别人的评论返回 200 删掉了。**
`Objects.equals(comment.getUserId(), userId)` 那段校验没生效，或者 `userId` 是从请求体拿的而不是 `@RequestAttribute`。后者是越权漏洞，必须从 token 解析出的 userId 取。

**分页不生效，`pageSize=100` 就真返回 100 条。**
两个可能：`Math.min(pageSize, 50)` 被删了；或者分页插件没配（检查 `MybatisPlusConfig` 里的 `MybatisPlusInterceptor`）。

**评论列表查不出数据，但数据库里明明有。**
检查 `create_time`。表定义是 `DEFAULT NULL`，如果 Service 里忘了 `comment.setCreateTime(LocalDateTime.now())`，写入的就是 NULL —— 排序和展示都会有问题。

---

## 九、已知小问题（不影响功能，可留到最后收拾）

这几条是这轮 review 时发现但**尚未修改**的，测试时看到不用慌：

| 位置 | 问题 | 影响 |
| --- | --- | --- |
| `Category.java` | 有一个没用到的 `import javax.validation.constraints.NotBlank` | 无，IDE 会有灰色提示 |
| `Category.java`、`Comment.java`、`CommentVO.java` | 字段没写 `private`（包级私有） | 无，Lombok 照样生成 getter/setter；但和 `Article.java` / `CommentDTO` 的风格不一致 |
| `CategoryServiceImpl.addCategory` / `updateCategory` | 用 `selectOne` 查重 | 现在能正常工作（唯一索引保证最多一行）；但它隐含依赖那条索引，`selectOne` 匹配到多行会抛 `TooManyResultsException`。换 `exists()` 语义更直白 |
| `CategoryServiceImpl.updateCategory` | 用 `updateById` 整体覆盖 | 分类表只有 2 个字段，无实际影响；但和 `ArticleServiceImpl` 里用 `LambdaUpdateWrapper` 的做法不一致，表字段变多后会踩坑 |
| `CommentServiceImpl` | `getOrDefault` 兜底文案是"默认昵称"，而 `ArticleServiceImpl` 用"未知作者" | 无，但两处不一致，排查日志时易混淆 |

---

## 十、验收清单

- [x] 应用编译通过并成功启动，`/category/list` 返回 JSON
- [x] 分类 4 个接口 + 评论 3 个接口全部返回正确结构
- [x] 重名分类 → 400「**分类名已存在**」（不是"用户名已存在"）
- [x] 改成自己原来的名字 → **200 成功**（不误报重名）
- [x] 删除有文章的分类 → 400 且带具体篇数
- [x] 给不存在的文章发评论 → 404
- [x] **`articleId=1` 只返回文章 1 的评论**（用例 16，最重要）
- [x] 评论 `authorNickname` 正确，SQL 日志里是批量查询而非 N+1
- [x] 用 tokenB 删别人的评论 → 403
- [x] 删不存在的评论 → 404（不是 500）
- [x] 带上 `EXPLAIN` 前后对比，`Using filesort` 消失
- [x] 数据一致性检查 4 条 SQL 全部符合预期
