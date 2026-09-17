package com.jiangpa.controller;

import com.jiangpa.common.Result;
import com.jiangpa.dto.UserLoginDTO;
import com.jiangpa.dto.UserRegisterDTO;
import com.jiangpa.dto.RefreshDTO;
import com.jiangpa.service.TokenService;
import com.jiangpa.service.UserService;
import com.jiangpa.vo.UserVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserService userService;
    private final TokenService tokenService;

    public AuthController(UserService userService, TokenService tokenService) {
        this.userService = userService;
        this.tokenService = tokenService;
    }

    //新增用户
    @PostMapping("/register")
    public Result<?> register(@Valid @RequestBody UserRegisterDTO dto) {
        return Result.success(userService.register(dto));
    }

    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody UserLoginDTO dto){
        UserVO user = userService.authenticate(dto);
        return Result.success(tokenService.issue(user.getId(), user.getUsername()));
    }

    @PostMapping("/logout")
    public Result<?> logout(HttpServletRequest request){
        String authorization = request.getHeader("Authorization");
        tokenService.logout(authorization);
        return Result.success();
    }

    @PostMapping("/refresh")
    public Result<?> refresh(@Valid @RequestBody RefreshDTO dto){
        return Result.success(tokenService.refresh(dto.getRefreshToken()));
    }
}
