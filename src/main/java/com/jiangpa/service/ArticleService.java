package com.jiangpa.service;


import com.jiangpa.common.PageResult;
import com.jiangpa.dto.ArticleDTO;
import com.jiangpa.dto.PageQueryDTO;
import com.jiangpa.vo.ArticleDetailVO;
import com.jiangpa.vo.ArticleListVO;

public interface ArticleService {
    ArticleDetailVO selectArticleById(Long id);

    PageResult<ArticleListVO> selectArticlesList(PageQueryDTO pageQueryDTO);

    Long addArticle(ArticleDTO articleDTO, Long userId);

    void updateArticle(ArticleDTO articleDTO, Long id, Long userId);

    void deleteArticle(Long id, Long userId);
}
