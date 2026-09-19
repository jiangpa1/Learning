package com.jiangpa.controller;

import com.jiangpa.annotation.RateLimit;
import com.jiangpa.annotation.RequireRole;
import com.jiangpa.common.Result;
import com.jiangpa.dto.PageQueryDTO;
import com.jiangpa.dto.UpdatePasswordDTO;
import com.jiangpa.dto.UserRoleUpdateDTO;
import com.jiangpa.dto.UpdateNicknameDTO;
import com.jiangpa.service.UserService;
import com.jiangpa.config.Knife4jConfig;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;


@RestController
@RequestMapping("/user")
// ★ 必须声明在【接口级】，原因见 Knife4jConfig（Knife4j 不读根级 security）
@SecurityRequirement(name = Knife4jConfig.SECURITY_SCHEME_NAME)
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }


    //查找用户
    @GetMapping("/{id}")
    public Result<?> selectUser(@PathVariable Long id, @RequestAttribute("userId") Long userId, @RequestAttribute("role") Integer role) {
        return Result.success(userService.selectUser(id, userId, role));
    }

    //查找用户列表
    @RateLimit(limit = 30, window = 1000*60)
    @RequireRole(1)
    @GetMapping("/list")
    public Result<?> selectList(@Valid @ModelAttribute PageQueryDTO pageQueryDTO){
        return Result.success(userService.selectList(pageQueryDTO));
    }

    //更新用户昵称
    @PutMapping("/nickname/{id}")
    public Result<?> updateNickname(@Valid @RequestBody UpdateNicknameDTO dto,
                            @PathVariable Long id, @RequestAttribute("userId") Long userId) {
        userService.updateNickname(dto, id, userId);
        return Result.success();
    }

    //更新用户密码
    @PutMapping("/password/{id}")
    public Result<?> updatePassword(@Valid @RequestBody UpdatePasswordDTO updatePasswordDTO,
                                    @PathVariable Long id, @RequestAttribute("userId") Long userId){
        userService.updatePassword(updatePasswordDTO, id, userId);
        return Result.success();
    }

    //删除用户
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id,
                            @RequestAttribute("userId") Long userId, @RequestAttribute("role") Integer role) {
        userService.delete(id, userId, role);
        return Result.success();
    }

    //新增、删除管理员
    @RequireRole(1)
    @PutMapping("/role")
    public Result<?> updateRole(@Valid @RequestBody UserRoleUpdateDTO userRoleUpdateDTO) {
        userService.updateRole(userRoleUpdateDTO);
        return Result.success();
    }

}
