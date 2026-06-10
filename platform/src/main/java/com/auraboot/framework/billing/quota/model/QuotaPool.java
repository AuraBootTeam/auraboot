package com.auraboot.framework.billing.quota.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Resource quota pool — groups buckets by account/workspace and resource type.
 *
 * <p>Backed by {@code ab_billing_quota_pool}.  A pool is the top-level container
 * that associates a resource type with an account (and optionally a workspace).
 * Buckets ({@link QuotaBucket}) belong to exactly one pool.
 *
 * <p>Platform-global table: {@code @InterceptorIgnore(tenantLine="true")} is applied
 * on the mapper to prevent the multi-tenant interceptor from injecting a tenant filter.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("ab_billing_quota_pool")
public class QuotaPool {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** Stable external identifier for this pool. */
    private String poolCode;

    /** Account that owns this pool. */
    private Long accountId;

    /** Optional workspace scope; NULL means account-level. */
    private Long workspaceId;

    /** Linked subscription. */
    private Long subscriptionId;

    /** Resource type — must be registered in {@code ab_billing_resource_catalog}. */
    private String resourceCode;

    /** Scope at which this pool applies. */
    private String scopeType;

    /** Whether this pool is dedicated or shared. */
    private String poolType;

    /** Lifecycle status. */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
