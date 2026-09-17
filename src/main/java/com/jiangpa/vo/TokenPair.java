package com.jiangpa.vo;

import lombok.Data;

@Data
public class TokenPair {
    private String accessToken;
    private String refreshToken;
    private Long expiresIn;
}
