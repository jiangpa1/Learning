package com.jiangpa.vo;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ArticleDetailVO {
    private Long id;
    private String title;
    private String content;
    private Long userId;
    private String authorNickname;
    private Long categoryId;
    private Long viewCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
