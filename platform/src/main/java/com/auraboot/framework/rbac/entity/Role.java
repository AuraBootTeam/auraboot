package com.auraboot.framework.rbac.entity;

import com.auraboot.framework.application.database.mybatis.JsonbStringTypeHandler;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
    @TableName(value = "ab_role", autoResultMap = true)
    public   class Role {

        @TableId(type = IdType.ASSIGN_ID)
        private Long id;                    // 角色ID
        private String pid;                 // 业务ID(ULID)

        private Instant createdAt;             // 创建时间
        private Instant updatedAt;             // 更新时间

        private Long tenantId;              // 所属租户ID

        private String name;                // 角色名称
       private String code;                // 角色编码
        private String description;         // 角色描述

        private String type;                // 角色类型：SYSTEM, TENANT, CUSTOM
        private String scopeType;               // 作用域：GLOBAL, TENANT, STORE
        @TableField(value = "scope_content", typeHandler = JsonbStringTypeHandler.class)
        private String scopeContent;               // 作用域值,json

        private Integer priority;           // 优先级(数字越小优先级越高)
        private String status;              // 状态：ACTIVE, INACTIVE

        private Boolean isDefault = false;  // 是否默认角色
        private Boolean isSystem = false;   // 是否系统角色
        private Boolean deletedFlag = false; // 逻辑删除标记

        // 审计字段
        private Long createdBy;           // 创建人
        private Long updatedBy;           // 更新人

    }
