package com.jiangpa.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class CategoryDTO {
    @NotBlank(message = "分类名不能为空！")
    @Size(min = 1, max = 50, message = "分类名长度必须在 1-50 之间")
    String name;
}
