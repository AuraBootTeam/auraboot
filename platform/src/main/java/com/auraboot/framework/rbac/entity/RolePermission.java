package com.auraboot.framework.rbac.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * Role-Permission Entity - Role-Permission绑定表 (V4)
 * 
 * 定义"谁能做"(Who can do)，通过Role-Permission绑定实现授权分配。
 * 
 * 核心原则:
 * - RBAC = Permission分配器
 * - 支持GRANT/DENY语义
 * - 支持时间窗口和条件控制
 * - 不定义Permission (Permission由系统生成)
 * 
 * @author AuraBoot
 * @version V4
 * @since 2025-01-07
 */
@Data
@TableName(value = "ab_role_permission", autoResultMap = true)
public class RolePermission {
    
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
    // Binding Relationship
    // ========================================================================
    
    /**
     * Role ID
     */
    private Long roleId;
    
    /**
     * Permission ID
     */
    private Long permissionId;
    
    // ========================================================================
    // Grant Control (V4: DENY语义收紧)
    // ========================================================================
    
    /**
     * Grant Type
     * Values: GRANT (允许), DENY (拒绝)
     * V4语义: DENY只对同一permission生效，禁止跨permission的deny
     */
    private String grantType;
    
    /**
     * Priority (优先级)
     * 冲突解决规则: DENY always wins → priority DESC → created_at DESC → id ASC
     */
    private Integer priority;
    
    // ========================================================================
    // Temporal Control (时间控制)
    // ========================================================================
    
    /**
     * Effective Date (生效日期)
     */
    private LocalDate effectiveDate;
    
    /**
     * Expiry Date (过期日期)
     */
    private LocalDate expiryDate;
    
    // ========================================================================
    // Conditions (Advanced)
    // ========================================================================
    
    /**
     * Conditions (条件, JSONB)
     * Stores policy parameter values for Permission Policy (Phase 4).
     * E.g. {"maxApprovalAmount": 100000, "allowBulkApprove": true}
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object conditions;
    
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
