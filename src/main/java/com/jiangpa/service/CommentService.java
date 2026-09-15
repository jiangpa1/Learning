package com.jiangpa.service;

import com.jiangpa.common.PageResult;
import com.jiangpa.dto.CommentDTO;

public interface CommentService {
    Long addComment(CommentDTO commentDTO, Long userId);

    PageResult<?> selectCommentList(Long articleId, Integer pageNum, Integer pageSize);

    void deleteComment(Long id, Long userId);
}
