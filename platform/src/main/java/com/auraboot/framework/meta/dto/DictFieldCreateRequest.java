package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.constant.DataType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

/**
 * 字典字段创建请求DTO
 */
@Data
public class DictFieldCreateRequest   {
    
    /**
     * 字段键
     */
    private String code;
    
    /**
     * 数据类型
     */
    private DataType dataType;
    
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
}