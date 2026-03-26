package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 字典字段查询请求DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class DictFieldQueryRequest extends AbstractQueryRequest {
    
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
     * PII分类
     */
    private String piiClass;
    
    /**
     * 索引提示
     */
    private String indexHint;
}