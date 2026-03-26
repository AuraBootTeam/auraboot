package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * Many-to-many binding between users and data domains.
 *
 * <p>A user can belong to multiple domains. The is_primary flag indicates
 * which domain new records should be assigned to by default.
 *
 * @since 5.2.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_user_data_domain")
public class UserDataDomain {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("user_id")
    private Long userId;

    @TableField("domain_id")
    private Long domainId;

    @TableField("is_primary")
    private Boolean isPrimary;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
