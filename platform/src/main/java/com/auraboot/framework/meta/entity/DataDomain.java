package com.auraboot.framework.meta.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.Instant;

/**
 * Data domain entity for business unit / subsidiary data isolation.
 *
 * <p>Supports hierarchical domains via parent_domain_id (e.g. "Asia" → "Shanghai Factory").
 * When domain isolation is enabled on a model, only records whose domain_id
 * matches one of the user's assigned domains are visible.
 *
 * @since 5.2.0
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("ab_data_domain")
public class DataDomain {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("tenant_id")
    private Long tenantId;

    @TableField("domain_code")
    private String domainCode;

    @TableField("domain_name")
    private String domainName;

    @TableField("description")
    private String description;

    @TableField("parent_domain_id")
    private Long parentDomainId;

    @TableField("enabled")
    private Boolean enabled;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    @TableLogic
    @TableField("deleted_flag")
    private Boolean deletedFlag;
}
