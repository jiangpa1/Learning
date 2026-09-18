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
                .excludePathPatterns("/auth/**", "/error");

        registry.addInterceptor(authorizationInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/auth/**", "/error");

        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns("/error");
    }
}
