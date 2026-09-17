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
}
