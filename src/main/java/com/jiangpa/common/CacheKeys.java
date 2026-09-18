package com.jiangpa.common;

public final class CacheKeys {
    private CacheKeys() {}

    private static final String PREFIX = "learning:";
    public static final String NULL_SENTINEL = "__NULL__";

    public static String articleDetail(Long id) { return PREFIX + "article:detail:" + id; }
    public static String articleViews(Long id)  { return PREFIX + "article:views:"  + id; }
    public static String articleLock(Long id)  { return PREFIX + "article:lock:"  + id; }
    public static String tokenRefresh(Long id)  { return PREFIX + "token:refresh:" + id; }
    public static String tokenBlacklist(String hash)  { return PREFIX + "token:blacklist:" + hash; }
    /** 限流 key：维度 + 标识 + 接口路径。
     *  ★ uri 必须参与拼接：否则 /article/1 和 /article/2 会共用同一个计数器（限流额度被互相消耗）。
     *  group 由调用方拼好前缀，如 "user:17"、"ip:127.0.0.1"。 */
    public static String rateLimit(String dimension, String group, String uri) {
        return PREFIX + "limit:" + dimension + ":" + group + ":" + uri;
    }
}
