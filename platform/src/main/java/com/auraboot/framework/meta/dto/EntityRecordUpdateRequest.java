package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.entity.payload.EntityRecordDataBean;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实体记录更新请求
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EntityRecordUpdateRequest extends AbstractUpdateRequest {
    
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
    
    /**
     * 记录数据
     */
    private EntityRecordDataBean data;
}