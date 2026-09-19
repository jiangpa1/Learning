package com.jiangpa.utils;

import com.jiangpa.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JwtUtils 单元测试。
 *
 * <p>重点锁住四件事（每一条都对应一个真实踩过的坑）：
 * <ol>
 *   <li>{@code type} claim 必须能正确区分两种 token（漏了会让 refreshToken 变万能通行证）</li>
 *   <li><b>取值必须用 {@code Number.class}</b> —— 用 {@code String.class} 取数字 claim 会抛
 *       {@code RequiredTypeException}，而它继承 {@code JwtException}，会被拦截器兜底 catch 吞掉，
 *       表现为「所有带合法 token 的请求返 401」</li>
 *   <li>过期 token 必须抛 {@code ExpiredJwtException}（拦截器靠它区分「过期」和「无效」）</li>
 *   <li>同一秒内签发的两个 token <b>不能相同</b>（靠 jti；否则 refresh 轮转静默失效）</li>
 * </ol>
 *
 * <p>纯单元测试：不启动 Spring 容器、不连数据库和 Redis。
 */
class JwtUtilsTest {

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"; // 64 字符，满足 HS256 的 32 字节要求

    private JwtUtils jwtUtils;

    @BeforeEach
    void setUp() {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setAccessExpiration(Duration.ofMinutes(30));
        props.setRefreshExpiration(Duration.ofDays(7));
        props.setIssuer("learning");

        jwtUtils = new JwtUtils(props);
        jwtUtils.init();   // 模拟 @PostConstruct（单元测试里不启动 Spring，必须手动调）
    }

    // ---------- 1. type claim：双 Token 的命门 ----------

    @Test
    @DisplayName("access token 的 type 必须是 access，且不能被认成 refresh")
    void accessTokenType() {
        String token = jwtUtils.generateAccessToken(17L, "lisi", 1);
        Claims claims = jwtUtils.parseToken(token);

        assertTrue(jwtUtils.isAccessToken(claims), "access token 应被识别为 access");
        assertFalse(jwtUtils.isRefreshToken(claims), "access token 不应被识别为 refresh");
    }

    @Test
    @DisplayName("refresh token 的 type 必须是 refresh（漏校验 = refresh 变万能通行证）")
    void refreshTokenType() {
        String token = jwtUtils.generateRefreshToken(17L, "lisi", 1);
        Claims claims = jwtUtils.parseToken(token);

        assertTrue(jwtUtils.isRefreshToken(claims));
        assertFalse(jwtUtils.isAccessToken(claims));
    }

    // ---------- 2. 取值类型：Number vs String ----------

    @Test
    @DisplayName("role 是 JSON 数字，必须能用 Number.class 取出（用 String.class 会抛异常）")
    void roleClaimMustBeReadAsNumber() {
        String token = jwtUtils.generateAccessToken(17L, "lisi", 1);
        Claims claims = jwtUtils.parseToken(token);

        // ✅ 正确姿势
        Number role = claims.get("role", Number.class);
        assertNotNull(role, "role 应该能取出来");
        assertEquals(1, role.intValue());

        // ❌ 错误姿势：类型精确比对会抛 RequiredTypeException（继承 JwtException）
        //    这正是「所有业务接口返 401」那个 bug 的根因，用测试把它钉住
        assertThrows(JwtException.class,
                () -> claims.get("role", String.class),
                "用 String.class 取数字 claim 必须抛 JwtException —— 这就是当初全站 401 的原因");
    }

    @Test
    @DisplayName("role=0 也能正确取出（0 是最容易被误判成 null 的值）")
    void roleZeroIsReadable() {
        String token = jwtUtils.generateAccessToken(18L, "zhangsan", 0);
        Claims claims = jwtUtils.parseToken(token);

        Number role = claims.get("role", Number.class);
        assertNotNull(role, "role=0 不能变成 null");
        assertEquals(0, role.intValue());
    }

    // ---------- 3. 业务字段 ----------

    @Test
    @DisplayName("getUserId 从 sub 取（不是从 id claim 取，避免 JSON 数字被反序列化成 Integer）")
    void getUserIdReadsSubject() {
        String token = jwtUtils.generateAccessToken(42L, "someone", 0);
        Claims claims = jwtUtils.parseToken(token);

        assertEquals(42L, jwtUtils.getUserId(claims));
        assertEquals("someone", claims.get("username", String.class));
        assertEquals("learning", claims.getIssuer());
    }

    @Test
    @DisplayName("getRemainingMillis 对有效期内的 token 返回正值，且不超过 access 有效期")
    void remainingMillisIsPositive() {
        String token = jwtUtils.generateAccessToken(17L, "lisi", 1);
        Claims claims = jwtUtils.parseToken(token);

        long remaining = jwtUtils.getRemainingMillis(claims);
        assertTrue(remaining > 0, "刚签发的 token 剩余时间应为正");
        assertTrue(remaining <= Duration.ofMinutes(30).toMillis(),
                "剩余时间不应超过 30 分钟（这是黑名单 TTL 的计算依据）");
    }

    // ---------- 4. 过期与篡改 ----------

    @Test
    @DisplayName("过期 token 抛 ExpiredJwtException（拦截器靠它区分'过期'与'无效'，前端据此决定是否跳登录页）")
    void expiredTokenThrowsExpiredJwtException() {
        JwtProperties expiredProps = new JwtProperties();
        expiredProps.setSecret(SECRET);
        expiredProps.setAccessExpiration(Duration.ofSeconds(-60));   // 60 秒前就过期了
        expiredProps.setRefreshExpiration(Duration.ofDays(7));
        expiredProps.setIssuer("learning");

        JwtUtils expiredJwtUtils = new JwtUtils(expiredProps);
        expiredJwtUtils.init();

        String expiredToken = expiredJwtUtils.generateAccessToken(17L, "lisi", 1);

        ExpiredJwtException ex = assertThrows(ExpiredJwtException.class,
                () -> expiredJwtUtils.parseToken(expiredToken),
                "过期 token 必须抛 ExpiredJwtException 而不是泛化的 JwtException");
        assertNotNull(ex.getClaims(), "从异常里能取回 claims —— 这是 logout 允许'过期 token 也能登出'的实现基础");
    }

    @Test
    @DisplayName("签名被篡改的 token 抛异常，绝不能解析成功")
    void tamperedTokenRejected() {
        String token = jwtUtils.generateAccessToken(17L, "lisi", 1);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";   // 改掉签名尾部

        assertThrows(JwtException.class, () -> jwtUtils.parseToken(tampered));
    }

    @Test
    @DisplayName("结构损坏的字符串抛异常（对应 refresh 接口的 500→401 修复）")
    void malformedTokenRejected() {
        assertThrows(JwtException.class, () -> jwtUtils.parseToken("this-is-not-a-jwt"));
    }

    @Test
    @DisplayName("用别的密钥签发的 token 必须验签失败（换密钥 = 强制全员下线）")
    void tokenFromAnotherSecretRejected() {
        JwtProperties otherProps = new JwtProperties();
        otherProps.setSecret("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        otherProps.setAccessExpiration(Duration.ofMinutes(30));
        otherProps.setRefreshExpiration(Duration.ofDays(7));
        otherProps.setIssuer("learning");

        JwtUtils otherJwtUtils = new JwtUtils(otherProps);
        otherJwtUtils.init();

        String foreignToken = otherJwtUtils.generateAccessToken(17L, "lisi", 1);

        assertThrows(JwtException.class, () -> jwtUtils.parseToken(foreignToken));
    }

    // ---------- 5. jti：轮转能生效的前提 ----------

    @Test
    @DisplayName("同一秒内连续签发的两个 token 必须不同（jti 修复；否则 refresh 轮转静默失效）")
    void twoTokensInSameSecondMustDiffer() {
        // 循环签发，只要有一次相同就说明 jti 没生效
        for (int i = 0; i < 20; i++) {
            String a = jwtUtils.generateAccessToken(17L, "lisi", 1);
            String b = jwtUtils.generateAccessToken(17L, "lisi", 1);
            assertNotEquals(a, b,
                    "第 " + i + " 次：同一用户同一秒签发的两个 token 相同 —— jti 失效，refresh 轮转会静默失败");
        }
    }

    @Test
    @DisplayName("两个 token 的 payload 不同（jti 存在），所以 SHA-256 哈希也不同")
    void tokenHashesDiffer() {
        String a = jwtUtils.generateRefreshToken(17L, "lisi", 1);
        String b = jwtUtils.generateRefreshToken(17L, "lisi", 1);

        assertNotEquals(jwtUtils.hashToken(a), jwtUtils.hashToken(b),
                "哈希必须不同，否则 Redis 里新旧 refresh 哈希相等，旧 token 照样能用");
    }

    // ---------- 6. hashToken ----------

    @Test
    @DisplayName("hashToken 返回 64 位十六进制（SHA-256），且相同输入结果稳定")
    void hashTokenIsStableSha256() {
        String token = "some-token-value";
        String h1 = jwtUtils.hashToken(token);
        String h2 = jwtUtils.hashToken(token);

        assertEquals(h1, h2, "同一输入必须得到同一哈希（否则 refresh 比对永远失败）");
        assertEquals(64, h1.length(), "SHA-256 十六进制表示是 64 位");
        assertTrue(h1.matches("[0-9a-f]{64}"), "应为小写十六进制");
        assertNotEquals(token, h1, "Redis 里必须存哈希而不是明文凭证");
    }
}
