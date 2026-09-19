package com.jiangpa.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CacheKeys 单元测试。
 *
 * <p>这个类看起来只是拼字符串，但<b>拼错一个字符就可能出大问题</b> —— 项目里真实发生过两次：
 * <ol>
 *   <li>限流 key 的签名收了 {@code uri} 参数却忘了拼进去 → {@code /article/1} 和 {@code /article/2}
 *       共用同一个计数器，刷爆一篇会让其他文章打不开（<b>列表接口看不出来，只有详情接口才暴露</b>）</li>
 *   <li>key 拼成 {@code learning:limituser::17:/article} 这种双冒号，导致
 *       {@code KEYS "learning:limit:*"} 扫不到，排查时误判成「限流没生效」</li>
 * </ol>
 *
 * <p>用测试把这两点钉死，以后改 key 结构会立刻被拦下。
 */
class CacheKeysTest {

    @Test
    @DisplayName("★ uri 必须参与限流 key 拼接：不同文章不能共用计数器")
    void rateLimitKeyMustIncludeUri() {
        String k1 = CacheKeys.rateLimit("user", "17", "/article/1");
        String k2 = CacheKeys.rateLimit("user", "17", "/article/2");

        assertNotEquals(k1, k2,
                "不同接口路径必须产生不同 key —— 否则 /article/1 的额度被 /article/2 消耗");
    }

    @Test
    @DisplayName("★ 同一维度+同一路径必须产生稳定一致的 key（否则限流永远不触发）")
    void rateLimitKeyIsStable() {
        String a = CacheKeys.rateLimit("user", "17", "/article/1");
        String b = CacheKeys.rateLimit("user", "17", "/article/1");

        assertEquals(a, b, "同一用户同一接口的 key 必须一致，否则每次都是新计数器，限流形同虚设");
    }

    @Test
    @DisplayName("不同维度（user vs ip）不能撞 key")
    void rateLimitKeyDistinguishesDimension() {
        String byUser = CacheKeys.rateLimit("user", "127.0.0.1", "/auth/login");
        String byIp = CacheKeys.rateLimit("ip", "127.0.0.1", "/auth/login");

        assertNotEquals(byUser, byIp, "同一个值在不同维度下必须产生不同 key");
    }

    @Test
    @DisplayName("限流 key 格式正确：learning:limit:{维度}:{标识}:{uri}，且 KEYS learning:limit:* 能扫到")
    void rateLimitKeyFormat() {
        String key = CacheKeys.rateLimit("user", "17", "/user/list");

        assertEquals("learning:limit:user:17:/user/list", key);
        assertTrue(key.startsWith("learning:limit:"),
                "必须以 learning:limit: 开头，否则 redis-cli 的 KEYS \"learning:limit:*\" 扫不到");
        assertFalse(key.contains("::"), "不允许出现双冒号（早期 bug：limit 后漏了冒号）");
    }

    @Test
    @DisplayName("所有 key 都带 learning: 前缀（虚拟机是多项目共用，不加前缀会撞 key）")
    void allKeysShareProjectPrefix() {
        String[] keys = {
                CacheKeys.articleDetail(1L),
                CacheKeys.articleViews(1L),
                CacheKeys.articleLock(1L),
                CacheKeys.tokenRefresh(17L),
                CacheKeys.tokenBlacklist("abc123"),
                CacheKeys.rateLimit("user", "17", "/article/1")
        };

        for (String key : keys) {
            assertTrue(key.startsWith("learning:"),
                    "key 必须以 learning: 开头，实际是：" + key);
        }
    }

    @Test
    @DisplayName("文章相关的三个 key 互相隔离（detail/views/lock 不能撞）")
    void articleKeysAreDistinct() {
        String detail = CacheKeys.articleDetail(1L);
        String views = CacheKeys.articleViews(1L);
        String lock = CacheKeys.articleLock(1L);

        assertNotEquals(detail, views);
        assertNotEquals(detail, lock);
        assertNotEquals(views, lock);
    }

    @Test
    @DisplayName("不同 id 的 key 必须不同（防止把 id 写死或漏拼）")
    void keysVaryById() {
        assertNotEquals(CacheKeys.articleDetail(1L), CacheKeys.articleDetail(2L));
        assertNotEquals(CacheKeys.tokenRefresh(17L), CacheKeys.tokenRefresh(18L));
        assertNotEquals(CacheKeys.tokenBlacklist("aaa"), CacheKeys.tokenBlacklist("bbb"));
    }

    @Test
    @DisplayName("token 相关的两个 key 语义不同：refresh 按 userId，blacklist 按 token 哈希")
    void tokenKeysHaveDifferentShapes() {
        String refresh = CacheKeys.tokenRefresh(17L);
        String blacklist = CacheKeys.tokenBlacklist("deadbeef");

        assertEquals("learning:token:refresh:17", refresh);
        assertEquals("learning:token:blacklist:deadbeef", blacklist);
        assertTrue(refresh.contains("refresh") && blacklist.contains("blacklist"),
                "两者必须能从 key 名直接区分，方便 redis-cli 排查");
    }

    @Test
    @DisplayName("空值哨兵是固定常量（改掉它会让缓存里的旧哨兵失效，穿透防护短暂失灵）")
    void nullSentinelIsStable() {
        assertEquals("__NULL__", CacheKeys.NULL_SENTINEL);
    }
}
