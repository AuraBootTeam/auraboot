package com.auraboot.framework.permission.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import java.time.Instant;

/**
 * Permission Entity - 权限事实表 (V4)
 * 
 * Permission代表系统中的权限事实，由资源定义自动生成。
 * 
 * 核心原则:
 * - Permission = Fact (能做什么)
 * - 系统生成，非人工编写
 * - 支持生命周期管理: ACTIVE → DEPRECATED → ARCHIVED
 * - Git-first管理，支持版本控制和回滚
 * 
 * @author AuraBoot
 * @version V4
 * @since 2025-01-07
 */
@Data
@TableName("ab_permission")
public class Permission {
    
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
    // Permission Identity (权限身份 - 长期稳定)
    // ========================================================================
    
    /**
     * Permission Code (唯一标识)
     * Format: {resource_type}.{resource_code}.{action}
     * Example: "model.user_model.create"
     */
    private String code;
    
    /**
     * Permission Name (显示名称)
     */
    private String name;
    
    /**
     * Description
     */
    private String description;
    
    // ========================================================================
    // Resource Classification (资源分类)
    // ========================================================================
    
    /**
     * Resource Type
     * Values: MODEL, PAGE, QUERY, API, WORKFLOW
     */
    private String resourceType;
    
    /**
     * Resource Code
     */
    private String resourceCode;
    
    /**
     * Action
     * Values: create, read, update, delete, execute, etc.
     */
    private String action;
    
    // ========================================================================
    // Source Tracking (来源追踪)
    // ========================================================================
    
    /**
     * Source (来源)
     * Values: GENERATED (系统生成), MANUAL (手动创建)
     */
    private String source;
    
    /**
     * Source Reference (来源引用)
     */
    private String sourceRef;
    
    // ========================================================================
    // Hierarchy Support (层级支持)
    // ========================================================================
    
    /**
     * Parent Permission ID
     */
    private Long parentId;
    
    /**
     * Path (层级路径)
     * Example: "/1/2/3"
     */
    private String path;
    
    /**
     * Level (层级深度)
     */
    private Integer level;
    
    // ========================================================================
    // Data Permission Extension (数据权限扩展 - Future)
    // ========================================================================
    
    /**
     * Data Scope Type
     * Values: ALL, DEPT, DEPT_AND_SUB, SELF, CUSTOM
     */
    private String dataScopeType;
    
    /**
     * Data Scope Config (JSONB)
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object dataScopeConfig;
    
    // ========================================================================
    // Metadata (元数据)
    // ========================================================================
    
    /**
     * Extension (扩展字段, JSONB)
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object extension;
    
    /**
     * Tags (标签数组)
     */
    private String[] tags;

    /**
     * 插件PID（用于标识资源来源的插件）
     */
    private String pluginPid;

    // ========================================================================
    // Lifecycle Status (生命周期状态)
    // ========================================================================
    
    /**
     * Status
     * Values: ACTIVE (使用中), DEPRECATED (6个月过渡期), ARCHIVED (永久归档)
     */
    private String status;
    
    /**
     * Deprecated At (废弃时间)
     */
    private Instant deprecatedAt;
    
    /**
     * Archived At (归档时间)
     */
    private Instant archivedAt;
    
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
