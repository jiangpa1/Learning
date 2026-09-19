package com.jiangpa.controller;

import com.jiangpa.annotation.RateLimit;
import com.jiangpa.common.Result;
import com.jiangpa.dto.CommentDTO;
import com.jiangpa.dto.PageQueryDTO;
import com.jiangpa.service.CommentService;
import com.jiangpa.config.Knife4jConfig;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/comment")
// ★ 必须声明在【接口级】，原因见 Knife4jConfig（Knife4j 不读根级 security）
@SecurityRequirement(name = Knife4jConfig.SECURITY_SCHEME_NAME)
public class CommentController {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @RateLimit(limit = 10, window = 1000*60)
    @PostMapping
    public Result<?> addComment(@Valid @RequestBody CommentDTO commentDTO,
                                @RequestAttribute("userId") Long userId) {
        return Result.success(commentService.addComment(commentDTO, userId));
    }

    @GetMapping("/list")
    public Result<?> selectCommentList(@RequestParam(required = true) Long articleId,
                                       @Valid @ModelAttribute PageQueryDTO pageQueryDTO){
        return Result.success(commentService.selectCommentList(articleId, pageQueryDTO));
    }

    @DeleteMapping("/{id}")
    public Result<?> deleteComment(@PathVariable Long id, @RequestAttribute("userId")  Long userId) {
        commentService.deleteComment(id, userId);
        return Result.success();
    }
}
