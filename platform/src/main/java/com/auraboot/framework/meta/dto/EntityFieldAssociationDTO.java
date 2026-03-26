package com.auraboot.framework.meta.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 实体字段关联DTO
 * 
 * @author AuraBoot
 * @since 2024-01-01
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntityFieldAssociationDTO {
    
    /**
     * 关联ID
     */
    private Long id;
    
    /**
     * 租户ID
     */
    private String tenantId;
    
    /**
     * 实体PID
     */
    private String entityPid;
    
    /**
     * 实体名称
     */
    private String entityName;
    
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
     * 是否必填
     */
    private Boolean required;
    
    /**
     * 是否唯一
     */
    private Boolean unique;
    
    /**
     * 排序顺序
     */
    private Integer sortOrder;
    
    /**
     * 状态
     */
    private String status;
    
    /**
     * 扩展配置
     */
    private Map<String, Object> extendedConfig;
    
    /**
     * 创建时间
     */
    private LocalDateTime createdAt;
    
    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
    
    /**
     * 创建人
     */
    private String createdBy;
    
    /**
     * 更新人
     */
    private String updatedBy;
    
    /**
     * 检查关联是否激活
     */
    public boolean isActive() {
        return "active".equalsIgnoreCase(status);
    }
    
    /**
     * 检查字段是否必填
     */
    public boolean isRequired() {
        return Boolean.TRUE.equals(required);
    }
    
    /**
     * 检查字段是否唯一
     */
    public boolean isUnique() {
        return Boolean.TRUE.equals(unique);
    }
}