package com.jiangpa.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;


@Data
public class ArticleDTO {
    @NotBlank(message = "标题不能为空")
    @Size(min = 1, max = 200, message = "标题长度必须在 1-200 之间")
    private String title;
    @NotBlank(message = "内容不能为空")
    private String content;
    private Long categoryId;
}
