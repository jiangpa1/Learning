package com.jiangpa.controller;


import com.jiangpa.annotation.RateLimit;
import com.jiangpa.common.Result;
import com.jiangpa.dto.ArticleDTO;
import com.jiangpa.dto.PageQueryDTO;
import com.jiangpa.service.ArticleService;
import com.jiangpa.config.Knife4jConfig;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/article")
// ★ 必须声明在【接口级】：Knife4j 只读接口自己的 security 来决定要不要把 Authorize 的 token
//   挂到请求上，根级的全局要求它不下发 —— 少了这行就是"填了 token 也不带头"（原因见 Knife4jConfig）
@SecurityRequirement(name = Knife4jConfig.SECURITY_SCHEME_NAME)
public class ArticleController {

    private final ArticleService articleService;

    public ArticleController(ArticleService articleService) {
        this.articleService = articleService;
    }

    //查找文章
    @RateLimit(limit = 120, window = 1000*60)
    @GetMapping("/{id}")
    public Result<?> selectArticleById(@PathVariable Long id){
        return Result.success(articleService.selectArticleById(id));
    }

    //查找文章列表
    @RateLimit(limit = 120, window = 1000*60)
    @GetMapping("/list")
    public Result<?> selectArticlesList(@Valid @ModelAttribute PageQueryDTO pageQueryDTO){
        return Result.success(articleService.selectArticlesList(pageQueryDTO));
    }

    //新增文章
    @RateLimit(limit = 10, window = 1000*60)
    @PostMapping
    public Result<?> addArticle(@Valid @RequestBody ArticleDTO articleDTO,
                                @RequestAttribute("userId") Long userId){
        return Result.success(articleService.addArticle(articleDTO, userId));
    }

    //更新文章
    @PutMapping("/{id}")
    public Result<?> updateArticle(@Valid @RequestBody ArticleDTO articleDTO, @PathVariable Long id,
                                   @RequestAttribute("userId") Long userId){
        articleService.updateArticle(articleDTO, id,  userId);
        return Result.success();
    }

    //删除文章
    @DeleteMapping("/{id}")
    public Result<?> deleteArticle(@PathVariable Long id,  @RequestAttribute("userId") Long userId){
        articleService.deleteArticle(id, userId);
        return Result.success();
    }
}
