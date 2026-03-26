package com.auraboot.framework.file.dto;

import lombok.Data;

/**
 * 文件关联请求DTO
 */
@Data
public class FileRelationRequestDTO {
    /**
     * 文件ID列表
     */
    private String[] fileIds;
    
    /**
     * 关联实体类型
     */
    private String entityType;
    
    /**
     * 关联实体ID
     */
    private String entityId;
    
    /**
     * 关联字段名
     */
    private String fieldName;
}