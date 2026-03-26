package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 字典创建请求DTO
 */
@Data
public class DictCreateRequest {

    /**
     * 字典编码
     */
    @NotBlank(message = "字典编码不能为空")
    private String code;

    /**
     * 字典名称
     */
    @NotBlank(message = "字典名称不能为空")
    private String name;

    /**
     * 字典描述
     */
    private String description;

    /**
     * 字典类型
     * SIMPLE: 简单字典 -> 映射到 DYNAMIC
     * TREE: 树形字典 -> 映射到 TREE
     */
    @NotBlank(message = "字典类型不能为空")
    private String dictType;

    /**
     * 数据源类型（保留字段，暂未使用）
     * STATIC: 静态数据（直接存储在 dict_item 表）
     * API: API数据源
     * SQL: SQL查询数据源
     */
    @NotBlank(message = "数据源类型不能为空")
    private String sourceType;

    /**
     * 字典项列表（用于STATIC类型）
     */
    private List<DictItemCreateRequest> items;

    /**
     * 数据源配置（用于API/SQL类型）
     */
    private JsonNode sourceConfig;

    /**
     * 级联配置（用于CASCADE类型）
     */
    private JsonNode cascadeConfig;

    /**
     * 缓存配置
     */
    private JsonNode cacheConfig;

    /**
     * 扩展属性
     */
    private JsonNode extendedProps;

    /**
     * 版本策略
     */
    private String versionStrategy = "latest";

    /**
     * 固定版本号
     */
    private String pinnedVersion;

    /**
     * 排序权重
     */
    private Integer sortWeight = 0;

    /**
     * 标签
     */
    private String tags;

    /**
     * 是否启用
     */
    private Boolean enabled = true;

    /**
     * 是否系统字典
     */
    private Boolean isSystem = false;

    /**
     * 版本说明
     */
    private String versionNote;

    /**
     * 插件PID（用于标识资源来源的插件）
     */
    private String pluginPid;

    /**
     * 创建者
     */
    private String createdBy;

    /**
     * 字典项创建请求
     */
    @Data
    public static class DictItemCreateRequest {
        /**
         * 字典项值
         */
        @NotBlank(message = "字典项值不能为空")
        private String value;

        /**
         * 字典项标签
         */
        @NotBlank(message = "字典项标签不能为空")
        private String label;

        /**
         * 排序顺序
         */
        private Integer sortOrder;

        /**
         * 父级值（用于级联字典）
         */
        private String parentValue;

        /**
         * 是否禁用
         */
        private Boolean disabled;

        /**
         * 扩展属性
         */
        private JsonNode extension;
    }
}