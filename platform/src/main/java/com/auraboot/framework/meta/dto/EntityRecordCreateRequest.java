package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.entity.payload.EntityRecordDataBean;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实体记录创建请求
 */
@Data
public class EntityRecordCreateRequest   {
    
    /**
     * 实体键
     */
    @NotBlank(message = "实体键不能为空")
    private String entityCode;
    
    /**
     * 实体版本
     */
    private Integer entityVersion;
    
    /**
     * 表单键
     */
    private String blockCode;
    
    /**
     * 表单版本
     */
    private Integer formVersion;
    
    /**
     * 记录名称
     */
    @NotBlank(message = "记录名称不能为空")
    private String name;
    
    /**
     * 记录数据
     */
    private EntityRecordDataBean data;

}