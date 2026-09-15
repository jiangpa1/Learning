package com.jiangpa.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jiangpa.pojo.Article;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ArticleMapper extends BaseMapper<Article> {
}
