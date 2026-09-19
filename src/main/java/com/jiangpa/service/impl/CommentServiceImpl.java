package com.jiangpa.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.jiangpa.common.PageResult;
import com.jiangpa.dto.CommentDTO;
import com.jiangpa.dto.PageQueryDTO;
import com.jiangpa.exception.BusinessException;
import com.jiangpa.mapper.ArticleMapper;
import com.jiangpa.mapper.CommentMapper;
import com.jiangpa.mapper.UserMapper;
import com.jiangpa.pojo.Comment;
import com.jiangpa.pojo.User;
import com.jiangpa.service.CommentService;
import com.jiangpa.vo.CommentVO;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class CommentServiceImpl implements CommentService {
    private final CommentMapper commentMapper;
    private final ArticleMapper articleMapper;
    private final UserMapper userMapper;

    public CommentServiceImpl(CommentMapper commentMapper, ArticleMapper articleMapper, UserMapper userMapper) {
        this.commentMapper = commentMapper;
        this.articleMapper = articleMapper;
        this.userMapper = userMapper;
    }

    @Override
    public Long addComment(CommentDTO commentDTO, Long userId) {
        if(articleMapper.selectById(commentDTO.getArticleId()) == null){
            throw new BusinessException(404, "文章不存在");
        }
        Comment comment = new Comment();
        BeanUtils.copyProperties(commentDTO,comment);
        comment.setUserId(userId);
        comment.setCreateTime(LocalDateTime.now());

        commentMapper.insert(comment);
        return comment.getId();
    }

    @Override
    public PageResult<CommentVO> selectCommentList(Long articleId, PageQueryDTO pageQueryDTO) {
        if(articleMapper.selectById(articleId) == null){
            throw new BusinessException(404, "文章不存在");
        }

        Page<Comment> page = new Page<>(pageQueryDTO.getPageNum(), pageQueryDTO.getPageSize());

        LambdaQueryWrapper<Comment> wrapper = new LambdaQueryWrapper<>();
        wrapper.select(Comment::getId, Comment::getArticleId, Comment::getUserId,
                Comment::getContent, Comment::getCreateTime)
                .eq(Comment::getArticleId, articleId)
                .orderByDesc(Comment::getCreateTime);

        IPage<Comment> result = commentMapper.selectPage(page, wrapper);

        List<Long> userIds = result.getRecords().stream()
                .map(Comment::getUserId)
                .distinct()
                .toList();

        Map<Long, String> nicknameMap = userIds.isEmpty()
                ? new HashMap<>()
                : userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(
                        User::getId, u -> u.getNickname() != null ? u.getNickname() : "默认昵称",
                        (oldVal,  newVal) -> newVal
                ));

        List<CommentVO> voList = result.getRecords().stream()
                .map(comment -> {
                    CommentVO vo = new CommentVO();
                    BeanUtils.copyProperties(comment, vo);
                    vo.setAuthorNickname(nicknameMap.getOrDefault(comment.getUserId(), "未知"));
                    return vo;
                }).toList();

        PageResult<CommentVO> pageResult = new PageResult<>();
        pageResult.setTotal(result.getTotal());
        pageResult.setPageNum(result.getCurrent());
        pageResult.setPageSize(result.getSize());
        pageResult.setPages(result.getPages());
        pageResult.setRecords(voList);

        return pageResult;
    }

    @Override
    public void deleteComment(Long id, Long userId) {
        Comment comment = commentMapper.selectById(id);

        if(comment == null){
            throw new BusinessException(404, "评论不存在");
        }

        if(!Objects.equals(comment.getUserId(), userId)){
            throw new BusinessException(403, "无权删除他人评论");
        }

        commentMapper.deleteById(id);
    }


}
