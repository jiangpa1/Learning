package com.jiangpa.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.jiangpa.dto.CategoryDTO;
import com.jiangpa.exception.BusinessException;
import com.jiangpa.mapper.ArticleMapper;
import com.jiangpa.mapper.CategoryMapper;
import com.jiangpa.pojo.Article;
import com.jiangpa.pojo.Category;
import com.jiangpa.service.CategoryService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CategoryServiceImpl implements CategoryService {
    private final CategoryMapper categoryMapper;
    private final ArticleMapper articleMapper;

    public CategoryServiceImpl(CategoryMapper categoryMapper, ArticleMapper articleMapper) {
        this.categoryMapper = categoryMapper;
        this.articleMapper = articleMapper;
    }

    @Override
    public List<Category> selectCategoryList() {
        return categoryMapper.selectList(new LambdaQueryWrapper<Category>().orderByAsc(Category::getId));
    }

    @Override
    public Long addCategory(CategoryDTO categoryDTO) {
        boolean exists = categoryMapper.exists(new LambdaQueryWrapper<Category>()
                .eq(Category::getName, categoryDTO.getName()));
        if(exists){
            throw new BusinessException("分类名已存在");
        }
        Category category = new Category();
        BeanUtils.copyProperties(categoryDTO,category);
        categoryMapper.insert(category);
        return category.getId();
    }

    @Override
    public void updateCategory(Long id, CategoryDTO categoryDTO) {

        if(categoryMapper.selectById(id)==null){
            throw new BusinessException(404, "分类不存在");
        }
        boolean exists = categoryMapper.exists(new LambdaQueryWrapper<Category>()
                .eq(Category::getName, categoryDTO.getName()).ne(Category::getId, id));
        if(exists){
            throw new BusinessException("分类名已存在");
        }

        LambdaUpdateWrapper<Category> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Category::getId, id)
                .set(Category::getName, categoryDTO.getName());
        categoryMapper.update(null, wrapper);
    }

    @Override
    public void deleteCategory(Long id) {
        Category category = categoryMapper.selectById(id);
        if(category==null){
            throw new BusinessException(404, "分类不存在");
        }

        Long n = articleMapper.selectCount(
                new LambdaQueryWrapper<Article>().eq(Article::getCategoryId, id)
        );
        if(n>0){
            throw new BusinessException("该分类下还有" + n + "篇有效文章，无法删除");
        }
        categoryMapper.deleteById(id);
    }
}
