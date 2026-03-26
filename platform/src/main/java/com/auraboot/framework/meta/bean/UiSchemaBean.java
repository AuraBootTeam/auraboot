package com.auraboot.framework.meta.bean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * UI Schema Bean
 * 用于DictField的uiSchema字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiSchemaBean {
    
    /**
     * 组件类型
     */
    private String component;
    
    /**
     * 显示标签
     */
    private String label;
    
    /**
     * 占位符文本
     */
    private String placeholder;
    
    /**
     * 帮助文本
     */
    private String helpText;
    
    /**
     * 错误提示
     */
    private String errorText;
    
    /**
     * 组件尺寸
     */
    private String size; // small, medium, large
    
    /**
     * 组件变体
     */
    private String variant; // default, outline, filled
    
    /**
     * 是否禁用
     */
    private Boolean disabled;
    
    /**
     * 是否只读
     */
    private Boolean readonly;
    
    /**
     * 是否隐藏
     */
    private Boolean hidden;
    
    /**
     * 布局配置
     */
    private LayoutConfig layout;
    
    /**
     * 样式配置
     */
    private StyleConfig style;
    
    /**
     * 验证配置
     */
    private ValidationConfig validation;
    
    /**
     * 数据源配置
     */
    private DataSourceConfig dataSource;
    
    /**
     * 事件配置
     */
    private Map<String, Object> events;
    
    /**
     * 组件属性
     */
    private Map<String, Object> props;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;
    
    /**
     * 布局配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LayoutConfig {
        private Integer span; // 栅格占位格数
        private Integer offset; // 栅格左侧的间隔格数
        private Integer order; // 栅格顺序
        private String width;
        private String height;
        private String margin;
        private String padding;
    }
    
    /**
     * 样式配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StyleConfig {
        private String className;
        private Map<String, String> css;
        private String theme;
        private String color;
        private String backgroundColor;
        private String borderColor;
        private String fontSize;
        private String fontWeight;
    }
    
    /**
     * 验证配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ValidationConfig {
        private Boolean required;
        private String pattern;
        private Integer minLength;
        private Integer maxLength;
        private Object minValue;
        private Object maxValue;
        private String customValidator;
        private List<String> rules;
    }
    
    /**
     * 数据源配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DataSourceConfig {
        private String type; // static, api, function
        private String url;
        private String method;
        private Map<String, Object> params;
        private List<Option> options;
        private String valueField;
        private String labelField;
        private Boolean multiple;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class Option {
            private Object value;
            private String label;
            private String description;
            private Boolean disabled;
            private Map<String, Object> extra;
        }
    }
}