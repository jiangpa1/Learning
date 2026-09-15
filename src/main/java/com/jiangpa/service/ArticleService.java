package com.jiangpa.service;


import com.jiangpa.common.PageResult;
import com.jiangpa.dto.ArticleDTO;
import com.jiangpa.vo.ArticleDetailVO;

public interface ArticleService {
    ArticleDetailVO selectArticleById(Long id);

    PageResult<?> selectArticlesList(Integer pageNum, Integer pageSize);

    Long addArticle(ArticleDTO articleDTO, Long userId);

    void updateArticle(ArticleDTO articleDTO, Long id, Long userId);

    void deleteArticle(Long id, Long userId);
}
