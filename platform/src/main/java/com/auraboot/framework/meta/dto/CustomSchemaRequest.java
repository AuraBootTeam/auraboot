package com.auraboot.framework.meta.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * 自定义Schema请求DTO
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
public class CustomSchemaRequest {
    
    /**
     * 租户ID
     */
    @NotNull(message = "租户ID不能为空")
    private Long tenantId;
    
    /**
     * Schema名称
     */
    @NotBlank(message = "Schema名称不能为空")
    private String schemaName;
    
    /**
     * Schema类型
     */
    @NotBlank(message = "Schema类型不能为空")
    private String schemaType;
    
    /**
     * 基础实体ID（可选）
     */
    private Long baseEntityId;
    
    /**
     * 自定义字段列表
     */
    private List<CustomFieldConfig> customFields;
    
    /**
     * 布局配置
     */
    private LayoutConfig layoutConfig;
    
    /**
     * 验证规则
     */
    private List<ValidationRule> validationRules;
    
    /**
     * 扩展属性
     */
    private Map<String, Object> extensions;
    
    /**
     * 描述
     */
    private String description;
    
    // getters and setters
    public Long getTenantId() {
        return tenantId;
    }
    
    public void setTenantId(Long tenantId) {
        this.tenantId = tenantId;
    }
    
    public String getSchemaName() {
        return schemaName;
    }
    
    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }
    
    public String getSchemaType() {
        return schemaType;
    }
    
    public void setSchemaType(String schemaType) {
        this.schemaType = schemaType;
    }
    
    public Long getBaseEntityId() {
        return baseEntityId;
    }
    
    public void setBaseEntityId(Long baseEntityId) {
        this.baseEntityId = baseEntityId;
    }
    
    public List<CustomFieldConfig> getCustomFields() {
        return customFields;
    }
    
    public void setCustomFields(List<CustomFieldConfig> customFields) {
        this.customFields = customFields;
    }
    
    public LayoutConfig getLayoutConfig() {
        return layoutConfig;
    }
    
    public void setLayoutConfig(LayoutConfig layoutConfig) {
        this.layoutConfig = layoutConfig;
    }
    
    public List<ValidationRule> getValidationRules() {
        return validationRules;
    }
    
    public void setValidationRules(List<ValidationRule> validationRules) {
        this.validationRules = validationRules;
    }
    
    public Map<String, Object> getExtensions() {
        return extensions;
    }
    
    public void setExtensions(Map<String, Object> extensions) {
        this.extensions = extensions;
    }
    
    public String getDescription() {
        return description;
    }
    
    public void setDescription(String description) {
        this.description = description;
    }
    
    /**
     * 自定义字段配置
     */
    public static class CustomFieldConfig {
        private String fieldCode;
        private String fieldName;
        private String fieldType;
        private boolean required;
        private String defaultValue;
        private Map<String, Object> properties;
        
        // getters and setters
        public String getFieldCode() {
            return fieldCode;
        }
        
        public void setFieldCode(String fieldCode) {
            this.fieldCode = fieldCode;
        }
        
        public String getFieldName() {
            return fieldName;
        }
        
        public void setFieldName(String fieldName) {
            this.fieldName = fieldName;
        }
        
        public String getFieldType() {
            return fieldType;
        }
        
        public void setFieldType(String fieldType) {
            this.fieldType = fieldType;
        }
        
        public boolean isRequired() {
            return required;
        }
        
        public void setRequired(boolean required) {
            this.required = required;
        }
        
        public String getDefaultValue() {
            return defaultValue;
        }
        
        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue;
        }
        
        public Map<String, Object> getProperties() {
            return properties;
        }
        
        public void setProperties(Map<String, Object> properties) {
            this.properties = properties;
        }
    }
    
    /**
     * 布局配置
     */
    public static class LayoutConfig {
        private String layoutType;
        private int columns;
        private Map<String, Object> styles;
        private List<String> fieldOrder;
        
        // getters and setters
        public String getLayoutType() {
            return layoutType;
        }
        
        public void setLayoutType(String layoutType) {
            this.layoutType = layoutType;
        }
        
        public int getColumns() {
            return columns;
        }
        
        public void setColumns(int columns) {
            this.columns = columns;
        }
        
        public Map<String, Object> getStyles() {
            return styles;
        }
        
        public void setStyles(Map<String, Object> styles) {
            this.styles = styles;
        }
        
        public List<String> getFieldOrder() {
            return fieldOrder;
        }
        
        public void setFieldOrder(List<String> fieldOrder) {
            this.fieldOrder = fieldOrder;
        }
    }
    
    /**
     * 验证规则
     */
    public static class ValidationRule {
        private String fieldCode;
        private String ruleType;
        private String ruleValue;
        private String errorMessage;
        
        // getters and setters
        public String getFieldCode() {
            return fieldCode;
        }
        
        public void setFieldCode(String fieldCode) {
            this.fieldCode = fieldCode;
        }
        
        public String getRuleType() {
            return ruleType;
        }
        
        public void setRuleType(String ruleType) {
            this.ruleType = ruleType;
        }
        
        public String getRuleValue() {
            return ruleValue;
        }
        
        public void setRuleValue(String ruleValue) {
            this.ruleValue = ruleValue;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }
}