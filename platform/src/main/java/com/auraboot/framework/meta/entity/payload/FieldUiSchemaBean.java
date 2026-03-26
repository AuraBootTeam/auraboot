package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 字段UI模式配置Bean
 * 用于FieldEntity的uiSchema字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldUiSchemaBean {
    
    /**
     * UI组件类型 (input, select, textarea, datepicker等)
     */
    private String widget;
    
    /**
     * 组件大小 (small, medium, large)
     */
    private String size;
    
    /**
     * 组件变体 (default, outline, filled)
     */
    private String variant;
    
    /**
     * 标签文本
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
     * 错误提示文本
     */
    private String errorText;
    
    /**
     * 布局配置
     */
    private LayoutConfig layout;
    
    /**
     * 样式配置
     */
    private StyleConfig style;
    
    /**
     * 交互配置
     */
    private InteractionConfig interaction;
    
    /**
     * 验证配置
     */
    private ValidationConfig validation;
    
    /**
     * 选项配置 (用于select, radio, checkbox等)
     */
    private OptionsConfig options;
    
    /**
     * 条件显示配置
     */
    private ConditionalConfig conditional;
    
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
        private Integer span; // 栅格占用列数
        private Integer offset; // 栅格左侧间隔
        private String align; // 对齐方式
        private Boolean inline; // 是否内联
        private String labelPosition; // 标签位置 (top, left, right)
        private Integer labelWidth; // 标签宽度
        private Boolean colon; // 是否显示冒号
        private Map<String, Object> responsive; // 响应式配置
    }
    
    /**
     * 样式配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StyleConfig {
        private String className;
        private Map<String, String> style;
        private String theme; // 主题
        private String color; // 颜色
        private String backgroundColor;
        private String borderColor;
        private String borderRadius;
        private String fontSize;
        private String fontWeight;
    }
    
    /**
     * 交互配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InteractionConfig {
        private Boolean disabled;
        private Boolean readonly;
        private Boolean clearable;
        private Boolean searchable;
        private Boolean multiple;
        private String trigger; // 触发方式
        private Map<String, String> events; // 事件配置
        private Map<String, Object> handlers; // 事件处理器
    }
    
    /**
     * 验证配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ValidationConfig {
        private Boolean showValidationIcon;
        private String validationTrigger; // 验证触发时机
        private Boolean validateFirst; // 是否只显示第一个错误
        private Map<String, String> messages; // 自定义错误消息
        private List<ValidationRule> rules;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ValidationRule {
            private String type;
            private Object value;
            private String message;
            private String trigger;
        }
    }
    
    /**
     * 选项配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OptionsConfig {
        private List<Option> staticOptions;
        private String dataSource; // 数据源
        private String valueField;
        private String labelField;
        private Boolean allowCustom;
        private String customPlaceholder;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class Option {
            private Object value;
            private String label;
            private Boolean disabled;
            private String description;
            private String icon;
            private String color;
            private Map<String, Object> metadata;
        }
    }
    
    /**
     * 条件显示配置
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConditionalConfig {
        private String when; // 条件表达式
        private List<ConditionalRule> rules;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class ConditionalRule {
            private String field;
            private String operator; // eq, ne, gt, lt, in, contains等
            private Object value;
            private String action; // show, hide, enable, disable
        }
    }
}