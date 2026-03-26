package com.auraboot.framework.meta.bean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 页面组件Bean
 * 用于PageDefinition的widgets字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PageWidgetBean {
    
    /**
     * 组件标识
     */
    private String key;
    
    /**
     * 组件类型
     */
    private String type;
    
    /**
     * 绑定字段
     */
    private String field;
    
    /**
     * 显示标签
     */
    private String label;
    
    /**
     * 占位符
     */
    private String placeholder;
    
    /**
     * 默认值
     */
    private Object defaultValue;
    
    /**
     * 是否必填
     */
    private Boolean required;
    
    /**
     * 是否只读
     */
    private Boolean readonly;
    
    /**
     * 是否禁用
     */
    private Boolean disabled;
    
    /**
     * 是否隐藏
     */
    private Boolean hidden;
    
    /**
     * 验证规则
     */
    private List<ValidationRule> validations;
    
    /**
     * 组件属性
     */
    private Map<String, Object> props;
    
    /**
     * 样式配置
     */
    private StyleConfig style;
    
    /**
     * 事件配置
     */
    private Map<String, Object> events;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extra;
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ValidationRule {
        /**
         * 规则类型
         */
        private String type;
        
        /**
         * 规则值
         */
        private Object value;
        
        /**
         * 错误消息
         */
        private String message;
        
        /**
         * 触发时机
         */
        private String trigger;
    }
    
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StyleConfig {
        /**
         * 宽度
         */
        private String width;
        
        /**
         * 高度
         */
        private String height;
        
        /**
         * 边距
         */
        private String margin;
        
        /**
         * 内边距
         */
        private String padding;
        
        /**
         * CSS类名
         */
        private String className;
        
        /**
         * 内联样式
         */
        private Map<String, String> inline;
    }
}