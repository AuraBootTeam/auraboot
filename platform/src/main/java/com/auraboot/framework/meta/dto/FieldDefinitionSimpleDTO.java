package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * 字段定义简单DTO
 * 用于列表显示和简单查询场景
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldDefinitionSimpleDTO {
    
    /**
     * 字段PID
     */
    private String fieldPid;
    
    /**
     * 字段键
     */
    private String code;
    
    /**
     * 字段名称
     */
    private String fieldName;
    
    /**
     * 数据类型
     */
    private String dataType;
    
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
     * 检查字段是否激活
     */
    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }
    
    /**
     * 检查字段是否为草稿状态
     */
    public boolean isDraft() {
        return StatusConstants.DRAFT.equals(status);
    }
}