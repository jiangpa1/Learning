# JWT 鉴权拦截器设计文档

> 项目：Learning（Spring Boot 2.7.18 + MyBatis-Plus 3.5.5）
> 相关类：`JwtUtils`、`JwtProperties`、`JwtInterceptor`、`WebMvcConfig`

---

## 一、职责边界

拦截器只做三件事：**取 token → 验 token → 把用户身份传给后面的代码**。

明确不做的：

- 不查数据库确认用户是否还存在（那属于业务校验，放 Service）
- 不判断权限级别／角色（等有权限系统了再说，那是另一个拦截器的事）

保持职责单一，后面加新功能时才不会互相牵扯。

---

## 二、请求头约定

客户端在每个需要鉴权的请求里带上：

```
Authorization: Bearer <token>
```

服务端解析时要注意两点。一是 `Bearer` 和 token 之间是**一个空格**，拼接格式固定是 `"Bearer " + token`。二是 `Bearer` 是 HTTP 认证方案名，按规范**大小写不敏感**，稳妥做法是忽略大小写去判断前缀，而不是直接 `startsWith("Bearer ")` 完全匹配。

取到之后长度要校验，剥掉前缀后如果剩下空串，同样按格式错误处理。

---

## 三、拦截与放行范围

| 路径 | 是否拦截 | 说明 |
| --- | --- | --- |
| `/auth/**` | 放行 | 注册、登录，这两个接口本身就是为了拿 token，不可能要求先有 token |
| `/error` | 放行 | Spring Boot 内部的错误转发路径 |
| 其他全部 | 拦截 | `addPathPatterns("/**")` 覆盖 |

**为什么认证接口要单独放 `/auth` 前缀？** 因为拦截器的路径匹配**不区分 HTTP 方法**。你原来注册是 `POST /user`、修改是 `PUT /user`，路径完全相同。如果为了放行注册而排除 `/user`，那么 `PUT /user`（修改用户）会一起被放行，未登录的人就能改数据——这是个实打实的漏洞。把认证接口挪到 `/auth/**` 之后，只需要排除一个前缀，干净且不会误伤。

**`/error` 为什么必须排除？** 请求出错时 Spring Boot 会把请求内部转发到 `/error` 走统一错误处理。如果不排除，这次转发又会被拦截器拦下、返回 401，结果就是你排查的真实错误被一个莫名其妙的"未登录"盖住了。

---

## 四、失败响应格式

所有鉴权失败统一返回 HTTP 状态码 **401**，响应体沿用 `Result` 结构：

```json
{
  "code": 401,
  "message": "未登录或登录已过期",
  "data": null
}
```

当前 `Result` 里有 200／400／404／500，**需要补一个 401**，建议加静态方法 `unauthorized(String message)`。

各类失败场景对应的提示：

| 场景 | 触发条件 | message |
| --- | --- | --- |
| 没带 token | 请求头为 `null` 或为空 | 未登录，请先登录 |
| 格式不对 | 前缀不是 `Bearer`，或剥离后为空 | token 格式错误 |
| token 过期 | 抛出 `ExpiredJwtException` | 登录已过期，请重新登录 |
| 签名不符 | 抛出 `SignatureException` | token 无效 |
| 格式损坏 | 抛出 `MalformedJwtException` | token 无效 |
| 其他解析异常 | 抛出 `JwtException` | token 无效 |

对应的 import 路径（jjwt 0.11.5）：

```java
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.SignatureException;   // 注意在 security 子包里
```

`SignatureException` 有个容易踩的点：`io.jsonwebtoken` 包下也有一个同名的 `SignatureException`，但已被标记废弃。要 catch 的是 **`io.jsonwebtoken.security.SignatureException`**，两者都继承自 `JwtException`，写错了编译能过但捕获不到该捕获的异常。IDE 自动导入时留意一下包名。

最后一条兜底很重要。区分过期和无效是有意义的：前端收到"已过期"可以自动跳登录页，收到"无效"则提示用户重新登录，体验不同。

---

## 五、成功时如何传递身份

校验通过后，把用户信息挂到当前请求上：

```
request.setAttribute("userId", ...);
request.setAttribute("username", ...);
```

Controller 里直接取：

```java
@GetMapping("/{id}")
public Result<?> selectUser(@RequestAttribute("userId") Long userId) { ... }
```

这样业务代码不用再信任前端传过来的 id，避免了"改个 URL 就能查别人数据"的问题。

如果后续很多地方都要用到当前用户，可以再包一层 `ThreadLocal` 存上下文，在 `afterCompletion` 里清理。现阶段用 `request` attribute 就够。

---

## 六、实现要点

### 6.1 拦截器类结构

新建 `com.jiangpa.interceptor.JwtInterceptor`，实现 `HandlerInterceptor`，加 `@Component` 交给 Spring 管理。依赖通过**构造器注入** `JwtUtils` 和 `ObjectMapper`，跟 `JwtUtils` 现在的写法保持一致。

**import 是 `javax.servlet.*`，不是 `jakarta.servlet.*`。** 这是本节最容易踩的坑——`jakarta` 是 Spring Boot 3 才换的包名，网上大量教程面向 Boot 3，直接抄会一片飘红。你的项目是 2.7，对应的是：

```java
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
```

核心是重写 `preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)`。

### 6.2 preHandle 的执行步骤

```
1. 从 request 读 Authorization 请求头
2. 判断是否为空 → 空则拦截
3. 剥离 "Bearer " 前缀（忽略大小写），判断剩余部分是否为空 → 空则拦截
4. try:
       Claims claims = jwtUtils.parseToken(token)
       从 claims 取出 id 和 username
       request.setAttribute("userId", ...)
       request.setAttribute("username", ...)
       return true          // 放行
   catch:
       按第四节的表格，分异常类型给出不同 message
       返回 false           // 拦截
```

### 6.3 最关键的坑：返回 false 之后必须自己写响应

`preHandle` 返回 `false` 只是告诉 Spring"别往下走了"，它**不会**帮你生成任何响应内容。所以如果你只是 `return false`，前端收到的会是一个**内容为空的 HTTP 200**，既不是错误码也不是错误信息，排查起来非常费劲。

拦截时必须手动往 `response` 里写：

```java
private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);      // 401
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write(objectMapper.writeValueAsString(Result.unauthorized(message)));
}
```

三点注意：`setStatus` 要和响应体里的 `code` 保持一致，都是 401；`setContentType` 必须带 `charset=UTF-8`，否则中文提示会变成乱码；用注入的 `ObjectMapper` 序列化，不要手拼 JSON 字符串。

**另一种更干净的写法**是抛一个自定义异常（比如 `UnauthorizedException`），交给 `@RestControllerAdvice` 全局异常处理器统一转成 401 响应。好处是响应格式只有一处定义，不会跟其他接口的异常处理走两套逻辑。但你的全局异常处理器还没建，所以先用上面直接写 `response` 的方式，等处理器建好了再重构成抛异常。

### 6.4 注册拦截器

新建 `com.jiangpa.config.WebMvcConfig`，实现 `WebMvcConfigurer`，重写 `addInterceptors(InterceptorRegistry registry)`，把拦截器对象注入进来后注册：

```java
registry.addInterceptor(jwtInterceptor)
        .addPathPatterns("/**")
        .excludePathPatterns("/auth/**", "/error");
```

不要直接 `new JwtInterceptor()`，走 Spring 注入才拿得到已经装配好的 `JwtUtils` 和 `ObjectMapper`。

---

## 七、踩坑清单

按踩到的概率排序：

1. **import 写成了 `jakarta.servlet`** —— 项目是 Boot 2.7，必须用 `javax.servlet`
2. **`preHandle` 返回 false 但没写 response** —— 前端收到空 200，最难查的一个
3. **路径匹配不区分 HTTP 方法** —— 所以认证接口要独立成 `/auth` 前缀，不能靠排除 `/user` 来放行注册
4. **忘记排除 `/error`** —— 真实错误会被 401 掩盖
5. **忘记排除登录／注册接口** —— 登录时被要求先登录，死锁
6. **`JWT_SECRET` 环境变量没生效** —— 系统环境变量改完必须完全重启 IDEA
7. **密钥长度不足** —— HS256 要求至少 256 位、即 32 字节，短了抛 `WeakKeyException`
8. **响应体里的 code 和 HTTP 状态码不一致** —— 一个 401 一个 200，前端不知道该信哪个

---

## 八、自测方法

用 IDEA 内置的 HTTP Client 或 Postman，按顺序验证四种情况：

1. 不带 `Authorization` 请求 `GET /user/1` → 期望 401，响应体是 `Result` 结构
2. 带一个随便编的 token → 期望 401，提示 token 无效
3. 先调 `POST /auth/login` 拿到真 token → 带上请求 `GET /user/1` → 期望 200，正常返回
4. 把 token 最后几个字符改掉再请求 → 期望 401，验证签名校验确实生效

第 4 步很多人会跳过，但它才是真正验证"签名有没有被校验"的一步——如果改动后仍然返回 200，说明你的解析逻辑漏了签名验证。

---

## 九、这轮需要改动的清单

| 类型 | 内容 |
| --- | --- |
| 新增 | `JwtInterceptor`（`com.jiangpa.interceptor`） |
| 新增 | `WebMvcConfig`（`com.jiangpa.config`） |
| 新增 | `AuthController`（`com.jiangpa.controller`），提供 `/auth/register`、`/auth/login` |
| 新增 | `LoginDTO`（`com.jiangpa.dto`） |
| 修改 | `Result` 增加 `unauthorized(String)` |
| 修改 | `UserController` 中的注册方法迁到 `AuthController`，路径改为 `/auth/register` |
| 修改 | `UserService` 增加 `login(LoginDTO)` 方法 |
| 同步 | `用户模块接口文档.md` 里的注册路径要跟着改成 `/auth/register` |

登录接口的职责：按用户名查用户 → 用 BCrypt 的 `matches(明文, 密文)` 校验密码（**不能用 `equals`**，BCrypt 每次加密结果都不同）→ 调 `jwtUtils.generateToken` 签发 token 返回。
