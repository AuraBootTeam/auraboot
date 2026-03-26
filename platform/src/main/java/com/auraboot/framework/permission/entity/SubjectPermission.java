package com.auraboot.framework.permission.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.Instant;

/**
 * Subject-Permission Entity - Subject-Permission统一声明表 (V4)
 * 
 * Subject代表Menu/Page/Button的统一抽象，声明所需的Permission。
 * 
 * 核心原则:
 * - Subject = Permission聚合器
 * - 支持AND/OR逻辑组合
 * - 支持is_negated (仅用于UI可见性)
 * - 不参与后端授权决策
 * 
 * @author AuraBoot
 * @version V4
 * @since 2025-01-07
 */
@Data
@TableName("ab_subject_permission")
public class SubjectPermission {
    
    /**
     * Primary Key
     */
    @TableId(type = IdType.AUTO)
    private Long id;
    
    /**
     * Public ID (UUID)
     */
    private String pid;
    
    // ========================================================================
    // Tenant Isolation
    // ========================================================================
    
    /**
     * Tenant ID
     */
    private Long tenantId;
    
 
    
    
    
    // ========================================================================
    // Subject (主体 - Menu/Page/Button的统一抽象)
    // ========================================================================
    
    /**
     * Subject Type
     * Values: MENU, PAGE, BUTTON, QUERY, WORKFLOW
     */
    private String subjectType;
    
    /**
     * Subject ID
     */
    private Long subjectId;
    
    /**
     * Subject Code
     * V4: NOT NULL DEFAULT '' (避免NULL导致的唯一约束问题)
     */
    private String subjectCode;
    
    // ========================================================================
    // Permission Reference
    // ========================================================================
    
    /**
     * Permission ID
     */
    private Long permissionId;
    
    // ========================================================================
    // Logic Control (逻辑控制)
    // ========================================================================
    
    /**
     * Logic Group (逻辑组)
     * 同一Subject可以有多个逻辑组，组间OR关系
     */
    private Integer logicGroup;
    
    /**
     * Group Logic Type (组内逻辑类型)
     * Values: AND, OR
     * V4约束: 同一逻辑组内必须一致
     */
    private String groupLogicType;
    
    /**
     * Is Negated (是否取反)
     * V4安全边界: 仅用于UI可见性判断，不参与后端授权决策
     */
    private Boolean isNegated;
    
    /**
     * Logic Order (逻辑顺序)
     * 用于控制同一逻辑组内的评估顺序
     */
    private Integer logicOrder;
    
    // ========================================================================
    // Requirement Type (需求类型)
    // ========================================================================
    
    /**
     * Requirement Type
     * Values: VIEW, EXECUTE, EDIT, DELETE, EXPORT, IMPORT
     */
    private String requirementType;
    
    // ========================================================================
    // Status
    // ========================================================================
    
    /**
     * Status
     * Values: ACTIVE, INACTIVE
     */
    private String status;
    
    /**
     * Deleted Flag (逻辑删除标记)
     */
    @TableLogic
    private Boolean deletedFlag;
    
    // ========================================================================
    // Audit (审计)
    // ========================================================================
    
    /**
     * Created At
     */
    private Instant createdAt;
    
    /**
     * Updated At
     */
    private Instant updatedAt;
    
    /**
     * Created By
     */
    private Long createdBy;
    
    /**
     * Updated By
     */
    private Long updatedBy;
}
