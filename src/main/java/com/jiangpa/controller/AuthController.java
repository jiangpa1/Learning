package com.jiangpa.controller;

import com.jiangpa.common.Result;
import com.jiangpa.dto.UserLoginDTO;
import com.jiangpa.dto.UserRegisterDTO;
import com.jiangpa.service.UserService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    //新增用户
    @PostMapping("/register")
    public Result<?> register(@Valid @RequestBody UserRegisterDTO dto) {
        return Result.success(userService.register(dto));
    }

    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody UserLoginDTO dto){
        return Result.success(userService.login(dto));
    }
}
