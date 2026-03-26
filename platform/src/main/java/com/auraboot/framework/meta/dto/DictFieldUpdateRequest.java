package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 字典字段更新请求DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictFieldUpdateRequest extends AbstractUpdateRequest {
    
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
     * UI配置
     */
    private Map<String, Object> uiSchema;
    
    /**
     * 查询配置
     */
    private Map<String, Object> querySchema;
    
    /**
     * 规则配置
     */
    private Map<String, Object> ruleSchema;
}