package com.jiangpa.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ArticleListVO {
    private Long id;
    private String title;
    private String summary;
    private Long userId;
    private String authorNickname;
    private Long viewCount;
    private LocalDateTime createTime;
}
