package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 字段特性配置Bean
 * 用于FieldEntity的feature字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldFeatureBean {
    
    /**
     * 是否必填
     */
    private Boolean required;
    
    /**
     * 是否只读
     */
    private Boolean readonly;
    
    /**
     * 是否隐藏
     */
    private Boolean hidden;
    
    /**
     * 是否禁用
     */
    private Boolean disabled;
    
    /**
     * 是否可搜索
     */
    private Boolean searchable;
    
    /**
     * 是否可排序
     */
    private Boolean sortable;
    
    /**
     * 是否可过滤
     */
    private Boolean filterable;
    
    /**
     * 是否可导出
     */
    private Boolean exportable;
    
    /**
     * 是否可导入
     */
    private Boolean importable;
    
    /**
     * 是否唯一
     */
    private Boolean unique;
    
    /**
     * 是否索引
     */
    private Boolean indexed;

    /**
     * Precision for DECIMAL fields (total digits)
     */
    private Integer precision;

    /**
     * Scale for DECIMAL fields (decimal places)
     */
    private Integer scale;

    /**
     * 默认值
     */
    private Object defaultValue;
    
    /**
     * 占位符文本
     */
    private String placeholder;
    
    /**
     * 帮助文本
     */
    private String helpText;
    
    /**
     * 验证规则 (legacy)
     */
    private ValidationRules validation;

    /**
     * Validation rules list for field-level validation
     */
    private List<ValidationRuleBean> validationRules;
    
    /**
     * 格式化配置
     */
    private FormatConfig format;
    
    /**
     * 权限配置
     */
    private AccessControl permission;
    
    /**
     * Virtual field type: COMPUTED_READONLY, MATERIALIZED, TRANSIENT
     */
    private String virtualType;

    /**
     * Compute expression (SQL for COMPUTED_READONLY/MATERIALIZED, SpEL for TRANSIENT)
     */
    private String computeExpression;

    /**
     * List of field codes this computed field depends on
     */
    private List<String> computeDependencies;

    /**
     * Roll-Up Summary configuration.
     * When set, this field is auto-readonly and its value is computed by aggregating child model records.
     */
    private RollUpConfig rollUp;

    /**
     * Whether this field supports user-content i18n.
     * When true, companion fields are auto-generated for supported locales during model publish:
     * {@code {code}_en_us}, {@code {code}_ja_jp}, {@code {code}_ko_kr}.
     * The primary field stores the default locale value (zh-CN).
     * Frontend reads the companion field based on the active locale with fallback to primary.
     *
     * @since 7.0.0
     */
    private Boolean i18nEnabled;

    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;
    
    /**
     * 验证规则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ValidationRules {
        private Integer minLength;
        private Integer maxLength;
        private String pattern; // 正则表达式
        private Object minValue;
        private Object maxValue;
        private List<Object> allowedValues;
        private List<Object> forbiddenValues;
        private String customValidator; // 自定义验证器名称
        private Map<String, Object> validatorParams;
    }
    
    /**
     * 格式化配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FormatConfig {
        private String dateFormat;
        private String numberFormat;
        private String currencyCode;
        private Integer decimalPlaces;
        private Boolean thousandsSeparator;
        private String prefix;
        private String suffix;
        private Map<String, Object> customFormat;
    }
    
    /**
     * 权限配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AccessControl {
        private List<String> readRoles;
        private List<String> writeRoles;
        private List<String> readUsers;
        private List<String> writeUsers;
        private String permissionExpression;
        private Map<String, Object> conditions;
    }

    /**
     * Roll-Up Summary configuration.
     * Declares that this field's value is computed by aggregating records from a child model.
     *
     * Example JSON:
     * <pre>
     * {
     *   "childModel": "order_line",
     *   "childField": "ol_amount",
     *   "childFk": "ol_order_id",
     *   "function": "sum",
     *   "childFilter": "ol_status != 'cancelled'"
     * }
     * </pre>
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RollUpConfig {
        /** Child model code (e.g. "order_line") */
        private String childModel;
        /** Field code in child model to aggregate (e.g. "ol_amount"). Not required for COUNT. */
        private String childField;
        /** FK field code in child model pointing to the parent record (e.g. "ol_order_id") */
        private String childFk;
        /** Aggregate function: SUM, COUNT, AVG, MIN, MAX (default: SUM) */
        private String function;
        /** Optional SQL WHERE fragment to filter child records (e.g. "ol_status != 'cancelled'") */
        private String childFilter;
    }
}