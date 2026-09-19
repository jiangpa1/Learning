package com.jiangpa.service;

import com.jiangpa.common.PageResult;
import com.jiangpa.dto.CommentDTO;
import com.jiangpa.dto.PageQueryDTO;
import com.jiangpa.vo.CommentVO;

public interface CommentService {
    Long addComment(CommentDTO commentDTO, Long userId);

    PageResult<CommentVO> selectCommentList(Long articleId, PageQueryDTO pageQueryDTO);

    void deleteComment(Long id, Long userId);
}
