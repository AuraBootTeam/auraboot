package com.auraboot.framework.rbac.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
     * 用户角色关联实体 - 用户与角色的多对多关系
     */
    @Data
    @TableName("ab_user_role")
    public   class UserRole {

        @TableId(type = IdType.ASSIGN_ID)
        private Long id;                    // 关联ID
        private String pid;                 // 业务ID(ULID)

        private Long tenantId;              // 所属租户ID

        private Instant createdAt;             // 创建时间
        private Instant updatedAt;             // 更新时间

        private Long memberId;            // Tenant member ID
        private Long roleId;                // 角色ID

        private String assignType;          // 分配类型：DIRECT, INHERITED

        private Instant effectiveDate;         // 生效日期
        private Instant expiryDate;            // 失效日期

        private String status;              // 状态：ACTIVE, INACTIVE
        private Boolean deletedFlag = false; // 逻辑删除标记

        // 审计字段
        private Long createdBy;           // 创建人
        private Long updatedBy;           // 更新人
    }