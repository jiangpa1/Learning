package com.jiangpa.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

@Data
public class UserUpdateDTO {
    @NotNull(message = "用户id不能为空")
    private Long id;
    private String nickname;
}
