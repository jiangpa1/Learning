package com.jiangpa.config;

import com.jiangpa.interceptor.AuthorizationInterceptor;
import com.jiangpa.interceptor.JwtInterceptor;
import com.jiangpa.interceptor.RateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置类。
 * 拦截器光写出来不会自动生效，必须在这里注册进 InterceptorRegistry。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;
    private final AuthorizationInterceptor authorizationInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;

    /**
     * 走构造器注入拿拦截器实例，不要在这里 new JwtInterceptor()。
     * 手动 new 出来的对象里，JwtUtils 和 ObjectMapper 都是 null，一用就空指针。
     */
    public WebMvcConfig(JwtInterceptor jwtInterceptor, AuthorizationInterceptor authorizationInterceptor, RateLimitInterceptor rateLimitInterceptor) {
        this.jwtInterceptor = jwtInterceptor;
        this.authorizationInterceptor = authorizationInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    /**
     * 接口文档（Knife4j / springdoc）相关的路径。
     * <p>
     * 这些 handler 都由框架自动注册，请求它们时**不可能带上 JWT**，
     * 所以必须绕开认证与授权拦截器 —— 否则打开 /doc.html 只会得到一段 401 的 JSON。
     */
    private static final String[] DOC_PATHS = {
            "/doc.html",        // Knife4j 的文档页面
            "/webjars/**",      // 该页面依赖的静态资源
            "/v3/api-docs/**",  // 页面要拉取的 OpenAPI 3 JSON
            "/swagger-ui/**",   // springdoc 自带的 UI（保留，便于对比两种 UI）
            "/swagger-ui.html"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)

                // 默认拦截所有请求
                .addPathPatterns("/**")

                // 放行名单，两个都不能少：
                //   /auth/** —— 注册和登录接口本身就是为了拿 token，
                //               要求它们先带 token 会形成死锁，永远登不进去
                //   /error   —— 请求出错时 Spring Boot 会内部转发到这里做统一错误处理，
                //               不放行的话这次转发又会被拦一次，真实错误会被 401 盖住
                //   DOC_PATHS —— 接口文档页面及其静态资源、JSON，见上面的注释
                .excludePathPatterns("/auth/**", "/error")
                .excludePathPatterns(DOC_PATHS);

        registry.addInterceptor(authorizationInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**", "/error")
                .excludePathPatterns(DOC_PATHS);

        // ⚠️ 限流拦截器**不需要**放行文档路径：它是注解驱动的，
        //    只有方法上标了 @RateLimit 才生效，文档相关的 handler 没有这个注解，
        //    走到 preHandle 里直接 return true 放行 —— 这也是它和上面两个的本质区别。
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/error");
    }
}
