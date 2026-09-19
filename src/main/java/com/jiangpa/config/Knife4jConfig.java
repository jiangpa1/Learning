package com.jiangpa.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置（Knife4j / OpenAPI 3）。
 * <p>
 * 访问地址：{@code http://localhost:8081/doc.html}
 * <p>
 * 这里做三件事：声明文档信息、注册 bearer 类型的 SecurityScheme、以及**把该方案声明成全局安全要求**。
 * <p>
 * ⚠️ 三件事里 <b>第三件才是关键</b>：{@code addSecurityItem(...)} 是"把 Authorize 里填的值
 * **真正挂到请求上**"的开关。如果只注册 SecurityScheme 而不声明 SecurityRequirement，
 * 现象是：UI 上能填 token、也能保存成功，但**请求里根本不会带 Authorization 头**，
 * 于是调试受保护接口永远 401 —— 2026-09-19 实测踩过这个坑。
 * <p>
 * 代价是"全局都要求带 token"，所以 {@code /auth/**} 那几个接口在文档里也会显示要求鉴权。
 * <p>
 * 试过用 {@code @Operation(security = {})} 单独清掉它们，**实测在 springdoc 1.7.0 上无效**
 * （原始 JSON 里根本不会出现 {@code "security":[]}，springdoc 不会输出空数组）。
 * 这个残留**无害且可以不管**：
 * <ul>
 *   <li>{@code /auth/**} 在 {@code WebMvcConfig} 的拦截器放行名单里，多带一个 Authorization 头不影响</li>
 *   <li>其中 {@code /auth/logout} 本来就需要这个头（拿它去拉黑 token），标着"需要鉴权"反而准确</li>
 * </ul>
 * <p>
 * ⚠️ 另一处配套改动在 {@code WebMvcConfig}：文档页面和它要拉的 JSON 都由框架注册，
 * 请求时不可能带 JWT，所以必须加进拦截器的放行名单，否则打开 {@code /doc.html}
 * 只会得到一段 401 的 JSON。
 */
@Configuration
public class Knife4jConfig {

    /**
     * SecurityScheme 的名字，同时也是 SecurityRequirement 引用的 key。
     * <p>
     * ⚠️ **这里必须叫 {@code Authorization}，不能改成 bearerAuth 之类的名字。**
     * 原因（2026-09-19 实测踩坑）：
     * <ul>
     *   <li>{@code type: http, scheme: bearer} 按 OpenAPI 规范**不带 name 字段**，springdoc 会把它丢掉，
     *       所以 JSON 里这个 scheme 只有 type/scheme/bearerFormat</li>
     *   <li>Knife4j 的 UI 解析到 name 为空时，会**拿这个 key 当请求头名字**兜底
     *       （源码：{@code strBlank(i.name) && (c.name = r, c.in="header")}，r 就是 key）</li>
     *   <li>所以 key 叫 {@code bearerAuth} 的话，请求头会发成 {@code bearerAuth: Bearer xxx}，
     *       而 {@code JwtInterceptor} 找的是 {@code Authorization} → 拿不到头 → 返回「未登录，请先登录」</li>
     * </ul>
     * 把 key 直接命名成 {@code Authorization}，兜底就能拼出正确的头名，
     * **同时保留 {@code Bearer } 自动补全**（Knife4j 对 scheme=bearer 会幂等地补前缀）。
     * <p>
     * 它还被各 Controller 的 {@code @SecurityRequirement(name = Knife4jConfig.SECURITY_SCHEME_NAME)}
     * 引用 —— 用常量而不是硬编码字符串，就是为了避免"改了这边忘了那边"（这个区域已经因为名字对不上踩过两次）。
     */
    public static final String SECURITY_SCHEME_NAME = "Authorization";

    @Bean
    public OpenAPI learningOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Learning 博客系统 API")
                        .description("Spring Boot 2.7 + MyBatis-Plus + MySQL + Redis 的博客后端，共 22 个接口。\n\n"
                                + "响应约定：HTTP 状态码一律返回 200，业务状态放在响应体的 code 字段"
                                + "（200 / 400 / 401 / 403 / 404 / 429 / 500）。\n\n"
                                + "鉴权：先调 /auth/login 拿到 accessToken，再到【左侧菜单】的 Authorize 里填入"
                                + "（带不带 Bearer 前缀都可以，Knife4j 会自己补）。")
                        .version("1.0.0")
                        .contact(new Contact().name("jiangpa")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .in(SecurityScheme.In.HEADER)
                                .name("Authorization")))
                // ★ 这一行是"Authorize 能真正生效"的关键，删掉它调试就会 401（只见文档不见效果）
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME));
    }
}
