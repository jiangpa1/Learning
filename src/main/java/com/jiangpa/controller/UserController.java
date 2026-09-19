package com.jiangpa.controller;

import com.jiangpa.annotation.RateLimit;
import com.jiangpa.annotation.RequireRole;
import com.jiangpa.common.Result;
import com.jiangpa.dto.UserRoleUpdateDTO;
import com.jiangpa.dto.UserUpdateDTO;
import com.jiangpa.service.UserService;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;


@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }


    //查找用户
    @GetMapping("/{id}")
    public Result<?> selectUser(@PathVariable Long id,@RequestAttribute("userId") Long userId, @RequestAttribute("role") Integer role) {
        return Result.success(userService.selectUser(id, userId, role));
    }

    //查找用户列表
    @RateLimit(limit = 30, window = 1000*60)
    @RequireRole(1)
    @GetMapping("/list")
    public Result<?> selectList(@RequestParam(defaultValue = "1") Integer pageNum,
                                @RequestParam(defaultValue = "10") Integer pageSize){
        return Result.success(userService.selectList(pageNum, pageSize));
    }

    //更新用户
    @PutMapping("/{id}")
    public Result<?> update(@Valid @RequestBody UserUpdateDTO dto,
                            @PathVariable Long id, @RequestAttribute("userId") Long userId) {
        userService.update(dto, id, userId);
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
