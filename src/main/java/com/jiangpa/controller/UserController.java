package com.jiangpa.controller;

import com.jiangpa.common.Result;

import com.jiangpa.dto.UserUpdateDTO;
import com.jiangpa.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;


@RestController
@RequestMapping("/user")
public class UserController {
    @Autowired
    private UserService userService;



    //查找用户
    @GetMapping("/{id}")
    public Result<?> selectUser(@PathVariable Long id) {
        return Result.success(userService.selectUser(id));
    }

    //查找用户列表
    @GetMapping("/list")
    public Result<?> selectList(){
        return Result.success(userService.selectList());
    }

    //更新用户
    @PutMapping("/{id}")
    public Result<?> update(@Valid @RequestBody UserUpdateDTO dto, @PathVariable Long id) {
        userService.update(dto, id);
        return Result.success();
    }

    //删除用户
    @DeleteMapping("/{id}")
    public Result<?> delete(@PathVariable Long id) {
        userService.delete(id);
        return Result.success();
    }


}
