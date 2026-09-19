package com.jiangpa.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiangpa.annotation.RateLimit;
import com.jiangpa.utils.IpUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.method.HandlerMethod;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 限流拦截器单元测试。
 *
 * <p>锁住三件容易写错、且错法很隐蔽的事：
 * <ol>
 *   <li><b>fail-open</b>：Redis 挂了必须放行（限流是性能层，挂了不该把所有人挡在门外）——
 *       注意这里 catch 的必须是 {@code Exception}，写成 {@code IOException} 就抓不住
 *       {@code RedisConnectionFailureException}，fail-open 会变成 500</li>
 *   <li><b>未登录请求不能 NPE</b>：{@code request.getAttribute("userId")} 为 null 时，
 *       早前的写法 {@code .toString()} 会直接空指针 → 登录/注册接口全 500</li>
 *   <li><b>key 维度/路径拼接</b>：登录按 IP、业务按 userId，且必须带接口路径</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private DefaultRedisScript<List<?>> rateLimitScript;

    @Mock
    private IpUtils ipUtils;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    private RateLimitInterceptor interceptor;
    private StringWriter responseBody;

    @BeforeEach
    void setUp() throws Exception {
        interceptor = new RateLimitInterceptor(stringRedisTemplate, rateLimitScript, new ObjectMapper(), ipUtils);

        // 让 writeOverLimit 能往 response 写内容
        responseBody = new StringWriter();
        lenient().when(response.getWriter()).thenReturn(new PrintWriter(responseBody));
    }

    /** 按这个 stub 的 handler 当作「标了 @RateLimit 的接口」 */
    static class FakeController {
        @RateLimit(limit = 5, window = 60_000)
        public void limited() {
        }

        public void notLimited() {
        }
    }

    private HandlerMethod limitedHandler() throws NoSuchMethodException {
        return new HandlerMethod(new FakeController(), FakeController.class.getMethod("limited"));
    }

    private HandlerMethod unlimitedHandler() throws NoSuchMethodException {
        return new HandlerMethod(new FakeController(), FakeController.class.getMethod("notLimited"));
    }

    /**
     * 脚本返回 {1, 剩余, 重置时间} = 放行。
     *
     * <p>这里用 {@code doReturn().when()} 而不是 {@code when().thenReturn()}：
     * {@code execute()} 返回 {@code List<?>}，用后者会触发「通配符捕获」编译错误
     * （{@code List<CAP#1>} 无法转为 {@code List<CAP#2>}）。两种写法运行时完全等价。
     */
    private void stubAllow(long remaining) {
        List<?> scriptResult = List.of(1L, remaining, System.currentTimeMillis() + 60_000);
        doReturn(scriptResult).when(stringRedisTemplate)
                .execute(eq(rateLimitScript), anyList(), any(), any(), any());
    }

    /** 脚本返回 {0, 0, 重置时间} = 超限 */
    private void stubReject() {
        List<?> scriptResult = List.of(0L, 0L, System.currentTimeMillis() + 60_000);
        doReturn(scriptResult).when(stringRedisTemplate)
                .execute(eq(rateLimitScript), anyList(), any(), any(), any());
    }

    // ==================== 基本路径 ====================

    @Test
    @DisplayName("没标 @RateLimit 的接口直接放行，不碰 Redis")
    void methodWithoutAnnotationIsPassedThrough() throws Exception {
        boolean allowed = interceptor.preHandle(request, response, unlimitedHandler());

        assertTrue(allowed);
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("handler 不是 HandlerMethod（静态资源等）直接放行，不能强转抛 ClassCastException")
    void nonHandlerMethodIsPassedThrough() throws Exception {
        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    @DisplayName("未超限 → 放行，并带上 X-RateLimit-* 头（前端要能提前感知剩余额度）")
    void allowedRequestGetsRateLimitHeaders() throws Exception {
        when(request.getAttribute("userId")).thenReturn(18L);
        when(request.getRequestURI()).thenReturn("/article/3");
        stubAllow(4L);

        assertTrue(interceptor.preHandle(request, response, limitedHandler()));

        verify(response).setHeader("X-RateLimit-Limit", "5");
        verify(response).setHeader("X-RateLimit-Remaining", "4");
    }

    // ==================== 超限 ====================

    @Test
    @DisplayName("★ 超限 → 拦截并返回 code=429（HTTP 仍 200，看 body 的 code）")
    void overLimitReturns429InBody() throws Exception {
        when(request.getAttribute("userId")).thenReturn(18L);
        when(request.getRequestURI()).thenReturn("/article/3");
        stubReject();

        boolean allowed = interceptor.preHandle(request, response, limitedHandler());

        assertFalse(allowed, "超限必须拦截");
        String body = responseBody.toString();
        assertTrue(body.contains("\"code\":429"), "业务码必须是 429，实际响应体：" + body);
        assertFalse(body.contains("\"code\":401"),
                "绝不能用 401 —— 前端会把限流误判成'凭证失效'而跳登录页");
        assertFalse(body.contains("\"code\":403"));
        verify(response).setHeader(eq("Retry-After"), anyString());
    }

    @Test
    @DisplayName("★ 超限时 HTTP 状态码仍是 200（项目铁律：状态码恒 200，业务码在 body）")
    void overLimitKeepsHttp200() throws Exception {
        when(request.getAttribute("userId")).thenReturn(18L);
        when(request.getRequestURI()).thenReturn("/article/3");
        stubReject();

        interceptor.preHandle(request, response, limitedHandler());

        verify(response).setStatus(HttpServletResponse.SC_OK);
    }

    // ==================== 降级：fail-open ====================

    @Test
    @DisplayName("★ Redis 挂了 → fail-open 放行（catch 必须是 Exception，写成 IOException 就抓不住）")
    void redisDownFailsOpen() throws Exception {
        when(request.getAttribute("userId")).thenReturn(18L);
        when(request.getRequestURI()).thenReturn("/article/3");
        when(stringRedisTemplate.execute(eq(rateLimitScript), anyList(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));

        boolean allowed = interceptor.preHandle(request, response, limitedHandler());

        assertTrue(allowed,
                "限流是性能层：Redis 挂了应该放行。若抛异常 → 全局异常处理器返 500 → 限流反而把全站打挂");
    }

    @Test
    @DisplayName("★ 脚本返回空/null → 也走 fail-open，不能 NPE")
    void emptyScriptResultFailsOpen() throws Exception {
        when(request.getAttribute("userId")).thenReturn(18L);
        when(request.getRequestURI()).thenReturn("/article/3");
        doReturn(null).when(stringRedisTemplate)
                .execute(eq(rateLimitScript), anyList(), any(), any(), any());

        assertTrue(interceptor.preHandle(request, response, limitedHandler()),
                "脚本返回 null 时若直接 res.get(0) 会 NPE");

        doReturn(List.of()).when(stringRedisTemplate)
                .execute(eq(rateLimitScript), anyList(), any(), any(), any());
        assertTrue(interceptor.preHandle(request, response, limitedHandler()),
                "返回空列表也不能 NPE");
    }

    // ==================== 未登录：userId 为 null（登录/注册接口走这条路） ====================

    @Test
    @DisplayName("★ 未登录请求（userId 为 null）不能 NPE，要走 IP 维度")
    void anonymousRequestUsesIpDimension() throws Exception {
        when(request.getAttribute("userId")).thenReturn(null);   // ← 登录/注册接口的真实情况
        when(request.getRequestURI()).thenReturn("/auth/login");
        when(ipUtils.getClientIp(request)).thenReturn("127.0.0.1");
        stubAllow(9L);

        boolean allowed = assertDoesNotThrow(
                () -> interceptor.preHandle(request, response, limitedHandler()),
                "userId 为 null 时若直接 .toString() 会 NPE → 登录接口 500（这是真实发生过的缺陷）");

        assertTrue(allowed);
        verify(ipUtils).getClientIp(request);   // 确认真的回退到 IP 维度
    }

    // ==================== key 维度 ====================

    @Test
    @DisplayName("★ 已登录按 userId 维度：key 含 user:<userId>，不含 IP")
    void loggedInUsesUserIdDimension() throws Exception {
        when(request.getAttribute("userId")).thenReturn(18L);
        when(request.getRequestURI()).thenReturn("/user/list");
        stubAllow(29L);

        interceptor.preHandle(request, response, limitedHandler());

        verify(stringRedisTemplate).execute(eq(rateLimitScript),
                argThat((List<String> keys) -> keys.size() == 1
                        && keys.get(0).equals("learning:limit:user:18:/user/list")),
                any(), any(), any());
        verify(ipUtils, never()).getClientIp(any());
    }

    @Test
    @DisplayName("★ 未登录按 IP 维度：key 含 ip:<addr>")
    void anonymousUsesIpDimensionInKey() throws Exception {
        when(request.getAttribute("userId")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/auth/login");
        when(ipUtils.getClientIp(request)).thenReturn("192.168.1.7");
        stubAllow(9L);

        interceptor.preHandle(request, response, limitedHandler());

        verify(stringRedisTemplate).execute(eq(rateLimitScript),
                argThat((List<String> keys) -> keys.size() == 1
                        && keys.get(0).equals("learning:limit:ip:192.168.1.7:/auth/login")),
                any(), any(), any());
    }

    @Test
    @DisplayName("★ key 必须带接口路径：不同接口不能共用计数器")
    void keyIncludesPath() throws Exception {
        when(request.getAttribute("userId")).thenReturn(18L);
        when(request.getRequestURI()).thenReturn("/article/1");
        stubAllow(4L);
        interceptor.preHandle(request, response, limitedHandler());

        when(request.getRequestURI()).thenReturn("/article/2");
        interceptor.preHandle(request, response, limitedHandler());

        // 两次调用的 key 必须不同
        verify(stringRedisTemplate).execute(eq(rateLimitScript),
                argThat((List<String> k) -> k.get(0).endsWith("/article/1")), any(), any(), any());
        verify(stringRedisTemplate).execute(eq(rateLimitScript),
                argThat((List<String> k) -> k.get(0).endsWith("/article/2")), any(), any(), any());
    }

    @Test
    @DisplayName("阈值与窗口来自注解，不是写死的（改注解即改阈值）")
    void limitAndWindowComeFromAnnotation() throws Exception {
        when(request.getAttribute("userId")).thenReturn(18L);
        when(request.getRequestURI()).thenReturn("/x");
        stubAllow(4L);

        interceptor.preHandle(request, response, limitedHandler());

        verify(stringRedisTemplate).execute(eq(rateLimitScript), anyList(),
                any(), eq("5"), eq("60000"));
    }
}
