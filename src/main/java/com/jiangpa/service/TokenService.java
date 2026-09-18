package com.jiangpa.service;

import com.jiangpa.vo.TokenPair;

public interface TokenService {
    TokenPair issue(Long userId, String username, Integer role);

    TokenPair refresh(String refreshToken);

    void logout(String authorization);

    boolean isRevoked(String accessToken);
}
