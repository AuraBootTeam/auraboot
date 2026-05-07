package com.auraboot.framework.agent.crosstenant;

import java.time.Instant;

/**
 * Domain record mirroring {@code ab_cross_tenant_spawn_audit}.
 *
 * <p>One row per cross-tenant spawn decision. {@link #grantId} and
 * {@link #childRunPid} are nullable: a denied decision has no grant
 * reference and no child run was created.
 */
public record CrossTenantSpawnAudit(
        Long id,
        Long grantId,
        Long parentTenantId,
        Long childTenantId,
        String parentRunPid,
        String childRunPid,
        String decision,
        Instant spawnAt,
        String errorMessage
) {
}
