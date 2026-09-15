package com.jiangpa.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class CommentDTO {
    @NotNull(message = "文章id不能为空！")
    private Long articleId;
    @NotBlank(message = "评论内容不能为空")
    @Size(max = 500, message = "评论上限500字")
    private String content;
}
