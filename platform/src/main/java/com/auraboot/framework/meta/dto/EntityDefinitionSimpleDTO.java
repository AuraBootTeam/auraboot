package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 实体定义简单DTO
 * 用于列表显示和简单查询场景
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityDefinitionSimpleDTO {
    
    /**
     * 实体PID
     */
    private String entityPid;
    
    /**
     * 实体名称
     */
    private String entityName;
    
    /**
     * 实体代码
     */
    private String entityCode;
    
    /**
     * 描述
     */
    private String description;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
    
    /**
     * 检查实体是否激活
     */
    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }
    
    /**
     * 检查实体是否为草稿状态
     */
    public boolean isDraft() {
        return StatusConstants.DRAFT.equals(status);
    }
}