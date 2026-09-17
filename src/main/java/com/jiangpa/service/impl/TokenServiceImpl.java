package com.jiangpa.service.impl;


import com.jiangpa.common.CacheKeys;
import com.jiangpa.exception.BusinessException;
import com.jiangpa.properties.JwtProperties;
import com.jiangpa.service.TokenService;
import com.jiangpa.utils.JwtUtils;
import com.jiangpa.vo.TokenPair;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;


@Service
@Slf4j
public class TokenServiceImpl implements TokenService {
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;
    private final StringRedisTemplate stringRedisTemplate;
    private final JwtProperties jwtProperties;

    public TokenServiceImpl(JwtUtils jwtUtils, StringRedisTemplate stringRedisTemplate, JwtProperties jwtProperties) {
        this.jwtUtils = jwtUtils;
        this.stringRedisTemplate = stringRedisTemplate;
        this.jwtProperties = jwtProperties;
    }

    @Override
    public TokenPair issue(Long userId, String username) {
        String accessToken = jwtUtils.generateAccessToken(userId, username);
        String refreshToken = jwtUtils.generateRefreshToken(userId, username);

        stringRedisTemplate.opsForValue().set(CacheKeys.tokenRefresh(userId),
                jwtUtils.hashToken(refreshToken), jwtProperties.getRefreshExpiration());

        TokenPair tokenPair = new TokenPair();
        tokenPair.setAccessToken(accessToken);
        tokenPair.setRefreshToken(refreshToken);
        tokenPair.setExpiresIn(jwtProperties.getAccessExpiration().toMillis());

        return tokenPair;
    }

    @Override
    public TokenPair refresh(String refreshToken) {
        Claims claims = jwtUtils.parseToken(refreshToken);

        if(!jwtUtils.isRefreshToken(claims)){
            throw new BusinessException(401, "token类型错误");
        }

        Long userId = jwtUtils.getUserId(claims);

        String stored =  stringRedisTemplate.opsForValue().get(CacheKeys.tokenRefresh(userId));
        if(stored == null || !stored.equals(jwtUtils.hashToken(refreshToken))){
            throw new BusinessException(401, "登录已失效，请重新登陆");
        }

        return issue(userId, claims.get("username", String.class));
    }

    @Override
    public void logout(String authorization) {
        String token = stripBearer(authorization);
        if(token == null) {
            log.warn("Authorization 头格式非法，登出未生效：{}", authorization);
            return;
        }

        Claims claims = parseQuietly(token);
        if(claims == null) return;

        Long userId = jwtUtils.getUserId(claims);
        stringRedisTemplate.delete(CacheKeys.tokenRefresh(userId));

        if (!jwtUtils.isAccessToken(claims)) {
            log.warn("登出时 token 类型不是 access，跳过黑名单");
            return;      // refresh key 已经删了，目的达到了
        }

        long remaining = jwtUtils.getRemainingMillis(claims);
        if (remaining > 0) {
            stringRedisTemplate.opsForValue().set(
                    CacheKeys.tokenBlacklist(jwtUtils.hashToken(token)), "1", remaining, TimeUnit.MILLISECONDS
            );
        }
    }

    private Claims parseQuietly(String token) {
        try {
            return jwtUtils.parseToken(token);
        }catch (ExpiredJwtException e){
            return e.getClaims();
        }catch (Exception e){
            log.warn("token 解析失败，登出未生效：{}", e.getMessage());
            return null;
        }
    }

    private String stripBearer(String authorization) {
        if (authorization == null) {
            return null;
        }
        if (!authorization.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null; // 不是 Bearer 认证
        }
        return authorization.substring(BEARER_PREFIX.length()).trim();
    }

    @Override
    public boolean isRevoked(String accessToken) {
        String key = CacheKeys.tokenBlacklist(jwtUtils.hashToken(accessToken));
        try {
            return Boolean.TRUE.equals(stringRedisTemplate.hasKey(key));
        }catch (Exception e){
            log.error("黑名单查询失败，按已吊销处理（fail-closed），key={}", key, e);
            throw new BusinessException(503, "认证服务暂时不可用，请稍后重试");
        }
    }
}
