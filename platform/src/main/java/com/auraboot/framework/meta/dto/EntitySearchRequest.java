package com.auraboot.framework.meta.dto;

import com.auraboot.framework.meta.dto.PaginationRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 实体搜索请求DTO
 * 用于实体定义的搜索和过滤
 * 
 * @author AuraBoot Team
 * @since 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EntitySearchRequest extends PaginationRequest {
    
    /**
     * 实体编码
     */
    private String code;
    
    /**
     * 实体类型
     */
    private String entityType;
    
    /**
     * 表名
     */
    private String tableName;
    
    /**
     * 是否为系统实体
     */
    private Boolean isSystem;
    
    /**
     * 状态
     */
    private String status;
    
      
    
    
    
    /**
     * 是否启用缓存
     */
    private Boolean cacheEnabled;
    
    /**
     * 创建人
     */
    private String createdBy;
    
    /**
     * 更新人
     */
    private String updatedBy;
}