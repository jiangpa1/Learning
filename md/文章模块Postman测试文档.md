# 文章模块 Postman 测试文档

> 项目：Learning（Spring Boot 2.7.18）
> Base URL：`http://localhost:8081`
> 前置：`tb_article` 表已建好，应用已启动

---

## 一、开始之前

### 1. 依然要记住的那条

**所有响应的 HTTP 状态码都是 200**，业务状态看响应体里的 `code`。文章模块的 401、403、404 在 Postman 右上角同样显示 `200 OK`。

### 2. 环境变量要加一个

沿用之前那个环境（`baseUrl`、`token`），**再加一个 `tokenB`**：

| 变量名 | 初始值 | 用途 |
| --- | --- | --- |
| `baseUrl` | `http://localhost:8081` | |
| `token` | 留空 | 存账号 A 的 token |
| `tokenB` | 留空 | 存账号 B 的 token |

**为什么要两个账号。** 文章模块新增了"只有作者本人能改／删"的权限校验，而**用同一个账号是测不出 403 的**——你改自己的文章永远成功。必须有一个"别人"来触发这条分支。这是本模块唯一无法用单账号验证的功能，也是最容易被漏测的。

### 3. 测之前先清空文章表

```sql
TRUNCATE TABLE tb_article;
```

第一个用例要用空表，所以先清干净。**这一步别跳过**——空表是列表接口最容易出错的状态，而开发过程中表里通常总有数据，很容易一路测过去都没发现问题。

---

## 二、测试顺序

按这个顺序来，每步的产物是下一步的输入：

```
准备账号 A、B  →  空表查列表  →  发短文章  →  发长文章
    →  查列表  →  查详情（看浏览量）  →  更新文章
    →  用 B 尝试改／删 A 的文章  →  删除自己的文章
```

---

## 三、准备两个账号

### 步骤 1：注册账号 A

```
POST {{baseUrl}}/auth/register
Content-Type: application/json
```

```json
{ "username": "zhangsan", "password": "123456", "nickname": "张三" }
```

### 步骤 2：注册账号 B

```json
{ "username": "lisi", "password": "123456", "nickname": "李四" }
```

### 步骤 3：分别登录，把 token 存进不同变量

登录 A 时，Tests 标签页里贴：

```javascript
const res = pm.response.json();
if (res.code === 200) {
    pm.environment.set("token", res.data);
    console.log("tokenA 已保存");
}
```

登录 B 时改成 `pm.environment.set("tokenB", res.data)`。**注意两次登录不要把 `token` 覆盖掉**，否则你手上两个变量都是 B 的，权限测试就失效了。

---

## 四、用例

### 用例 1：空表查询列表

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| URL | `{{baseUrl}}/article/list?pageNum=1&pageSize=10` |
| Header | `Authorization: Bearer {{token}}` |

预期：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "total": 0,
    "pageNum": 1,
    "pageSize": 10,
    "pages": 0,
    "records": []
  }
}
```

**重点看 `code` 是不是 200，而不是 500。**

这条在验证一个容易出问题的边界：表里没有文章时，代码里收集作者 id 的那一步会得到一个空集合，如果直接拿它去批量查用户，拼出来的 SQL 会变成 `WHERE id IN ()`，在 MySQL 里是语法错误。代码里已经加了判空，这条用例就是确认它真的生效了。

如果这里返回 500，说明判空那步没起作用，去看控制台的 SQL 日志。

### 用例 2：发布一篇短文章（正文少于 100 字）

```
POST {{baseUrl}}/article
Authorization: Bearer {{token}}
Content-Type: application/json
```

```json
{
  "title": "第一篇短文",
  "content": "这是一篇很短的测试文章。",
  "categoryId": null
}
```

预期：`code` 为 200，`data` 是新文章的 id（比如 `1`）。

**这条验证的是摘要截取不会越界。** 正文比 100 字短的时候，如果代码直接 `substring(0, 100)` 就会抛 `StringIndexOutOfBoundsException`，接口变成 500。这是新手写摘要时最常踩的坑，一定要先测短的。

> 记下返回的 id，后面都用它。

### 用例 3：发布一篇长文章

```json
{
  "title": "MySQL 索引为什么用 B+ 树",
  "content": "在这里粘贴一段超过 200 字的正文……",
  "categoryId": null
}
```

预期：`code` 为 200。

正文可以随便从哪篇文章复制一段长文本，只要超过 100 字就行。

### 用例 4：查询文章列表

`GET {{baseUrl}}/article/list?pageNum=1&pageSize=10`

预期：`code` 为 200，`data.records` 里有 2 条，且**按创建时间倒序**（刚发的长文章排前面）。

每条记录长这样：

```json
{
  "id": 2,
  "title": "MySQL 索引为什么用 B+ 树",
  "summary": "在这里粘贴一段超过 200 字的正文……",
  "userId": 1,
  "authorNickname": "张三",
  "viewCount": 0,
  "createTime": "2026-09-13T15:20:00"
}
```

要检查三件事：

**一是没有 `content` 字段。** 列表项里只应该有 `summary`，不该出现 `content`。如果全文出来了，说明查询里那句 `wrapper.select(...)` 没生效——这正是"列表页轻量"的设计，必须确认。

**二是 `authorNickname` 是"张三"而不是 null。** 如果这里是 null，说明跨表查昵称那段没工作。

**三是长文章的 `summary` 被截断了**，大约 100 个字加 `...`；短文章的 `summary` 就是原文，没有省略号。

### 用例 5：验证分页上限

`GET {{baseUrl}}/article/list?pageNum=1&pageSize=100`

预期：`code` 为 200，返回的 `data.pageSize` 是 **50** 而不是 100。

文档里定的上限是 50，代码里用 `Math.min(pageSize, 50)` 截断。如果这里原样返回 100，说明上限没生效——那就意味着别人传 `pageSize=100000` 能把整张表拉出来。

### 用例 6：查询文章详情（第一次）

`GET {{baseUrl}}/article/2`

Header 同上。

预期：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 2,
    "title": "MySQL 索引为什么用 B+ 树",
    "content": "完整的正文……",
    "userId": 1,
    "authorNickname": "张三",
    "categoryId": null,
    "viewCount": 1,
    "createTime": "2026-09-13T15:20:00",
    "updateTime": "2026-09-13T15:20:00"
  }
}
```

检查三点：**有 `content` 完整正文**（和列表不同）、`authorNickname` 有值、`viewCount` 是 **1**。

### 用例 7：再查一次详情，确认浏览量累加

再发一次 `GET {{baseUrl}}/article/2`。

预期：`viewCount` 变成 **2**。

**这里要特别留意返回值是不是比数据库少一。** 代码是先读出文章、再执行 `view_count = view_count + 1`，返回给前端的是"读到的值 + 1"。如果哪天有人改了顺序，你会看到数据库里是 5、接口返回 4，这种 off-by-one 很难靠肉眼发现。建议顺手在 MySQL 里执行确认一下：

```sql
SELECT id, title, view_count FROM tb_article WHERE id = 2;
```

数据库里应该是 2，跟接口返回的**一致**。

### 用例 8：修改文章

```
PUT {{baseUrl}}/article/2
Authorization: Bearer {{token}}
Content-Type: application/json
```

```json
{
  "title": "MySQL 索引为什么用 B+ 树（修订版）",
  "content": "这是修改后的正文内容。",
  "categoryId": null
}
```

预期：`code` 为 200。

### 用例 9：确认 createTime 没被覆盖、viewCount 没被重置

这里要拿出用例 6 的响应做对比。

**改完之后再查一次详情** `GET {{baseUrl}}/article/2`，对比这几个字段：

| 字段 | 期望 |
| --- | --- |
| `createTime` | **和用例 6 完全一样**，没有被改动 |
| `updateTime` | 变成了刚才修改的时间，比 `createTime` 晚 |
| `viewCount` | **不是 0**，而是在之前基础上继续加（修改不会重置浏览量） |
| `title` / `content` | 是新的内容 |
| `summary` | 重新生成的，跟新正文对应 |

**`viewCount` 这一条是本模块最需要盯的。** 如果它变成了 0，说明更新逻辑用的是 `updateById` 而不是显式指定列的 `LambdaUpdateWrapper`——实体里 `viewCount` 是基本类型，新建对象不赋值就是 0 而不是 null，会被一起写进 SQL 把浏览量清空。你的代码现在是对的，这条用例是防止以后改坏。

### 用例 10：用账号 B 修改账号 A 的文章

```
PUT {{baseUrl}}/article/2
Authorization: Bearer {{tokenB}}
Content-Type: application/json
```

Body 随便填：

```json
{ "title": "我要改别人的文章", "content": "测试越权" }
```

预期：

```json
{ "code": 403, "message": "无权操作他人文章", "data": null }
```

**这是整个模块最关键的一条用例。** 改完再去确认一下文章内容没被改动。

### 用例 11：用账号 B 删除账号 A 的文章

```
DELETE {{baseUrl}}/article/2
Authorization: Bearer {{tokenB}}
```

预期：`code` 为 403。

同样，删完确认文章还在（`GET /article/2` 仍返回 200）。

### 用例 12：删除自己的文章

用账号 A 的 token：`DELETE {{baseUrl}}/article/2`

预期：`code` 为 200。

### 用例 13：查询已删除的文章

`GET {{baseUrl}}/article/2`

预期：`code` 为 **404**，`message` 为 `"文章不存在！"`。

### 用例 14：标题为空

```
POST {{baseUrl}}/article
```

```json
{ "title": "", "content": "正文" }
```

预期：`code` 为 400，`message` 为 `"标题不能为空"`。

再试一个只传 `"content"` 不传 `title` 的，同样应该是 400。

### 用例 15：不带 token

`GET {{baseUrl}}/article/list`，**不加 `Authorization` 头**。

预期：`code` 为 401，`message` 为 `"未登录，请先登录"`。

### 用例 16：改一篇不存在的文章

用账号 A：`PUT {{baseUrl}}/article/99999`，body 填合法内容。

预期：`code` 为 404，`message` 为 `"文章不存在"`。

**注意这条返回的是 404 而不是 403。** 代码里是先判断文章是否存在、再判断归属，顺序是对的——如果反了，文章不存在时会返回 403，让人以为是没有权限，排查时容易绕远路。

---

## 五、断言脚本

需要 token 的文章接口，在 Tests 里加：

```javascript
const res = pm.response.json();

pm.test("鉴权通过", function () {
    pm.expect(res.code).to.not.eql(401);
});

pm.test("业务成功", function () {
    pm.expect(res.code).to.eql(200);
});
```

列表接口额外验证一下字段：

```javascript
const res = pm.response.json();
const records = res.data.records;

pm.test("列表项不含正文", function () {
    if (records.length > 0) {
        pm.expect(records[0]).to.not.have.property("content");
    }
});

pm.test("列表项带作者昵称", function () {
    if (records.length > 0) {
        pm.expect(records[0].authorNickname).to.be.a("string");
    }
});

pm.test("pageSize 不超过 50", function () {
    pm.expect(res.data.pageSize).to.be.at.most(50);
});
```

权限用例（用例 10、11）的断言：

```javascript
const res = pm.response.json();

pm.test("返回 403", function () {
    pm.expect(res.code).to.eql(403);
});
```

---

## 六、常见问题排查

**列表接口所有记录都没有 `content`，是不是坏了？**
不是，这是设计如此。列表只返回 `summary`，要看正文走详情接口。

**列表里 `authorNickname` 全是 null。**
说明跨表查昵称那段没生效。去看控制台的 SQL 日志，正常应该能看到一条 `SELECT ... FROM tb_user WHERE id IN (?, ?)` 这样的批量查询。

**详情接口返回的 `viewCount` 比数据库里少 1。**
读值和自增这两步的顺序有问题，回去看实现。

**修改文章之后 `viewCount` 变成 0。**
更新逻辑覆盖了不该动的列，检查是不是从 `LambdaUpdateWrapper` 改成了 `updateById`。

**分页没生效，`pageSize=10` 却返回了全部数据。**
分页插件没配或没被扫描到。检查 `MybatisPlusConfig` 里的 `MybatisPlusInterceptor` 是否存在，以及它所在的包在 `com.jiangpa` 之下。

**`Subquery returns more than 1 row` 之类的 SQL 报错。**
一般出现在用 `selectOne` 查了可能有多条结果的场景。文章模块里没有这种查询，如果遇到，先看日志里那条 SQL。

**发文章报 403。**
不太可能，发文接口没有权限校验。如果真报 403，检查是不是请求头里的 token 串了——比如用了账号 B 的 token 去改账号 A 的文章。

---

## 七、一个小的文案不一致

`selectArticleById` 里抛的是 `"文章不存在！"`（带全角感叹号），而 `updateArticle` 和 `deleteArticle` 里抛的是 `"文章不存在"`（没有标点）。同一个意思两种写法，测试时看到两个不同的提示不用慌，顺手统一一下比较好。

用户模块那边也有类似情况（查重抛 `"用户名已存在!"` 半角、唯一索引兜底抛 `"用户名已存在！"` 全角），可以一起收拾掉。
