package com.auraboot.framework.category.service.impl;

import com.auraboot.framework.category.entity.Category;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.category.mapper.CategoryMapper;
import com.auraboot.framework.category.service.CategoryService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 类目服务实现类
 */
@Slf4j
@Service
public class CategoryServiceImpl extends ServiceImpl<CategoryMapper, Category> implements CategoryService {

    @Resource
    private CategoryMapper categoryMapper;

    @Override
    @Transactional
    public Category createCategory(Category category) {
        // 检查类目编码在租户下是否已存在
        if (!isCodeAvailable(category.getCode(), category.getTenantId())) {
            throw new BusinessException("类目编码在该租户下已存在: " + category.getCode());
        }

        // 设置业务ID
        if (category.getPid() == null) {
            category.setPid(UniqueIdGenerator.generate());
        }

        // 设置时间
        Instant now = Instant.now();
        category.setCreatedAt(now);
        category.setUpdatedAt(now);
        category.setDeletedFlag(false);

        // 计算层级和验证
        if (category.getParentId() != null && category.getParentId() > 0) {
            Category parent = getById(category.getParentId());
            if (parent == null) {
                throw new BusinessException("父类目不存在");
            }
            if (parent.getLevel() >= 2) {
                throw new BusinessException("不支持三级及以上类目，最多支持两级");
            }
            category.setLevel(parent.getLevel() + 1);

            // 更新父节点的 is_leaf 状态
            if (parent.isLeaf()) {
                parent.setLeaf(false);
                updateById(parent);
            }
        } else {
            category.setLevel(1);
            category.setParentId(null);
        }

        // 设置默认值
        if (category.getStatus() == null) {
            category.setStatus(StatusConstants.ACTIVE);
        }
        if (category.getSortOrder() == null) {
            category.setSortOrder(0);
        }
        if (category.getVisible() == null) {
            category.setVisible(true);
        }

        // New categories are leaf nodes by default (no children yet)
        category.setLeaf(true);

        save(category);
        return category;
    }

    @Override
    @Transactional
    public Category updateCategory(Category category) {
        Category existing = getById(category.getId());
        if (existing == null) {
            throw new BusinessException("类目不存在");
        }

        // 检查租户ID是否匹配（防止跨租户操作）
        if (!existing.getTenantId().equals(category.getTenantId())) {
            throw new BusinessException("不允许修改其他租户的类目");
        }

        // 检查类目编码是否被其他类目使用
        if (!existing.getCode().equals(category.getCode())
            && !isCodeAvailable(category.getCode(), category.getTenantId())) {
            throw new BusinessException("类目编码在该租户下已存在: " + category.getCode());
        }

        category.setUpdatedAt(Instant.now());
        updateById(category);
        return category;
    }

    @Override
    public Category findByCodeAndTenantId(String code, Long tenantId) {
        return categoryMapper.findByCodeAndTenantId(code, tenantId);
    }

    @Override
    public Category findByPid(String pid) {
        return categoryMapper.findByPid(pid);
    }

    @Override
    public List<Category> findByParentId(Long parentId) {
        return categoryMapper.findByParentId(parentId);
    }

    @Override
    public List<Category> findRootCategoriesByTenantId(Long tenantId) {
        return categoryMapper.findRootCategoriesByTenantId(tenantId);
    }

    @Override
    public List<Category> findByTenantIdAndType(Long tenantId, String categoryType) {
        return categoryMapper.findByTenantIdAndType(tenantId, categoryType);
    }

    @Override
    public Page<Category> findCategories(Long tenantId, int pageNum, int pageSize, String keyword, String categoryType, String status) {
        Page<Category> page = new Page<>(pageNum, pageSize);
        // Use Mapper SQL instead of QueryWrapper; tenant_id is handled by tenant interceptor
        return categoryMapper.findCategoriesPage(page, keyword, categoryType, status);
    }

    @Override
    @Transactional
    public boolean enableCategory(Long categoryId) {
        // Use Mapper SQL instead of UpdateWrapper
        return categoryMapper.enableCategoryById(categoryId) > 0;
    }

    @Override
    @Transactional
    public boolean disableCategory(Long categoryId) {
        // Use Mapper SQL instead of UpdateWrapper
        return categoryMapper.disableCategoryById(categoryId) > 0;
    }

    @Override
    @Transactional
    public boolean deleteCategory(Long categoryId) {
        // 检查是否有子类目
        if (hasChildren(categoryId)) {
            throw new BusinessException("存在子类目，无法删除");
        }

        Category category = getById(categoryId);
        if (category == null) {
            throw new BusinessException("类目不存在");
        }

        // 如果有父类目，检查是否需要更新父类目的 is_leaf 状态
        if (category.getParentId() != null) {
            List<Category> siblings = findByParentId(category.getParentId());
            // 如果删除后父节点没有其他子节点了，更新父节点为叶子节点
            if (siblings.size() == 1 && siblings.get(0).getId().equals(categoryId)) {
                Category parent = getById(category.getParentId());
                if (parent != null) {
                    parent.setLeaf(true);
                    updateById(parent);
                }
            }
        }

        // Use Mapper SQL instead of UpdateWrapper
        return categoryMapper.softDeleteById(categoryId) > 0;
    }

    @Override
    public boolean isCodeAvailable(String code, Long tenantId) {
        Category category = categoryMapper.findByCodeAndTenantId(code, tenantId);
        return category == null;
    }

    @Override
    public List<Map<String, Object>> getCategoryTree(Long tenantId) {
        List<Category> allCategories = categoryMapper.findAllByTenantId(tenantId);
        return buildCategoryTree(allCategories, null);
    }

    @Override
    public List<Map<String, Object>> getCategoryTreeByType(Long tenantId, String categoryType) {
        List<Category> categories = categoryMapper.findByTenantIdAndType(tenantId, categoryType);
        return buildCategoryTree(categories, null);
    }

    @Override
    @Transactional
    public boolean moveCategory(Long categoryId, Long newParentId) {
        Category category = getById(categoryId);
        if (category == null) {
            throw new BusinessException("类目不存在");
        }

        // 验证新父类目
        if (newParentId != null && newParentId > 0) {
            Category newParent = getById(newParentId);
            if (newParent == null) {
                throw new BusinessException("新父类目不存在");
            }

            // 检查租户是否相同
            if (!newParent.getTenantId().equals(category.getTenantId())) {
                throw new BusinessException("不允许移动到其他租户的类目下");
            }

            // 检查层级限制
            if (newParent.getLevel() >= 2) {
                throw new BusinessException("不支持三级及以上类目，无法移动");
            }

            // 不能移动到自己的子类目下
            List<Long> childIds = getChildCategoryIds(categoryId);
            if (childIds.contains(newParentId)) {
                throw new BusinessException("不能移动到自己的子类目下");
            }

            category.setParentId(newParentId);
            category.setLevel(newParent.getLevel() + 1);

            // 更新新父节点的 is_leaf 状态
            if (newParent.isLeaf()) {
                newParent.setLeaf(false);
                updateById(newParent);
            }
        } else {
            // 移动到根节点
            category.setParentId(null);
            category.setLevel(1);
        }

        // 检查旧父节点是否需要更新 is_leaf 状态
        Long oldParentId = getById(categoryId).getParentId();
        if (oldParentId != null) {
            List<Category> siblings = findByParentId(oldParentId);
            if (siblings.size() == 1 && siblings.get(0).getId().equals(categoryId)) {
                Category oldParent = getById(oldParentId);
                if (oldParent != null) {
                    oldParent.setLeaf(true);
                    updateById(oldParent);
                }
            }
        }

        category.setUpdatedAt(Instant.now());
        return updateById(category);
    }

    @Override
    @Transactional
    public boolean updateSortOrder(Long categoryId, Integer sortOrder) {
        // Use Mapper SQL instead of UpdateWrapper
        return categoryMapper.updateSortOrderById(categoryId, sortOrder) > 0;
    }

    @Override
    public boolean hasChildren(Long categoryId) {
        List<Category> children = findByParentId(categoryId);
        return children != null && !children.isEmpty();
    }

    @Override
    public List<Long> getChildCategoryIds(Long categoryId) {
        List<Long> childIds = new ArrayList<>();
        collectChildCategoryIds(categoryId, childIds);
        return childIds;
    }

    @Override
    public List<Category> findAllActiveCategoriesByTenantId(Long tenantId) {
        return categoryMapper.findAllActiveCategoriesByTenantId(tenantId);
    }

    @Override
    @Transactional
    public int bulkInsertCategories(List<Category> categories) {
        if (categories == null || categories.isEmpty()) {
            return 0;
        }

        Instant now = Instant.now();
        categories.forEach(category -> {
            if (category.getPid() == null) {
                category.setPid(UniqueIdGenerator.generate());
            }
            if (category.getCreatedAt() == null) {
                category.setCreatedAt(now);
            }
            if (category.getUpdatedAt() == null) {
                category.setUpdatedAt(now);
            }
            if (category.getDeletedFlag() == null) {
                category.setDeletedFlag(false);
            }
            if (category.getStatus() == null) {
                category.setStatus(StatusConstants.ACTIVE);
            }
            if (category.getVisible() == null) {
                category.setVisible(true);
            }

        });

        return saveBatch(categories) ? categories.size() : 0;
    }

    /**
     * 构建类目树（两级展开）
     */
    private List<Map<String, Object>> buildCategoryTree(List<Category> categories, Long parentId) {
        List<Map<String, Object>> tree = new ArrayList<>();

        for (Category category : categories) {
            if (Objects.equals(category.getParentId(), parentId)) {
                Map<String, Object> node = new HashMap<>();
                node.put("id", category.getId());
                node.put("pid", category.getPid());
                node.put("name", category.getName());
                node.put("code", category.getCode());
                node.put("categoryType", category.getCategoryType());
                node.put("level", category.getLevel());
                node.put("sortOrder", category.getSortOrder());
                node.put("icon", category.getIcon());
                node.put("color", category.getColor());
                node.put("visible", category.getVisible());
                node.put("status", category.getStatus());
                node.put("isLeaf", category.isLeaf());
                node.put("description", category.getDescription());

                // 递归构建子树（只支持两级）
                if (category.getLevel() < 2) {
                    List<Map<String, Object>> children = buildCategoryTree(categories, category.getId());
                    node.put("children", children);
                } else {
                    node.put("children", new ArrayList<>());
                }

                tree.add(node);
            }
        }

        return tree;
    }

    /**
     * 递归收集子类目ID
     */
    private void collectChildCategoryIds(Long parentId, List<Long> childIds) {
        List<Category> children = findByParentId(parentId);
        for (Category child : children) {
            childIds.add(child.getId());
            collectChildCategoryIds(child.getId(), childIds);
        }
    }
}
