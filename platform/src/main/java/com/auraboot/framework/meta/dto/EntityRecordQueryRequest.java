package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实体记录查询请求DTO
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EntityRecordQueryRequest extends AbstractQueryRequest {
    
    /**
     * 实体键
     */
    private String entityCode;
    
    /**
     * 实体版本
     */
    private String entityVersion;
    
    /**
     * 表单键
     */
    private String blockCode;
    
    /**
     * 表单版本
     */
    private String formVersion;
    
    /**
     * 记录名称
     */
    private String name;
}