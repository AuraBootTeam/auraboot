package com.auraboot.framework.meta.entity.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 字段规则模式配置Bean
 * 用于FieldEntity的ruleSchema字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldRuleSchemaBean {
    
    /**
     * 验证规则列表
     */
    private List<ValidationRule> validationRules;
    
    /**
     * 业务规则列表
     */
    private List<BusinessRule> businessRules;
    
    /**
     * 数据转换规则
     */
    private List<TransformRule> transformRules;
    
    /**
     * 权限规则
     */
    private PermissionRule permissionRule;
    
    /**
     * 审计规则
     */
    private AuditRule auditRule;
    
    /**
     * 生命周期规则
     */
    private LifecycleRule lifecycleRule;
    
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
        private String type; // required, pattern, range, custom等
        private Object value;
        private String message;
        private String trigger; // change, blur, submit
        private Integer priority;
        private Boolean enabled;
        private String condition; // 条件表达式
        private Map<String, Object> parameters;
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
        private String ruleType; // calculation, constraint, workflow等
        private String expression; // 规则表达式
        private String action; // 执行动作
        private Integer priority;
        private Boolean enabled;
        private String trigger; // 触发条件
        private List<String> dependencies; // 依赖字段
        private Map<String, Object> parameters;
        private String errorHandling; // 错误处理策略
    }
    
    /**
     * 数据转换规则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TransformRule {
        private String name;
        private String transformType; // format, convert, calculate等
        private String sourceFormat;
        private String targetFormat;
        private String expression;
        private Boolean reversible;
        private String trigger; // input, output, both
        private Map<String, Object> options;
    }
    
    /**
     * 权限规则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PermissionRule {
        private Boolean readable;
        private Boolean writable;
        private Boolean deletable;
        private List<String> readRoles;
        private List<String> writeRoles;
        private List<String> deleteRoles;
        private String readCondition;
        private String writeCondition;
        private String deleteCondition;
        private FieldLevelSecurity fieldSecurity;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class FieldLevelSecurity {
            private Boolean maskSensitive;
            private String maskPattern;
            private List<String> exemptRoles;
            private String encryptionLevel; // none, basic, advanced
            private Boolean auditAccess;
        }
    }
    
    /**
     * 审计规则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuditRule {
        private Boolean enableAudit;
        private Boolean trackChanges;
        private Boolean trackAccess;
        private List<String> auditEvents; // create, update, delete, read
        private String retentionPeriod;
        private Boolean compressOldRecords;
        private Map<String, Object> auditOptions;
    }
    
    /**
     * 生命周期规则
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LifecycleRule {
        private List<LifecycleStage> stages;
        private String currentStage;
        private Boolean autoTransition;
        private Map<String, Object> transitionRules;
        
        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public static class LifecycleStage {
            private String name;
            private String description;
            private List<String> allowedActions;
            private List<String> requiredFields;
            private String transitionCondition;
            private String nextStage;
            private Map<String, Object> stageOptions;
        }
    }
}