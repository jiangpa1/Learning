package com.jiangpa.service;

import com.jiangpa.dto.CategoryDTO;
import com.jiangpa.pojo.Category;

import java.util.List;

public interface CategoryService {
    List<Category> selectCategoryList();

    Long addCategory(CategoryDTO categoryDTO);

    void updateCategory(Long id, CategoryDTO categoryDTO);

    void deleteCategory(Long id);
}
