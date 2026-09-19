package com.jiangpa.controller;

import com.jiangpa.annotation.RequireRole;
import com.jiangpa.common.Result;
import com.jiangpa.dto.CategoryDTO;
import com.jiangpa.service.CategoryService;
import com.jiangpa.config.Knife4jConfig;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

@RestController
@RequestMapping("/category")
// ★ 必须声明在【接口级】，原因见 Knife4jConfig（Knife4j 不读根级 security）
@SecurityRequirement(name = Knife4jConfig.SECURITY_SCHEME_NAME)
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping("/list")
    public Result<?> selectCategoryList(){
        return Result.success(categoryService.selectCategoryList());
    }

    @RequireRole(1)
    @PostMapping
    public Result<?> addCategory(@Valid @RequestBody CategoryDTO categoryDTO){
        return Result.success(categoryService.addCategory(categoryDTO));
    }

    @RequireRole(1)
    @PutMapping("/{id}")
    public Result<?> updateCategory(@PathVariable Long id,@Valid @RequestBody CategoryDTO categoryDTO){
        categoryService.updateCategory(id, categoryDTO);
        return Result.success();
    }

    @RequireRole(1)
    @DeleteMapping("/{id}")
    public Result<?> deleteCategory(@PathVariable Long id){
        categoryService.deleteCategory(id);
        return Result.success();
    }
}
