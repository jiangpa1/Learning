package com.jiangpa.dto;

import lombok.Data;
import lombok.ToString;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class UpdatePasswordDTO {
    @ToString.Exclude
    @NotBlank(message = "旧密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度必须在 6-20 位之间")
    private String oldPassword;
    @ToString.Exclude
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度必须在 6-20 位之间")
    private String newPassword;
    @ToString.Exclude
    @NotBlank(message = "确认密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度必须在 6-20 位之间")
    private  String confirmNewPassword;
}
