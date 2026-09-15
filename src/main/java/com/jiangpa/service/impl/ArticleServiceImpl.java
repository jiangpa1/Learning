package com.jiangpa.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;



import com.jiangpa.common.PageResult;
import com.jiangpa.dto.ArticleDTO;
import com.jiangpa.exception.BusinessException;
import com.jiangpa.mapper.ArticleMapper;
import com.jiangpa.mapper.UserMapper;
import com.jiangpa.pojo.Article;
import com.jiangpa.pojo.User;
import com.jiangpa.service.ArticleService;

import com.jiangpa.vo.ArticleDetailVO;
import com.jiangpa.vo.ArticleListVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ArticleServiceImpl implements ArticleService {
    private final ArticleMapper articleMapper;
    private final UserMapper userMapper;


    public ArticleServiceImpl(ArticleMapper articleMapper, UserMapper userMapper) {
        this.articleMapper = articleMapper;
        this.userMapper = userMapper;

    }
    @Override
    public ArticleDetailVO selectArticleById(Long id) {
        Article article = articleMapper.selectById(id);
        if (article == null) {
            throw new BusinessException(404, "文章不存在！");
        }
        ArticleDetailVO articleDetailVO = new ArticleDetailVO();
        User author = userMapper.selectById(article.getUserId());

        BeanUtils.copyProperties(article, articleDetailVO);
        articleDetailVO.setAuthorNickname(author == null ? null : author.getNickname());

        LambdaUpdateWrapper<Article> viewWrapper = new LambdaUpdateWrapper<>();
        viewWrapper.eq(Article::getId, id).setSql("view_count = view_count + 1");
        articleMapper.update(null, viewWrapper);

        articleDetailVO.setViewCount(article.getViewCount() + 1);
        return articleDetailVO;
    }

    @Override
    public PageResult<?> selectArticlesList(Integer pageNum, Integer pageSize) {
        pageSize = Math.min(pageSize, 50);
        Page<Article> page = new Page<>(pageNum, pageSize);



        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(Article::getId, Article::getTitle, Article::getSummary,
                        Article::getUserId, Article::getViewCount, Article::getCreateTime)
                .orderByDesc(Article::getCreateTime);

        IPage<Article> result = articleMapper.selectPage(page, wrapper);


        List<Long> userIds = result.getRecords().stream()
                .map(Article::getUserId)
                .distinct()
                .toList();

        Map<Long, String> nicknameMap = userIds.isEmpty()
                ? new HashMap<>()
                : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId,
                        u -> u.getNickname() != null ? u.getNickname() : "默认昵称",
                        (oldVal, newVal) -> oldVal));


        List<ArticleListVO> voList = result.getRecords().stream()
                .map(article -> {
                    ArticleListVO vo = new ArticleListVO();
                    BeanUtils.copyProperties(article, vo);
                    vo.setAuthorNickname(nicknameMap.getOrDefault(article.getUserId(), "未知作者"));
                    return vo;
                })
                .toList();


        PageResult<ArticleListVO> pageResult = new PageResult<>();
        pageResult.setTotal(result.getTotal());
        pageResult.setPageNum(result.getCurrent());
        pageResult.setPageSize(result.getSize());
        pageResult.setPages(result.getPages());
        pageResult.setRecords(voList);

        return pageResult;
    }

    @Override
    public Long addArticle(ArticleDTO articleDTO, Long userId) {
        Article article = new Article();
        BeanUtils.copyProperties(articleDTO, article);
        article.setUserId(userId);
        LocalDateTime now = LocalDateTime.now();
        article.setCreateTime(now);
        article.setUpdateTime(now);

        String summary = getSummary(articleDTO.getContent());

        article.setSummary(summary);
        articleMapper.insert(article);

        return article.getId();
    }

    @Override
    public void updateArticle(ArticleDTO articleDTO, Long id, Long userId) {
        Article article = articleMapper.selectById(id);

        if (article == null) {
            throw new BusinessException(404, "文章不存在");
        }

        if (!Objects.equals(article.getUserId(), userId)) {
            throw new BusinessException(403, "无权操作他人文章");
        }

        LambdaUpdateWrapper<Article> wrapper = new LambdaUpdateWrapper<>();

        String summary = getSummary(articleDTO.getContent());
        wrapper.eq(Article::getId, id)
                .set(Article::getTitle, articleDTO.getTitle())
                .set(Article::getContent, articleDTO.getContent())
                .set(Article::getSummary, summary)
                .set(Article::getCategoryId, articleDTO.getCategoryId())
                .set(Article::getUpdateTime, LocalDateTime.now());

        articleMapper.update(null, wrapper);
    }

    @Override
    public void deleteArticle(Long id, Long userId) {
        Article article = articleMapper.selectById(id);

        if (article == null) {
            throw new BusinessException(404, "文章不存在");
        }

        if (!Objects.equals(article.getUserId(), userId)) {
            throw new BusinessException(403, "无权操作他人文章");
        }

        articleMapper.deleteById(id);
    }

    //列表中文章摘要生成
    private String getSummary(String content) {
        String plain = content.trim().replaceAll("\\s+", " ");
        if (plain.length() <= 100) {
            return plain;
        }
        return plain.substring(0, 100) + "...";
    }
}
