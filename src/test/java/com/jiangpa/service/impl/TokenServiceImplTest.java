package com.jiangpa.service.impl;

import com.jiangpa.common.CacheKeys;
import com.jiangpa.exception.BusinessException;
import com.jiangpa.properties.JwtProperties;
import com.jiangpa.utils.JwtUtils;
import com.jiangpa.vo.TokenPair;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TokenService 单元测试 —— <b>本测试的核心价值是锁住「降级方向」</b>。
 *
 * <p>为什么这些用例非写不可：Redis 挂掉时接口该返什么码，靠手工测试要「停 Redis 再跑一遍」，
 * 很难每次都验；而且四种场景的方向是<b>故意相反</b>的，非常容易改错：
 *
 * <table border="1">
 *   <tr><th>方法</th><th>Redis 操作</th><th>方向</th><th>期望</th></tr>
 *   <tr><td>issue</td><td>写 refresh key</td><td>fail-closed</td><td>503（签不出凭证不该给假成功）</td></tr>
 *   <tr><td>refresh</td><td>读 refresh key</td><td>fail-closed</td><td>503</td></tr>
 *   <tr><td>isRevoked</td><td>读黑名单</td><td>fail-closed</td><td>503（查不到 ≠ 没被拉黑）</td></tr>
 *   <tr><td>logout</td><td>删 refresh key</td><td><b>fail-open</b></td><td>不抛异常，登出照常成功</td></tr>
 *   <tr><td>logout</td><td>写黑名单</td><td>fail-closed</td><td>503（写失败 = token 没作废）</td></tr>
 * </table>
 */
@ExtendWith(MockitoExtension.class)
class TokenServiceImplTest {

    private static final Long USER_ID = 17L;
    private static final String USERNAME = "lisi";
    private static final int ROLE = 1;

    @Mock
    private JwtUtils jwtUtils;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private JwtProperties jwtProperties;
    private TokenServiceImpl tokenService;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties();
        jwtProperties.setAccessExpiration(Duration.ofMinutes(30));
        jwtProperties.setRefreshExpiration(Duration.ofDays(7));

        tokenService = new TokenServiceImpl(jwtUtils, stringRedisTemplate, jwtProperties);
    }

    /** 造一个"看起来合法"的 Claims（只 stub 用到的方法） */
    private Claims claimsOf(String type, Integer role) {
        Claims claims = mock(Claims.class);
        lenient().when(claims.get("role", Number.class)).thenReturn(role);
        lenient().when(claims.get("username", String.class)).thenReturn(USERNAME);
        lenient().when(jwtUtils.isAccessToken(claims)).thenReturn("access".equals(type));
        lenient().when(jwtUtils.isRefreshToken(claims)).thenReturn("refresh".equals(type));
        lenient().when(jwtUtils.getUserId(claims)).thenReturn(USER_ID);
        return claims;
    }

    // ==================== issue：登录签发 ====================

    @Test
    @DisplayName("issue 正常路径：签发双 token、返回 expiresIn、把 refresh 哈希写进 Redis")
    void issueWritesRefreshHashToRedis() {
        when(jwtUtils.generateAccessToken(USER_ID, USERNAME, ROLE)).thenReturn("ACCESS");
        when(jwtUtils.generateRefreshToken(USER_ID, USERNAME, ROLE)).thenReturn("REFRESH");
        when(jwtUtils.hashToken("REFRESH")).thenReturn("HASH");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        TokenPair pair = tokenService.issue(USER_ID, USERNAME, ROLE);

        assertEquals("ACCESS", pair.getAccessToken());
        assertEquals("REFRESH", pair.getRefreshToken());
        assertEquals(Duration.ofMinutes(30).toMillis(), pair.getExpiresIn(),
                "expiresIn 要返回 access 的有效毫秒数，前端据此提前刷新");

        // key 用 userId（天然单端登录）、value 存哈希（不存明文凭证）、TTL = refresh 有效期（滑动窗口）
        verify(valueOperations).set(
                eq(CacheKeys.tokenRefresh(USER_ID)),
                eq("HASH"),
                eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("★ issue 的 Redis 挂了 → 503（fail-closed），不能返 500 也不能假装成功")
    void issueFailsClosedWhenRedisDown() {
        when(jwtUtils.generateAccessToken(anyLong(), anyString(), anyInt())).thenReturn("ACCESS");
        when(jwtUtils.generateRefreshToken(anyLong(), anyString(), anyInt())).thenReturn("REFRESH");
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("connection refused"))
                .when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> tokenService.issue(USER_ID, USERNAME, ROLE));

        assertEquals(503, ex.getCode(),
                "签发不出 refresh 凭证就不该给用户假的成功；返 500 会让前端以为是代码 bug");
    }

    // ==================== refresh：续期轮转 ====================

    @Test
    @DisplayName("refresh 正常路径：校验通过后签发新的一对 token（轮转）")
    void refreshRotatesTokens() {
        Claims claims = claimsOf("refresh", ROLE);
        when(jwtUtils.parseToken("R1")).thenReturn(claims);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CacheKeys.tokenRefresh(USER_ID))).thenReturn("HASH1");
        when(jwtUtils.hashToken("R1")).thenReturn("HASH1");

        when(jwtUtils.generateAccessToken(USER_ID, USERNAME, ROLE)).thenReturn("A2");
        when(jwtUtils.generateRefreshToken(USER_ID, USERNAME, ROLE)).thenReturn("R2");
        when(jwtUtils.hashToken("R2")).thenReturn("HASH2");

        TokenPair pair = tokenService.refresh("R1");

        assertEquals("A2", pair.getAccessToken());
        assertEquals("R2", pair.getRefreshToken());
        verify(valueOperations).set(eq(CacheKeys.tokenRefresh(USER_ID)), eq("HASH2"), eq(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("★ refresh 的 Redis 挂了 → 503（fail-closed）")
    void refreshFailsClosedWhenRedisDown() {
        Claims claims = claimsOf("refresh", ROLE);
        when(jwtUtils.parseToken("R1")).thenReturn(claims);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        BusinessException ex = assertThrows(BusinessException.class, () -> tokenService.refresh("R1"));
        assertEquals(503, ex.getCode());
    }

    @Test
    @DisplayName("★ 过期 refreshToken → 401 而不是 500（这是那个真实缺陷的回归测试）")
    void expiredRefreshTokenReturns401() {
        ExpiredJwtException expired = new ExpiredJwtException(null, claimsOf("refresh", ROLE), "expired");
        when(jwtUtils.parseToken("EXPIRED")).thenThrow(expired);

        BusinessException ex = assertThrows(BusinessException.class, () -> tokenService.refresh("EXPIRED"));
        assertEquals(401, ex.getCode(), "过期是'凭证问题'，必须 401（前端据此跳登录页）；返 500 会打断自动续期链路");
    }

    @Test
    @DisplayName("★ 结构损坏的 refreshToken → 401 而不是 500")
    void malformedRefreshTokenReturns401() {
        when(jwtUtils.parseToken("garbage")).thenThrow(new MalformedJwtException("bad token"));

        BusinessException ex = assertThrows(BusinessException.class, () -> tokenService.refresh("garbage"));
        assertEquals(401, ex.getCode());
    }

    @Test
    @DisplayName("★ 传 accessToken 来续期 → 401「token类型错误」（漏 type 校验 = refresh 变万能通行证）")
    void accessTokenRejectedAtRefresh() {
        Claims accessClaims = claimsOf("access", ROLE);
        when(jwtUtils.parseToken("ACCESS")).thenReturn(accessClaims);

        BusinessException ex = assertThrows(BusinessException.class, () -> tokenService.refresh("ACCESS"));
        assertEquals(401, ex.getCode());
        assertTrue(ex.getMessage().contains("类型"), "应明确提示类型错误，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("★ 旧 refreshToken 复用（已被轮转/登出/顶号）→ 401")
    void reusedRefreshTokenRejected() {
        Claims claims = claimsOf("refresh", ROLE);
        when(jwtUtils.parseToken("R1")).thenReturn(claims);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(CacheKeys.tokenRefresh(USER_ID))).thenReturn("HASH_OF_R2");  // Redis 里已是新值
        when(jwtUtils.hashToken("R1")).thenReturn("HASH_OF_R1");

        BusinessException ex = assertThrows(BusinessException.class, () -> tokenService.refresh("R1"));
        assertEquals(401, ex.getCode(), "哈希对不上必须是 401（且服务端无法区分'轮转/顶号/被盗'，所以只拒绝、不吊销全部会话）");
    }

    // ==================== isRevoked：黑名单（拦截器每个请求都查） ====================

    @Test
    @DisplayName("isRevoked：黑名单里有 → true（登出后 access 立即失效）")
    void isRevokedTrueWhenPresent() {
        when(jwtUtils.hashToken("ACCESS")).thenReturn("HASH");
        when(stringRedisTemplate.hasKey(CacheKeys.tokenBlacklist("HASH"))).thenReturn(true);

        assertTrue(tokenService.isRevoked("ACCESS"));
    }

    @Test
    @DisplayName("isRevoked：黑名单里没有 → false（放行）")
    void isRevokedFalseWhenAbsent() {
        when(jwtUtils.hashToken("ACCESS")).thenReturn("HASH");
        when(stringRedisTemplate.hasKey(CacheKeys.tokenBlacklist("HASH"))).thenReturn(false);

        assertFalse(tokenService.isRevoked("ACCESS"));
    }

    @Test
    @DisplayName("★ isRevoked 的 Redis 挂了 → 503（fail-closed），绝不能 return false")
    void isRevokedFailsClosedWhenRedisDown() {
        when(jwtUtils.hashToken("ACCESS")).thenReturn("HASH");
        when(stringRedisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        BusinessException ex = assertThrows(BusinessException.class, () -> tokenService.isRevoked("ACCESS"));
        assertEquals(503, ex.getCode(),
                "查不到黑名单 ≠ token 没被拉黑。返回 false 会让已登出的 token 集体复活");
    }

    @Test
    @DisplayName("★ isRevoked 必须用 503 而不是 401（用 401 会让 Redis 抖动时用户被误踢到登录页）")
    void isRevokedUses503Not401() {
        when(jwtUtils.hashToken("ACCESS")).thenReturn("HASH");
        when(stringRedisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        BusinessException ex = assertThrows(BusinessException.class, () -> tokenService.isRevoked("ACCESS"));
        assertNotEquals(401, ex.getCode(), "401 会让前端清 token 跳登录页；503 才是'服务端暂时不可用'");
        assertEquals(503, ex.getCode());
    }

    // ==================== logout：登出 ====================

    @Test
    @DisplayName("logout 正常路径：删 refresh key + 把 access 写进黑名单（TTL = 剩余有效期）")
    void logoutDeletesRefreshAndBlacklistsAccess() {
        Claims claims = claimsOf("access", ROLE);
        when(jwtUtils.parseToken("ACCESS")).thenReturn(claims);
        when(jwtUtils.hashToken("ACCESS")).thenReturn("HASH");
        when(jwtUtils.getRemainingMillis(claims)).thenReturn(1_000_000L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        tokenService.logout("Bearer ACCESS");

        verify(stringRedisTemplate).delete(CacheKeys.tokenRefresh(USER_ID));
        verify(valueOperations).set(
                eq(CacheKeys.tokenBlacklist("HASH")), eq("1"), eq(1_000_000L), eq(TimeUnit.MILLISECONDS));
    }

    @Test
    @DisplayName("★ logout 删 refresh key 失败 → fail-open（登出照常成功，不能因为 Redis 抖动就报错）")
    void logoutIsFailOpenWhenDeletingRefreshKeyFails() {
        Claims claims = claimsOf("access", ROLE);
        when(jwtUtils.parseToken("ACCESS")).thenReturn(claims);
        when(stringRedisTemplate.delete(CacheKeys.tokenRefresh(USER_ID)))
                .thenThrow(new RedisConnectionFailureException("down"));
        when(jwtUtils.hashToken("ACCESS")).thenReturn("HASH");
        when(jwtUtils.getRemainingMillis(claims)).thenReturn(1_000_000L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);

        assertDoesNotThrow(() -> tokenService.logout("Bearer ACCESS"),
                "用户点了退出却看到 500，比'服务端还没清干净'更糟。删 key 失败只记日志");
    }

    @Test
    @DisplayName("★ logout 写黑名单失败 → 503（fail-closed：access 没被作废，不能声称登出成功）")
    void logoutFailsClosedWhenBlacklistWriteFails() {
        Claims claims = claimsOf("access", ROLE);
        when(jwtUtils.parseToken("ACCESS")).thenReturn(claims);
        when(jwtUtils.hashToken("ACCESS")).thenReturn("HASH");
        when(jwtUtils.getRemainingMillis(claims)).thenReturn(1_000_000L);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        doThrow(new RedisConnectionFailureException("down"))
                .when(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        BusinessException ex = assertThrows(BusinessException.class, () -> tokenService.logout("Bearer ACCESS"));
        assertEquals(503, ex.getCode(),
                "写黑名单失败 = access 完全没被作废。返 200 会让用户以为退了，实际 token 在剩余 30 分钟内仍有效");
    }

    @Test
    @DisplayName("★ 用【已过期】的 accessToken 登出 → 仍然成功（从异常里取 claims，refreshKey 照样删）")
    void logoutWithExpiredAccessTokenStillSucceeds() {
        Claims claims = claimsOf("access", ROLE);
        ExpiredJwtException expired = new ExpiredJwtException(null, claims, "expired");
        when(jwtUtils.parseToken("ACCESS")).thenThrow(expired);

        assertDoesNotThrow(() -> tokenService.logout("Bearer ACCESS"));

        verify(stringRedisTemplate).delete(CacheKeys.tokenRefresh(USER_ID));
        verify(stringRedisTemplate, never()).opsForValue();   // 已过期 → 剩余 <= 0 → 不写黑名单
    }

    @Test
    @DisplayName("logout 的 Authorization 头非法 → 静默返回，不抛异常（幂等）")
    void logoutWithInvalidHeaderIsSilent() {
        assertDoesNotThrow(() -> tokenService.logout(null));
        assertDoesNotThrow(() -> tokenService.logout("Basic abc"));
        assertDoesNotThrow(() -> tokenService.logout("Bearer "));

        verifyNoInteractions(stringRedisTemplate);
    }
}
