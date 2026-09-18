package com.jiangpa.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiangpa.annotation.RateLimit;
import com.jiangpa.common.CacheKeys;
import com.jiangpa.common.Result;
import com.jiangpa.utils.IpUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;


@Component
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<List<?>> rateLimitScript;
    private final ObjectMapper objectMapper;
    private final IpUtils ipUtils;

    public RateLimitInterceptor(StringRedisTemplate stringRedisTemplate, DefaultRedisScript<List<?>> rateLimitScript, ObjectMapper objectMapper, IpUtils ipUtils) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimitScript = rateLimitScript;
        this.objectMapper = objectMapper;
        this.ipUtils = ipUtils;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception{
        if (!(handler instanceof HandlerMethod handlerMethod)) return true;

        if (!handlerMethod.hasMethodAnnotation(RateLimit.class)) return true;

        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);

        Object uidAttr = request.getAttribute("userId");
        String uri = request.getRequestURI();
        String key;
        if (uidAttr != null) {
            key = CacheKeys.rateLimit("user", String.valueOf(uidAttr), uri);
        } else {
            key = CacheKeys.rateLimit("ip", ipUtils.getClientIp(request), uri);
        }

        List<String> keys = Collections.singletonList(key);

        long now = System.currentTimeMillis();

        List<?> res;
        try {
            res = stringRedisTemplate.execute(rateLimitScript, keys,
                    String.valueOf(now), String.valueOf(rateLimit.limit()), String.valueOf(rateLimit.window()));

            // 脚本没返回结果（理论上不该发生）→ 按 fail-open 放行，不要 NPE
            if (res == null || res.isEmpty()) {
                log.warn("限流脚本返回空结果，放行本次请求，key={}", key);
                return true;
            }

            if (((Number) res.get(0)).intValue() == 0) {
                writeOverLimit(response, rateLimit.limit(),
                        ((Number) res.get(1)).longValue(), ((Number) res.get(2)).longValue());
                return false;
            }
        } catch (Exception e) {
            log.warn("限流检查失败（Redis 不可用），放行本次请求", e);
            return true;
        }

        writeHead(response, rateLimit.limit(),
                ((Number) res.get(1)).longValue(), ((Number) res.get(2)).longValue());

        return true;
    }

    private void writeOverLimit(HttpServletResponse response, Long limit,
                                Long remaining, Long reset) throws IOException {
        long retryAfter = Math.max(0, (reset - System.currentTimeMillis() + 999) / 1000);

        writeHead(response, limit, remaining, reset);
        // HTTP 状态码设为 200
        response.setStatus(HttpServletResponse.SC_OK);

        // 必须带 charset=UTF-8，否则中文提示到前端会变乱码
        response.setContentType("application/json;charset=UTF-8");

        // 用注入的 ObjectMapper 序列化，不要手拼 JSON 字符串——字段名或转义出问题很难查
        response.getWriter().write(objectMapper.writeValueAsString(Result.overLimit("操作太频繁，请等待" + retryAfter + "秒")));
    }

    private void writeHead(HttpServletResponse response, Long limit, Long remaining, Long reset){
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        response.setHeader("X-RateLimit-Reset", String.valueOf(reset / 1000));
        long retryAfter = Math.max(0, (reset - System.currentTimeMillis() + 999) / 1000);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
    }

}
