package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.List;

/**
 * 动态数据响应DTO
 * 用于动态CRUD操作的响应数据
 * 
 * @author AuraBoot Framework
 * @since 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicDataResponse {

    /**
     * 业务主键
     */
    private String pid;

    /**
     * 数据内容
     */
    private Map<String, Object> data;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;

    /**
     * 创建人
     */
    private String createdBy;

    /**
     * 更新人
     */
    private String updatedBy;

    /**
     * 是否已删除
     */
    private Boolean deleted;

    /**
     * 版本号（用于乐观锁）
     */
    private Long version;

    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;

    /**
     * 关联数据
     */
    private Map<String, Object> relations;

    /**
     * 元数据信息
     */
    private MetaInfo metaInfo;

    /**
     * 元数据信息内部类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MetaInfo {
        /**
         * 页面编码
         */
        private String pageCode;

        /**
         * 表名
         */
        private String tableName;

        /**
         * 字段配置
         */
        private List<FieldConfig> fields;

        /**
         * 操作权限
         */
        private Map<String, Boolean> permissions;
    }

    /**
     * 字段配置内部类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FieldConfig {
        /**
         * 字段名
         */
        private String name;

        /**
         * 字段标签
         */
        private String label;

        /**
         * 字段类型
         */
        private String type;

        /**
         * 是否必填
         */
        private Boolean required;

        /**
         * 是否只读
         */
        private Boolean readonly;

        /**
         * 字段长度
         */
        private Integer length;

        /**
         * 默认值
         */
        private Object defaultValue;

        /**
         * 验证规则
         */
        private Map<String, Object> validation;

        /**
         * 选项配置（用于下拉框等）
         */
        private List<OptionConfig> options;
    }

    /**
     * 选项配置内部类
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptionConfig {
        /**
         * 选项值
         */
        private Object value;

        /**
         * 选项标签
         */
        private String label;

        /**
         * 是否禁用
         */
        private Boolean disabled;

        /**
         * 扩展属性
         */
        private Map<String, Object> extra;
    }
}