package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;
import java.util.ArrayList;

/**
 * 字段使用情况信息DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MetaFieldUsageInfo {

    /**
     * 字段PID
     */
    private String fieldPid;

    /**
     * 字段键
     */
    private String code;

    /**
     * 总引用次数
     */
    private Long totalReferences;

    /**
     * 模型引用次数
     */
    private Long modelReferences;

    /**
     * 页面引用次数
     */
    private Long pageReferences;

    /**
     * 查询引用次数
     */
    private Long queryReferences;

    /**
     * 引用的模型列表
     */
    @Builder.Default
    private List<ModelReference> referencedModels = new ArrayList<>();

    /**
     * 引用的页面列表
     */
    @Builder.Default
    private List<PageReference> referencedPages = new ArrayList<>();

    /**
     * 引用的查询列表
     */
    @Builder.Default
    private List<QueryReference> referencedQueries = new ArrayList<>();

    /**
     * 字典绑定信息
     */
    @Builder.Default
    private List<DictBindingInfo> dictBindings = new ArrayList<>();

    /**
     * 是否为核心字段（被多个模型引用）
     */
    private Boolean isCoreField;

    /**
     * 最后使用时间
     */
    private Long lastUsedAt;

    /**
     * 使用频率（每天平均使用次数）
     */
    private Double usageFrequency;

    /**
     * 模型引用信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelReference {
        /**
         * 模型PID
         */
        private String modelPid;

        /**
         * 模型编码
         */
        private String modelCode;

        /**
         * 模型显示名称
         */
        private String modelDisplayName;

        /**
         * 字段在模型中的排序
         */
        private Integer fieldOrder;

        /**
         * 绑定时间
         */
        private Long boundAt;
    }

    /**
     * 页面引用信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageReference {
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
         * 引用类型（表单字段、列表列、查询条件等）
         */
        private String referenceType;

        /**
         * 引用时间
         */
        private Long referencedAt;
    }

    /**
     * 查询引用信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class QueryReference {
        /**
         * 查询PID
         */
        private String queryPid;

        /**
         * 查询名称
         */
        private String queryName;

        /**
         * 引用类型（查询字段、排序字段、过滤条件等）
         */
        private String referenceType;

        /**
         * 引用时间
         */
        private Long referencedAt;
    }

    /**
     * 字典绑定信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DictBindingInfo {
        /**
         * 字典PID
         */
        private String dictPid;

        /**
         * 字典编码
         */
        private String dictCode;

        /**
         * 字典显示名称
         */
        private String dictDisplayName;

        /**
         * 绑定时间
         */
        private Long boundAt;

        /**
         * 是否为主字典
         */
        private Boolean isPrimary;
    }

    /**
     * 添加模型引用
     * @param modelPid 模型PID
     * @param modelCode 模型编码
     * @param modelDisplayName 模型显示名称
     * @param fieldOrder 字段排序
     */
    public void addModelReference(String modelPid, String modelCode, String modelDisplayName, Integer fieldOrder) {
        if (referencedModels == null) {
            referencedModels = new ArrayList<>();
        }
        referencedModels.add(ModelReference.builder()
            .modelPid(modelPid)
            .modelCode(modelCode)
            .modelDisplayName(modelDisplayName)
            .fieldOrder(fieldOrder)
            .boundAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 添加页面引用
     * @param pagePid 页面PID
     * @param pageCode 页面编码
     * @param pageTitle 页面标题
     * @param referenceType 引用类型
     */
    public void addPageReference(String pagePid, String pageCode, String pageTitle, String referenceType) {
        if (referencedPages == null) {
            referencedPages = new ArrayList<>();
        }
        referencedPages.add(PageReference.builder()
            .pagePid(pagePid)
            .pageCode(pageCode)
            .pageTitle(pageTitle)
            .referenceType(referenceType)
            .referencedAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 添加查询引用
     * @param queryPid 查询PID
     * @param queryName 查询名称
     * @param referenceType 引用类型
     */
    public void addQueryReference(String queryPid, String queryName, String referenceType) {
        if (referencedQueries == null) {
            referencedQueries = new ArrayList<>();
        }
        referencedQueries.add(QueryReference.builder()
            .queryPid(queryPid)
            .queryName(queryName)
            .referenceType(referenceType)
            .referencedAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 添加字典绑定
     * @param dictPid 字典PID
     * @param dictCode 字典编码
     * @param dictDisplayName 字典显示名称
     * @param isPrimary 是否为主字典
     */
    public void addDictBinding(String dictPid, String dictCode, String dictDisplayName, Boolean isPrimary) {
        if (dictBindings == null) {
            dictBindings = new ArrayList<>();
        }
        dictBindings.add(DictBindingInfo.builder()
            .dictPid(dictPid)
            .dictCode(dictCode)
            .dictDisplayName(dictDisplayName)
            .isPrimary(isPrimary)
            .boundAt(System.currentTimeMillis())
            .build());
    }

    /**
     * 检查是否被使用
     * @return 是否被使用
     */
    public boolean isUsed() {
        return totalReferences != null && totalReferences > 0;
    }

    /**
     * 检查是否为核心字段
     * @return 是否为核心字段
     */
    public boolean isCoreField() {
        return Boolean.TRUE.equals(isCoreField) || (modelReferences != null && modelReferences >= 3);
    }
}