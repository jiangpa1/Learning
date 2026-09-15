package com.jiangpa.controller;

import com.jiangpa.common.Result;
import com.jiangpa.dto.CommentDTO;
import com.jiangpa.service.CommentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/comment")
public class CommentController {
    @Autowired
    private CommentService commentService;

    @PostMapping
    public Result<?> addComment(@Valid @RequestBody CommentDTO commentDTO,
                                @RequestAttribute("userId") Long userId) {
        return Result.success(commentService.addComment(commentDTO, userId));
    }

    @GetMapping("/list")
    public Result<?> selectCommentList(@RequestParam(required = true) Long articleId,
                                       @RequestParam(defaultValue = "1") Integer pageNum,
                                       @RequestParam(defaultValue = "10") Integer pageSize){
        return Result.success(commentService.selectCommentList(articleId, pageNum, pageSize));
    }

    @DeleteMapping("/{id}")
    public Result<?> deleteComment(@PathVariable Long id, @RequestAttribute("userId")  Long userId) {
        commentService.deleteComment(id, userId);
        return Result.success();
    }
}
