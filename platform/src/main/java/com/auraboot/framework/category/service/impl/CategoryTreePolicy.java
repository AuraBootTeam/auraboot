package com.auraboot.framework.category.service.impl;

import com.auraboot.framework.category.config.CategoryProperties;
import com.auraboot.framework.category.entity.Category;
import com.auraboot.framework.exception.BusinessException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CategoryTreePolicy {

    static final int DEFAULT_MAX_LEVEL = 2;

    private final CategoryProperties properties;

    public CategoryTreePolicy(CategoryProperties properties) {
        this.properties = properties;
    }

    public void assertChildAllowed(Category parent, String childCategoryType) {
        int maxLevel = resolveMaxLevel(childCategoryType, parent == null ? null : parent.getCategoryType());
        int parentLevel = parent == null || parent.getLevel() == null ? 0 : parent.getLevel();
        if (parentLevel >= maxLevel) {
            throw new BusinessException("category_type=" + displayType(childCategoryType)
                    + " 最多支持 " + maxLevel + " 级");
        }
    }

    public int resolveMaxLevel(String categoryType) {
        return resolveMaxLevel(categoryType, null);
    }

    public String rootPath(String pid) {
        return "/" + requirePid(pid);
    }

    public String childPath(Category parent, String childPid) {
        if (parent == null) {
            return rootPath(childPid);
        }
        String parentPath = StringUtils.hasText(parent.getMaterializedPath())
                ? parent.getMaterializedPath()
                : rootPath(parent.getPid());
        return stripTrailingSlash(parentPath) + "/" + requirePid(childPid);
    }

    private int resolveMaxLevel(String categoryType, String parentCategoryType) {
        String effectiveType = StringUtils.hasText(categoryType) ? categoryType : parentCategoryType;
        Integer configured = StringUtils.hasText(effectiveType)
                ? properties.getMaxLevel().get(effectiveType)
                : null;
        if (configured != null) {
            return configured;
        }
        return properties.getMaxLevel().getOrDefault("default", DEFAULT_MAX_LEVEL);
    }

    private String requirePid(String pid) {
        if (!StringUtils.hasText(pid)) {
            throw new BusinessException("Category pid is required to build materialized path");
        }
        return pid;
    }

    private String displayType(String categoryType) {
        return StringUtils.hasText(categoryType) ? categoryType : "default";
    }

    private String stripTrailingSlash(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }
}
