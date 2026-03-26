package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * Binding between DataPermissionPolicy and Role.
 *
 * @since 5.1.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_data_permission_role_binding")
public class DataPermissionRoleBinding {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("policy_pid")
    private String policyPid;

    @TableField("role_pid")
    private String rolePid;
}
