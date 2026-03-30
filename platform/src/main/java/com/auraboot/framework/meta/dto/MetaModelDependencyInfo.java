package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.ArrayList;

/**
 * 模型依赖关系信息DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaModelDependencyInfo {

    /**
     * 模型PID
     */
    private String modelPid;

    /**
     * 模型编码
     */
    private String modelCode;

    /**
     * 依赖的模型列表
     */
    @Builder.Default
    private List<ModelDependency> dependencies = new ArrayList<>();

    /**
     * 被依赖的模型列表
     */
    @Builder.Default
    private List<ModelDependency> dependents = new ArrayList<>();

    /**
     * 依赖的字段列表
     */
    @Builder.Default
    private List<FieldDependency> fieldDependencies = new ArrayList<>();

    /**
     * 依赖的页面列表
     */
    @Builder.Default
    private List<PageDependency> pageDependencies = new ArrayList<>();

    /**
     * 循环依赖检查结果
     */
    private Boolean hasCircularDependency;

    /**
     * 循环依赖路径
     */
    @Builder.Default
    private List<String> circularDependencyPath = new ArrayList<>();

    /**
     * 依赖深度
     */
    private Integer dependencyDepth;

    /**
     * 影响范围评估
     */
    private String impactScope;

    /**
     * 模型依赖信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelDependency {
        /**
         * 依赖模型PID
         */
        private String modelPid;

        /**
         * 依赖模型编码
         */
        private String modelCode;

        /**
         * 依赖模型显示名称
         */
        private String modelDisplayName;

        /**
         * 依赖类型（引用、继承、组合等）
         */
        private String dependencyType;

        /**
         * 依赖强度（强依赖、弱依赖）
         */
        private String dependencyStrength;

        /**
         * 依赖描述
         */
        private String description;

        /**
         * 创建时间
         */
        private Long createdAt;
    }

    /**
     * 字段依赖信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldDependency {
        /**
         * 字段PID
         */
        private String fieldPid;

        /**
         * 字段键
         */
        private String code;

        /**
         * 字段显示名称
         */
        private String fieldDisplayName;

        /**
         * 依赖类型
         */
        private String dependencyType;

        /**
         * 字段排序
         */
        private Integer fieldOrder;

        /**
         * 是否为必需字段
         */
        private Boolean isRequired;

        /**
         * 创建时间
         */
        private Long createdAt;
    }

    /**
     * 页面依赖信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageDependency {
        /**
         * 页面PID
         */
        private String pagePid;

        /**
         * 页面编码
         */
        private String pageCode;

        /**
         * 页面标题
         */
        private String pageTitle;

        /**
         * 依赖类型
         */
        private String dependencyType;

        /**
         * Page kind
         */
        private String kind;

        /**
         * 创建时间
         */
        private Long createdAt;
    }

    /**
     * 添加模型依赖
     * @param modelPid 依赖模型PID
     * @param modelCode 依赖模型编码
     * @param modelDisplayName 依赖模型显示名称
     * @param dependencyType 依赖类型
     * @param dependencyStrength 依赖强度
     * @param description 依赖描述
     */
    public void addDependency(String modelPid, String modelCode, String modelDisplayName, String dependencyType, String dependencyStrength, String description) {
        if (dependencies == null) {
            dependencies = new ArrayList<>();
        }
        dependencies.add(ModelDependency.builder()
            .modelPid(modelPid)
            .modelCode(modelCode)
            .modelDisplayName(modelDisplayName)
            .dependencyType(dependencyType)
            .dependencyStrength(dependencyStrength)
            .description(description)
            .createdAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 添加被依赖模型
     * @param modelPid 被依赖模型PID
     * @param modelCode 被依赖模型编码
     * @param modelDisplayName 被依赖模型显示名称
     * @param dependencyType 依赖类型
     * @param dependencyStrength 依赖强度
     * @param description 依赖描述
     */
    public void addDependent(String modelPid, String modelCode, String modelDisplayName, String dependencyType, String dependencyStrength, String description) {
        if (dependents == null) {
            dependents = new ArrayList<>();
        }
        dependents.add(ModelDependency.builder()
            .modelPid(modelPid)
            .modelCode(modelCode)
            .modelDisplayName(modelDisplayName)
            .dependencyType(dependencyType)
            .dependencyStrength(dependencyStrength)
            .description(description)
            .createdAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 添加字段依赖
     * @param fieldPid 字段PID
     * @param code 字段键
     * @param fieldDisplayName 字段显示名称
     * @param dependencyType 依赖类型
     * @param fieldOrder 字段排序
     * @param isRequired 是否必需
     */
    public void addFieldDependency(String fieldPid, String code, String fieldDisplayName, String dependencyType, Integer fieldOrder, Boolean isRequired) {
        if (fieldDependencies == null) {
            fieldDependencies = new ArrayList<>();
        }
        fieldDependencies.add(FieldDependency.builder()
            .fieldPid(fieldPid)
            .code(code)
            .fieldDisplayName(fieldDisplayName)
            .dependencyType(dependencyType)
            .fieldOrder(fieldOrder)
            .isRequired(isRequired)
            .createdAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 添加页面依赖
     * @param pagePid 页面PID
     * @param pageCode 页面编码
     * @param pageTitle 页面标题
     * @param dependencyType 依赖类型
     * @param kind page kind
     */
    public void addPageDependency(String pagePid, String pageCode, String pageTitle, String dependencyType, String kind) {
        if (pageDependencies == null) {
            pageDependencies = new ArrayList<>();
        }
        pageDependencies.add(PageDependency.builder()
            .pagePid(pagePid)
            .pageCode(pageCode)
            .pageTitle(pageTitle)
            .dependencyType(dependencyType)
            .kind(kind)
            .createdAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 检查是否有依赖
     * @return 是否有依赖
     */
    public boolean hasDependencies() {
        return (dependencies != null && !dependencies.isEmpty()) ||
               (fieldDependencies != null && !fieldDependencies.isEmpty()) ||
               (pageDependencies != null && !pageDependencies.isEmpty());
    }

    /**
     * 检查是否被依赖
     * @return 是否被依赖
     */
    public boolean hasDependents() {
        return dependents != null && !dependents.isEmpty();
    }

    /**
     * 获取依赖总数
     * @return 依赖总数
     */
    public int getTotalDependencies() {
        int count = 0;
        if (dependencies != null) count += dependencies.size();
        if (fieldDependencies != null) count += fieldDependencies.size();
        if (pageDependencies != null) count += pageDependencies.size();
        return count;
    }

    /**
     * 获取被依赖总数
     * @return 被依赖总数
     */
    public int getTotalDependents() {
        return dependents != null ? dependents.size() : 0;
    }

    /**
     * 检查是否为核心模型（被多个模型依赖）
     * @return 是否为核心模型
     */
    public boolean isCoreModel() {
        return getTotalDependents() >= 3;
    }
}