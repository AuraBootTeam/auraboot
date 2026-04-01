package com.auraboot.framework.permission.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

import java.time.Instant;

/**
 * Role Data Scope entity — stores per-role, per-resource, per-action
 * data visibility configuration.
 */
@Data
@TableName(value = "ab_role_data_scope", autoResultMap = true)
public class RoleDataScope {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String pid;

    private Long tenantId;

    private Long roleId;

    private String resourceCode;

    private String actionCode;

    /**
     * Scope type: all, none, self, dept, dept_and_sub
     */
    private String scopeType;

    /**
     * Merge strategy when user has multiple roles: MAX (most permissive) or MIN (most restrictive)
     */
    private String mergeStrategy;

    /**
     * Optional JSON config for custom scope parameters
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private Object scopeConfig;

    private Instant createdAt;

    private Instant updatedAt;
}
