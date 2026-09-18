package com.jiangpa.dto;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;


@Data
public class UserRoleUpdateDTO {
    @NotNull(message = "用户id不能为空")
    private Long id;

    @NotNull(message = "权限设定不能为空！")
    @Min(value = 0, message = "权限值只能是 0(降级) 或 1(升级)")
    @Max(value = 1, message = "权限值只能是 0(降级) 或 1(升级)")
    private Integer role;
}
