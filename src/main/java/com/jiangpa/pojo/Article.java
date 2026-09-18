package com.jiangpa.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("tb_article")
public class Article {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private String content;
    private String summary;
    private Long categoryId;
    private Long viewCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
