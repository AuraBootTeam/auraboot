package com.auraboot.framework.meta.bean;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 规则Schema Bean
 * 用于DictField的ruleSchema字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuleSchemaBean {
    
    /**
     * 验证规则
     */
    private List<ValidationRule> validationRules;
    
    /**
     * 业务规则
     */
    private List<BusinessRule> businessRules;
    
    /**
     * 权限规则
     */
    private List<AccessRule> accessRules;
    
    /**
     * 转换规则
     */
    private List<TransformRule> transformRules;
    
    /**
     * 触发器规则
     */
    private List<TriggerRule> triggerRules;
    
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
    public static class ValidationRule {
        private String name;
        private String type; // REQUIRED, PATTERN, RANGE, CUSTOM
        private String message;
        private Object value;
        private String expression;
        private Integer priority;
        private Boolean enabled;
    }
    
    /**
     * 业务规则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BusinessRule {
        private String name;
        private String description;
        private String condition;
        private String action;
        private Map<String, Object> parameters;
        private Integer priority;
        private Boolean enabled;
        private String errorMessage;
    }
    
    /**
     * 权限规则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AccessRule {
        private String operation; // CREATE, READ, UPDATE, DELETE
        private String condition;
        private List<String> roles;
        private List<String> permissions;
        private String effect; // ALLOW, DENY
        private Integer priority;
        private Boolean enabled;
    }
    
    /**
     * 转换规则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TransformRule {
        private String name;
        private String type; // FORMAT, CALCULATE, LOOKUP, CUSTOM
        private String expression;
        private Map<String, Object> parameters;
        private String targetField;
        private String trigger; // BEFORE_SAVE, AFTER_SAVE, ON_CHANGE
        private Boolean enabled;
    }
    
    /**
     * 触发器规则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TriggerRule {
        private String name;
        private String event; // INSERT, UPDATE, DELETE, SELECT
        private String timing; // BEFORE, AFTER
        private String condition;
        private String action;
        private Map<String, Object> parameters;
        private Boolean async;
        private Integer retryCount;
        private Boolean enabled;
    }
}