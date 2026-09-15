package com.jiangpa.controller;

import com.jiangpa.common.Result;
import com.jiangpa.dto.CategoryDTO;
import com.jiangpa.service.CategoryService;
import org.springframework.beans.factory.annotation.Autowired;


import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/category")
public class CategoryController {
    @Autowired
    private CategoryService categoryService;

    @GetMapping("/list")
    public Result<?> selectCategoryList(){
        return Result.success(categoryService.selectCategoryList());
    }

    @PostMapping
    public Result<?> addCategory(@Valid @RequestBody CategoryDTO categoryDTO){
        return Result.success(categoryService.addCategory(categoryDTO));
    }

    @PutMapping("/{id}")
    public Result<?> updateCategory(@PathVariable Long id,@Valid @RequestBody CategoryDTO categoryDTO){
        categoryService.updateCategory(id, categoryDTO);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<?> deleteCategory(@PathVariable Long id){
        categoryService.deleteCategory(id);
        return Result.success();
    }
}
