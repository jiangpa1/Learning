package com.jiangpa.utils;

import org.springframework.stereotype.Component;

import javax.servlet.http.HttpServletRequest;

/**
 * 取客户端真实 IP。
 *
 * 与 {@link JwtUtils} 保持一致的风格：注册成 Spring Bean、用构造器注入、对外是实例方法。
 * （JwtUtils 依赖 JwtProperties、靠 @PostConstruct 初始化密钥，做不到静态化；
 *  工具类统一成 Bean 风格，测试时也更容易替换成 mock。）
 *
 * ⚠️ 部署前提（安全相关，别忽略）：
 * 本类会优先采信 X-Forwarded-For 等代理头，而这些头【客户端可以伪造】。
 * 如果应用能被直连（不经反向代理），攻击者只要每个请求换一个假的 XFF，
 * 就能让"按 IP 限流"彻底失效（每次都是新 IP = 新额度）。
 *
 * 正确做法（二者之一）：
 *   ① 生产环境让 Nginx 覆盖该头：proxy_set_header X-Forwarded-For $remote_addr;
 *   ② 只在"直连来源是可信网关"时才采信 XFF，否则一律用 getRemoteAddr()
 *
 * 本地直连（本项目开发环境）没有代理，XFF 缺失会自然回落到 getRemoteAddr()，是安全的。
 */
@Component
public class IpUtils {

    private static final String UNKNOWN = "unknown";
    private static final String LOOPBACK_IPV6 = "::1";

    public String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");

        if (isInvalid(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (isInvalid(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (isInvalid(ip)) {
            ip = request.getHeader("WL-Proxy-Client-IP");
        }
        if (isInvalid(ip)) {
            ip = request.getHeader("HTTP_CLIENT_IP");
        }
        if (isInvalid(ip)) {
            ip = request.getHeader("HTTP_X_FORWARDED_FOR");
        }
        if (isInvalid(ip)) {
            ip = request.getRemoteAddr();
        }

        // X-Forwarded-For 可能是多个 IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }

        // 本机回环处理
        if ("0:0:0:0:0:0:0:1".equals(ip) || LOOPBACK_IPV6.equals(ip)) {
            ip = "127.0.0.1";
        }

        // 兜底：极端情况下 remoteAddr 也可能为 null，避免拼出 "ip:null" 这种 key
        return ip == null || ip.isEmpty() ? UNKNOWN : ip;
    }

    private boolean isInvalid(String ip) {
        return ip == null || ip.isEmpty() || UNKNOWN.equalsIgnoreCase(ip);
    }
}
