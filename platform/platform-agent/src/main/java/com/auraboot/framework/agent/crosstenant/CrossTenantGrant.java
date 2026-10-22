package com.auraboot.framework.agent.crosstenant;

import java.time.Instant;

/**
 * Domain record mirroring {@code ab_cross_tenant_grant}. Used by
 * {@link CrossTenantAclService} (read path) and the admin
 * {@code CrossTenantGrantController} (CRUD path).
 *
 * <p>Java 17 record — immutable, value semantics. Serialised to JSON via
 * Jackson default record support.
 */
public record CrossTenantGrant(
        Long id,
        Long parentTenantId,
        Long childTenantId,
        String grantType,
        Long grantedBy,
        Instant grantedAt,
        Instant expiresAt,
        Instant revokedAt,
        Long revokedBy,
        String note
) {
}
