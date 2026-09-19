# Postman 接口测试文档

> 项目：Learning（Spring Boot 2.7.18）
> Base URL：`http://localhost:8081`

---

## 一、开始之前

### 1. 一个必须知道的设定

**所有响应的 HTTP 状态码都是 200。**

你的项目采用的是「HTTP 一律返 200，业务状态放在响应体的 `code` 里」这套方案。鉴权失败的 401、参数错误的 400、用户不存在的 404、服务器出错的 500——在 Postman 右上角看到的 Status **全都是 200 OK**。

所以测试时的判断依据是**响应体里的 `code` 字段，不是 Postman 显示的 Status**。如果你习惯性看到 200 就以为成功了，会漏掉所有错误分支。

### 2. 状态码含义速查

| code | 含义 | 典型场景 |
| --- | --- | --- |
| 200 | 成功 | 正常返回 |
| 400 | 参数或业务校验失败 | 用户名重复、密码错误、字段为空 |
| 401 | 未登录 / token 无效 | 没带 token、token 过期或被篡改 |
| 404 | 资源不存在 | 按 id 查／改／删时找不到用户 |
| 500 | 服务器内部错误 | 未预期的异常 |

### 3. 配置环境变量

在 Postman 左侧「Environments」新建一个环境，比如叫 `Learning 本地`，添加两个变量：

| 变量名 | 初始值 | 类型 |
| --- | --- | --- |
| `baseUrl` | `http://localhost:8081` | default |
| `token` | 留空 | default（secret 可不开，方便查看） |

建好之后**记得在右上角的下拉框里选中这个环境**，不选的话 `{{baseUrl}}` 会原样发出去。

### 4. 让 Postman 自动保存 token

登录成功后拿到的 token 要手动复制粘贴到每个请求里，很麻烦。贴一段脚本让它自动存：

打开「登录」这个请求 → 切到 **Tests** 标签页 → 粘贴：

```javascript
const res = pm.response.json();
if (res.code === 200) {
    pm.environment.set("token", res.data);
    console.log("token 已保存：" + res.data.substring(0, 20) + "...");
}
```

这样每次登录，环境变量 `token` 会自动更新，后面所有请求直接用 `{{token}}` 引用。

### 5. 启动前置检查

跑测试之前确认：MySQL 在运行、`JWT_SECRET` 环境变量已配置、`LearningApplication` 已启动且控制台没有报错。

---

## 二、测试顺序

接口之间有依赖，按这个顺序来：

```
注册  →  登录（拿到 token）  →  其余所有 /user 接口（都要带 token）
```

`/auth/**` 下的两个接口不需要 token（拦截器已放行）；`/user/**` 下的四个接口**全部需要**。

如果跳过登录直接测 `/user`，会全部返回 401，那不是接口坏了。

---

## 三、认证接口用例

这两个接口**不需要**带 `Authorization` 头。

### 用例 1：注册成功

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| URL | `{{baseUrl}}/auth/register` |
| Header | `Content-Type: application/json` |

请求体：

```json
{
  "username": "zhangsan",
  "password": "123456",
  "nickname": "张三"
}
```

预期响应：

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "id": 1,
    "username": "zhangsan",
    "nickname": "张三"
  }
}
```

检查三点：`data.id` 有值（说明自增主键回填成功）、字段值正确、**`data` 里不能出现 `password`**。最后这条是重点，如果密码字段漏出来了，说明 `UserVO` 的转换有问题。

> 记下响应里的 `id`，后面用例会用到。

### 用例 2：不传昵称，自动用用户名兜底

请求体：

```json
{
  "username": "lisi",
  "password": "123456"
}
```

预期：`code` 为 200，且 `data.nickname` 等于 `"lisi"`。

### 用例 3：用户名为空

请求体：

```json
{
  "username": "",
  "password": "123456"
}
```

预期：`code` 为 400，`message` 为 `"用户名不能为空"`。

### 用例 4：用户名长度不足

请求体：

```json
{
  "username": "ab",
  "password": "123456"
}
```

预期：`code` 为 400，`message` 为 `"用户名长度必须在 4-20 位之间"`。

### 用例 5：密码长度不足

请求体：

```json
{
  "username": "wangwu",
  "password": "123"
}
```

预期：`code` 为 400，`message` 为 `"密码长度必须在 6-20 位之间"`。

### 用例 6：注册已存在的用户名

请求体：

```json
{
  "username": "zhangsan",
  "password": "123456"
}
```

预期：`code` 为 400，`message` 为 `"用户名已存在!"`。

> 这条走的是 Service 里的查重分支。另有一条走数据库唯一索引的分支（并发时才会触发），提示语是 `"用户名已存在！"`，标点不同——这是代码里两处文案没对齐，不是 bug，但可以记一下。

### 用例 7：登录成功

| 项 | 内容 |
| --- | --- |
| 方法 | `POST` |
| URL | `{{baseUrl}}/auth/login` |
| Header | `Content-Type: application/json` |

请求体：

```json
{
  "username": "zhangsan",
  "password": "123456"
}
```

预期：`code` 为 200，`data` 是一串 JWT。

JWT 长这样，**由三个点分成三段**，以 `eyJ` 开头：

```
eyJhbGciOiJIUzI1NiJ9.eyJpZCI6MSwidXNlcm5hbWUiOiJ6aGFuZ3NhbiJ9.xxxxx
```

看到这个格式说明签发成功。别把第三段（签名）弄丢或改错。

> 如果你在第 4 节贴了自动保存脚本，这里 `token` 环境变量就已经有值了。去 Environments 里确认一下。

### 用例 8：密码错误

```json
{
  "username": "zhangsan",
  "password": "wrongpassword"
}
```

预期：`code` 为 400，`message` 为 `"用户名或密码错误"`。

### 用例 9：用户名不存在

```json
{
  "username": "nobody999",
  "password": "123456"
}
```

预期：`code` 为 400，`message` 为 `"用户名或密码错误"`。

**注意用例 8 和用例 9 的提示语、状态码完全一样，这是有意设计的。** 如果两者能区分开，攻击者就能拿一批用户名去试探，凡是回"密码错误"的就说明账号真实存在——这是用户名枚举漏洞。所以这两条必须一模一样，别"优化"成更详细的提示。

---

## 四、用户接口用例

下面四个接口**都必须带 token**。在 Postman 的 Headers 里加一行：

| Key | Value |
| --- | --- |
| `Authorization` | `Bearer {{token}}` |

注意 `Bearer` 和 `{{token}}` 之间**有一个空格**，别漏了。

### 用例 10：查询单个用户

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| URL | `{{baseUrl}}/user/1` |

无请求体。

预期：`code` 为 200，`data` 是用户信息且**不含 `password`**。

### 用例 11：查询用户列表

| 项 | 内容 |
| --- | --- |
| 方法 | `GET` |
| URL | `{{baseUrl}}/user/list` |

预期：`code` 为 200，`data` 是数组。没有任何用户时应该是空数组 `[]`，不是 `null`。

> 这里有个可能让你困惑的点：`/user/list` 和 `/user/{id}` 的路径形状很像，为什么 `list` 不会被当成 id？因为 Spring 在匹配时**优先选字面量路径**，`/user/list` 是写死的字面量，比 `/user/{id}` 这种模板更具体，所以会命中前者。如果反过来命中不了，你会看到 `list` 被拿去转 `Long` 然后报类型转换错误。

### 用例 12：修改用户

| 项 | 内容 |
| --- | --- |
| 方法 | `PUT` |
| URL | `{{baseUrl}}/user` |
| Header | `Content-Type: application/json` |

请求体：

```json
{
  "id": 1,
  "nickname": "张三丰"
}
```

预期：`code` 为 200。改完用用例 10 再查一次，确认 `nickname` 变了、`updateTime` 也更新了。

### 用例 13：删除用户

| 项 | 内容 |
| --- | --- |
| 方法 | `DELETE` |
| URL | `{{baseUrl}}/user/2` |

预期：`code` 为 200。删完再查一次，应该返回 404。

---

## 五、鉴权失败用例

这组用例是验证拦截器有没有真正生效，**不要跳过**。

### 用例 14：完全不带 token

请求 `GET {{baseUrl}}/user/1`，**不加 `Authorization` 头**。

预期：`code` 为 401，`message` 为 `"未登录，请先登录"`。

### 用例 15：token 格式错误

请求 `GET {{baseUrl}}/user/1`，Authorization 头填：

```
abc123
```

预期：`code` 为 401，`message` 为 `"token 格式错误"`。

再试一个 `Bearer`（只有前缀、后面没内容）的情况，预期同样是 `"token 格式错误"`。

### 用例 16：token 被篡改（最重要的一条）

拿用例 7 得到的真 token，**把最后三个字符随便改掉**，然后请求 `GET {{baseUrl}}/user/1`。

预期：`code` 为 401，`message` 为 `"token 无效"`。

**这条是整个鉴权测试里最关键的一步。** 如果改了签名还能返回 200，说明服务端根本没校验签名，也就是说任何人都能自己编一个 token 冒充别人——这是致命的。很多人只测"不带 token 会不会被拦"，不测这条，就漏掉了真正的问题。

### 用例 17：token 过期

这条要改配置，测完记得改回来。

把 `application-local.yml` 里的 `expiration` 从 `3600000`（1 小时）改成 `5000`（5 秒），重启应用，登录拿到新 token，**等 6 秒**，再请求 `GET {{baseUrl}}/user/1`。

预期：`code` 为 401，`message` 为 `"登录已过期，请重新登录"`。

验证完把 `expiration` 改回 `3600000` 并重启——不然你后面每次测试都要重新登录。

---

## 六、资源不存在用例

### 用例 18：查询不存在的用户

`GET {{baseUrl}}/user/99999`

预期：`code` 为 404，`message` 为 `"用户不存在！"`。

### 用例 19：修改不存在的用户

`PUT {{baseUrl}}/user`，请求体 `{"id": 99999, "nickname": "x"}`

预期：`code` 为 404。

### 用例 20：删除不存在的用户

`DELETE {{baseUrl}}/user/99999`

预期：`code` 为 404。

### 用例 21：修改时不传 id

`PUT {{baseUrl}}/user`，请求体：

```json
{
  "nickname": "x"
}
```

预期：`code` 为 400，`message` 为 `"用户id不能为空"`。

这条验证的是 `UserUpdateDTO` 上的 `@NotNull` 有没有被触发。如果返回的是 404"用户不存在"，说明 Controller 上的 `@Valid` 没生效，参数校验被跳过了。

---

## 七、进阶：给请求加断言脚本

手动看响应比较累，可以在 Tests 标签页里写断言，让 Postman 自动判断通过与否。

登录请求（同时存 token 并断言）：

```javascript
const res = pm.response.json();

pm.test("code 为 200", function () {
    pm.expect(res.code).to.eql(200);
});

pm.test("返回了 token", function () {
    pm.expect(res.data).to.be.a("string");
    pm.expect(res.data.length).to.be.above(0);
});

if (res.code === 200) {
    pm.environment.set("token", res.data);
}
```

需要 token 的接口，加一条"未被拦下"的断言：

```javascript
const res = pm.response.json();

pm.test("鉴权通过", function () {
    pm.expect(res.code).to.not.eql(401);
});

pm.test("业务成功", function () {
    pm.expect(res.code).to.eql(200);
});
```

鉴权失败用例（比如用例 16）的断言：

```javascript
const res = pm.response.json();

pm.test("返回 401", function () {
    pm.expect(res.code).to.eql(401);
});

pm.test("提示 token 无效", function () {
    pm.expect(res.message).to.eql("token 无效");
});
```

断言跑完在响应区的「Test Results」标签里能看到通过情况。

---

## 八、常见问题排查

**所有 `/user` 请求都返回 401。**
先确认 Headers 里有没有 `Authorization`，格式是不是 `Bearer ` 加空格再加 token。再确认环境变量 `token` 里是不是空的——没登录过，或者登录请求的 Tests 脚本没贴。

**登录成功但 `token` 变量还是空的。**
脚本标签页的名字必须是 **Tests**，不是 Pre-request Script。另外确认右侧选了环境，`pm.environment.set` 是往当前环境里写。

**应用启动就报 `Could not resolve placeholder 'JWT_SECRET'`。**
环境变量没配，或者配了但 IDEA 没完全重启。系统环境变量改完必须把 IDEA 彻底关掉再开。

**启动报 `WeakKeyException`。**
`JWT_SECRET` 的值太短，HS256 要求至少 32 个字符。

**注册报 `Table 'learning.tb_user' doesn't exist`。**
表还没建。注意表名是 `tb_user`，不是 `user`。

**登录一直提示"用户名或密码错误"，但密码肯定是对的。**
大概率是表里那条记录还是早期用 MD5 存的。BCrypt 的 `matches` 对 MD5 串只会返回 false，清掉这条记录重新注册即可。

---

## 九、测试时的已知缺口

这些不是 bug，是当前版本还没做的部分，遇到时不用当成故障排查：

**JSON 格式写错时会返回 500。** 比如请求体只写了 `{"username":` 就发出去，Spring 抛的 `HttpMessageNotReadableException` 目前没有被单独处理，会落到兜底分支返回 500。更合理的做法是单独接住它返回 400，可以后面补。

**`Content-Type` 不是 `application/json` 时也会返 500。** 同理，`HttpMediaTypeNotSupportedException` 没有单独处理。

**修改接口只能改昵称。** `UserUpdateDTO` 里只有 `id` 和 `nickname`。改密码得另开接口，用户名作为登录标识也不允许改。

**列表接口没有分页。** `selectList` 是全表查询，用户量上去之后需要改。

**兜底 500 的日志里有完整堆栈，返回给前端的只有一句通用提示。** 这是对的，排查问题时去看控制台，别指望响应体里能看出原因。
