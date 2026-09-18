package com.jiangpa.controller;


import com.jiangpa.annotation.RateLimit;
import com.jiangpa.common.Result;
import com.jiangpa.dto.ArticleDTO;
import com.jiangpa.service.ArticleService;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/article")
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
    public Result<?> selectArticlesList(@RequestParam(defaultValue = "1") Integer pageNum,
                                        @RequestParam(defaultValue = "10") Integer pageSize){
        return Result.success(articleService.selectArticlesList(pageNum, pageSize));
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
