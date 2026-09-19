package com.jiangpa.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;


@Data
public class PageQueryDTO {
    @Min(value = 1, message = "从第一页开始访问")
    private Integer pageNum = 1;
    @Min(value = 1, message = "一页只能访问1-50条数据")
    @Max(value = 50, message = "一页只能访问1-50条数据")
    private Integer pageSize = 10;
}
