package com.auraboot.framework.category.controller;

import io.swagger.v3.oas.annotations.tags.Tag;

import com.auraboot.framework.application.annotation.CurrentUserId;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.category.entity.Category;
import com.auraboot.framework.category.service.CategoryService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

/**
 * 类目管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/categories")
@Tag(name = "Categories", description = "Category tree management")
public class CategoryController {

    @Autowired
    private CategoryService categoryService;

    /**
     * 分页查询类目列表
     */
    @GetMapping
    public ApiResponse<Page<Category>> getCategories(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryType,
            @RequestParam(required = false) String status) {

        Long tenantId = MetaContext.getCurrentTenantId();
        Page<Category> categories = categoryService.findCategories(
                tenantId, pageNum, pageSize, keyword, categoryType, status);
        return ApiResponse.success(categories);
    }

    /**
     * 获取类目树结构
     */
    @GetMapping("/tree")
    public ApiResponse<List<Map<String, Object>>> getCategoryTree(
            @RequestParam String categoryType) {

        Long tenantId = MetaContext.getCurrentTenantId();
        List<Map<String, Object>> tree;

        tree = categoryService.getCategoryTreeByType(tenantId, categoryType);

        return ApiResponse.success(tree);
    }

    /**
     * 根据ID获取类目详情
     */
    @GetMapping("/{pid}")
    public ApiResponse<Category> getCategory(@PathVariable String pid) {
        Category category = findByPidOrThrow(pid);
        return ApiResponse.success(category);
    }

    /**
     * 创建类目
     */
    @PostMapping
    @RequirePermission(MetaPermission.CATEGORY_MANAGE)
    public ApiResponse<Category> createCategory(
            @RequestBody Category category,
            @CurrentUserId Long userId) {

        // Sanitize client-supplied fields that should be server-controlled
        category.setId(null);
        category.setDeletedFlag(null);
        category.setCreatedAt(null);
        category.setUpdatedAt(null);

        Long tenantId = MetaContext.getCurrentTenantId();
        category.setTenantId(tenantId);
        category.setCreatedBy(userId);
        category.setUpdatedBy(userId);

        Category created = categoryService.createCategory(category);
        return ApiResponse.success(created);
    }

    /**
     * 更新类目
     */
    @PutMapping("/{pid}")
    @RequirePermission(MetaPermission.CATEGORY_MANAGE)
    public ApiResponse<Category> updateCategory(
            @PathVariable String pid,
            @RequestBody Category category,
            @CurrentUserId Long userId) {

        Category existingCategory = findByPidOrThrow(pid);

        Long tenantId = MetaContext.getCurrentTenantId();
        category.setId(existingCategory.getId());
        category.setTenantId(tenantId);
        category.setUpdatedBy(userId);

        Category updated = categoryService.updateCategory(category);
        return ApiResponse.success(updated);
    }

    /**
     * 删除类目
     */
    @DeleteMapping("/{pid}")
    @RequirePermission(MetaPermission.CATEGORY_MANAGE)
    public ApiResponse<Boolean> deleteCategory(@PathVariable String pid) {
        Category category = findByPidOrThrow(pid);
        boolean result = categoryService.deleteCategory(category.getId());
        return ApiResponse.success(result);
    }

    /**
     * 启用类目
     */
    @PutMapping("/{pid}/enable")
    @RequirePermission(MetaPermission.CATEGORY_MANAGE)
    public ApiResponse<Boolean> enableCategory(@PathVariable String pid) {
        Category category = findByPidOrThrow(pid);
        boolean result = categoryService.enableCategory(category.getId());
        return ApiResponse.success(result);
    }

    /**
     * 禁用类目
     */
    @PutMapping("/{pid}/disable")
    @RequirePermission(MetaPermission.CATEGORY_MANAGE)
    public ApiResponse<Boolean> disableCategory(@PathVariable String pid) {
        Category category = findByPidOrThrow(pid);
        boolean result = categoryService.disableCategory(category.getId());
        return ApiResponse.success(result);
    }

    /**
     * 移动类目到新的父类目下
     */
    @PutMapping("/{pid}/move")
    @RequirePermission(MetaPermission.CATEGORY_MANAGE)
    public ApiResponse<Boolean> moveCategory(
            @PathVariable String pid,
            @RequestParam(required = false) Long newParentId) {

        Category category = findByPidOrThrow(pid);
        boolean result = categoryService.moveCategory(category.getId(), newParentId);
        return ApiResponse.success(result);
    }

    /**
     * 更新类目排序
     */
    @PutMapping("/{pid}/sort")
    @RequirePermission(MetaPermission.CATEGORY_MANAGE)
    public ApiResponse<Boolean> updateSortOrder(
            @PathVariable String pid,
            @RequestParam Integer sortOrder) {

        Category category = findByPidOrThrow(pid);
        boolean result = categoryService.updateSortOrder(category.getId(), sortOrder);
        return ApiResponse.success(result);
    }

    /**
     * 检查类目编码是否可用
     */
    @GetMapping("/check-code")
    public ApiResponse<Boolean> checkCodeAvailable(
            @RequestParam String code) {

        Long tenantId = MetaContext.getCurrentTenantId();
        boolean available = categoryService.isCodeAvailable(code, tenantId);
        return ApiResponse.success(available);
    }

    /**
     * 获取根类目列表
     */
    @GetMapping("/root")
    public ApiResponse<List<Category>> getRootCategories() {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Category> categories = categoryService.findRootCategoriesByTenantId(tenantId);
        return ApiResponse.success(categories);
    }

    /**
     * 获取激活状态的类目列表
     */
    @GetMapping("/active")
    public ApiResponse<List<Category>> getActiveCategories() {
        Long tenantId = MetaContext.getCurrentTenantId();
        List<Category> categories = categoryService.findAllActiveCategoriesByTenantId(tenantId);
        return ApiResponse.success(categories);
    }

    /**
     * Look up category by pid and verify it belongs to the current tenant.
     * Throws generic "Resource not found" to avoid leaking tenant information.
     */
    private Category findByPidOrThrow(String pid) {
        Category category = categoryService.findByPid(pid);
        if (category == null) {
            throw new RootUnCheckedException(BadParam, "Resource not found");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (!category.getTenantId().equals(tenantId)) {
            throw new RootUnCheckedException(BadParam, "Resource not found");
        }
        return category;
    }
}
