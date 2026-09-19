package com.jiangpa.controller;

import com.jiangpa.annotation.RateLimit;
import com.jiangpa.common.Result;
import com.jiangpa.dto.UserLoginDTO;
import com.jiangpa.dto.UserRegisterDTO;
import com.jiangpa.dto.RefreshDTO;
import com.jiangpa.config.Knife4jConfig;
import com.jiangpa.service.TokenService;
import com.jiangpa.service.UserService;
import com.jiangpa.vo.UserVO;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/auth")
// ★ 这里也要声明：/auth/** 虽然在拦截器放行名单里，但 /auth/logout 需要读 Authorization 头
//   去拉黑 token —— 不声明的话从文档页调 logout 永远带不上头。原因见 Knife4jConfig
@SecurityRequirement(name = Knife4jConfig.SECURITY_SCHEME_NAME)
public class AuthController {

    private final UserService userService;
    private final TokenService tokenService;

    public AuthController(UserService userService, TokenService tokenService) {
        this.userService = userService;
        this.tokenService = tokenService;
    }

    //新增用户
    @RateLimit(limit = 5, window = 1000*60)
    @PostMapping("/register")
    public Result<?> register(@Valid @RequestBody UserRegisterDTO dto) {
        return Result.success(userService.register(dto));
    }

    //登录
    @RateLimit(limit = 10, window = 1000*60)
    @PostMapping("/login")
    public Result<?> login(@Valid @RequestBody UserLoginDTO dto){
        UserVO user = userService.authenticate(dto);
        return Result.success(tokenService.issue(user.getId(), user.getUsername(), user.getRole()));
    }

    //登出
    //注意：本接口不需要"已登录"（/auth/** 在拦截器放行名单里），但它自己要读 Authorization 头
    //去拉黑当前 token —— 所以在文档里调试它之前，仍然要先在 Authorize 里填好 accessToken。
    @PostMapping("/logout")
    public Result<?> logout(HttpServletRequest request){
        String authorization = request.getHeader("Authorization");
        tokenService.logout(authorization);
        return Result.success();
    }

    //刷新登录缓存
    //refreshToken 走【请求体】，不是 Authorization 头 —— Authorize 里填的 accessToken 对本接口无效。
    @RateLimit(limit = 20, window = 1000*60)
    @PostMapping("/refresh")
    public Result<?> refresh(@Valid @RequestBody RefreshDTO dto){
        return Result.success(tokenService.refresh(dto.getRefreshToken()));
    }
}
