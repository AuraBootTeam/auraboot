package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 字典字段响应DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictFieldResponse extends AbstractResponse {
    
    /**
     * 字段键
     */
    private String code;
    
    /**
     * 数据类型
     */
    private String dataType;
    
    /**
     * 数据源ID
     */
    private String dataSourceId;
    
    /**
     * 字段特性
     */
    private String feature;
    
    /**
     * 引用目标
     */
    private String refTarget;
    
    /**
     * 自定义属性
     */
    private Map<String, Object> adhocAttr;
    
    /**
     * PII分类
     */
    private String piiClass;
    
    /**
     * 索引提示
     */
    private String indexHint;
    
    /**
     * UI模式
     */
    private Map<String, Object> uiSchema;
    
    /**
     * 查询模式
     */
    private Map<String, Object> querySchema;
    
    /**
     * 规则模式
     */
    private Map<String, Object> ruleSchema;
    
    /**
     * UI元数据
     */
    private Map<String, Object> uiMeta;
    
    /**
     * 是否建立索引
     */
    private Boolean isIndexed;
    
    /**
     * 默认值
     */
    private String defaultValue;
    
    /**
     * 验证规则
     */
    private Map<String, Object> validationRules;
    
    /**
     * 是否必填
     */
    private Boolean isRequired;
    
    /**
     * 是否唯一
     */
    private Boolean isUnique;
    
    /**
     * 获取字段编码（fieldKey的别名方法）
     * @return 字段编码
     */
    public String getFieldCode() {
        return this.code;
    }
    
    /**
     * 获取是否必填（isRequired的别名方法）
     * @return 是否必填
     */
    public Boolean getRequired() {
        return this.isRequired;
    }
    
    /**
     * 获取字段名称
     * @return 字段名称
     */
    public String getFieldName() {
        return code; // 返回字段键作为字段名称
    }
    
    /**
     * 获取字段类型（dataType的别名方法）
     * @return 字段类型
     */
    public String getFieldType() {
        return this.dataType;
    }
    
    /**
     * 获取最大长度（从validationRules中获取）
     * @return 最大长度
     */
    public Integer getMaxLength() {
        if (validationRules != null && validationRules.containsKey("maxLength")) {
            Object maxLength = validationRules.get("maxLength");
            if (maxLength instanceof Number) {
                return ((Number) maxLength).intValue();
            }
        }
        return null;
    }
}