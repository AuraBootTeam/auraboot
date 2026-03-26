package com.auraboot.framework.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * Per-tenant login channel toggle.
 * <p>
 * Each row controls whether a specific login channel (e.g., EMAIL_PASSWORD, SMS)
 * is enabled for a given tenant, and its display sort order on the login page.
 *
 * @since 7.0.0
 */
@Data
@TableName("ab_tenant_login_channel")
public class TenantLoginChannel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** Channel code: EMAIL_PASSWORD | SMS | EMAIL_CODE | WECHAT | GOOGLE | APPLE */
    private String channel;

    /** Whether this channel is enabled for the tenant */
    private Boolean enabled;

    /** Display sort order (lower = higher priority) */
    private Integer sortOrder;
}
