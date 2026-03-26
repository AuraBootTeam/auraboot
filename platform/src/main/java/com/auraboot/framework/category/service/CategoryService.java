package com.auraboot.framework.category.service;

import com.auraboot.framework.category.entity.Category;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * 类目服务接口
 */
public interface CategoryService extends IService<Category> {

    /**
     * 创建类目
     */
    Category createCategory(Category category);

    /**
     * 更新类目信息
     */
    Category updateCategory(Category category);

    /**
     * 根据类目编码和租户ID查询类目
     */
    Category findByCodeAndTenantId(String code, Long tenantId);

    /**
     * 根据类目PID查询类目
     */
    Category findByPid(String pid);

    /**
     * 根据父类目ID查询子类目列表
     */
    List<Category> findByParentId(Long parentId);

    /**
     * 根据租户ID查询根类目列表
     */
    List<Category> findRootCategoriesByTenantId(Long tenantId);

    /**
     * 根据租户ID和类型查询类目列表
     */
    List<Category> findByTenantIdAndType(Long tenantId, String categoryType);

    /**
     * 分页查询类目列表
     */
    Page<Category> findCategories(Long tenantId, int pageNum, int pageSize, String keyword, String categoryType, String status);

    /**
     * 启用类目
     */
    boolean enableCategory(Long categoryId);

    /**
     * 禁用类目
     */
    boolean disableCategory(Long categoryId);

    /**
     * 删除类目(逻辑删除)
     */
    boolean deleteCategory(Long categoryId);

    /**
     * 检查类目编码在租户下是否可用
     */
    boolean isCodeAvailable(String code, Long tenantId);

    /**
     * 获取类目树结构(两级展开)
     */
    List<Map<String, Object>> getCategoryTree(Long tenantId);

    /**
     * 获取指定类型的类目树
     */
    List<Map<String, Object>> getCategoryTreeByType(Long tenantId, String categoryType);

    /**
     * 移动类目到新的父类目下
     */
    boolean moveCategory(Long categoryId, Long newParentId);

    /**
     * 更新类目排序
     */
    boolean updateSortOrder(Long categoryId, Integer sortOrder);

    /**
     * 检查类目是否有子类目
     */
    boolean hasChildren(Long categoryId);

    /**
     * 获取类目的所有子类目ID列表
     */
    List<Long> getChildCategoryIds(Long categoryId);

    /**
     * 获取所有激活状态的类目
     */
    List<Category> findAllActiveCategoriesByTenantId(Long tenantId);

    /**
     * 批量创建类目
     */
    int bulkInsertCategories(List<Category> categories);
}
