package com.auraboot.framework.agent.crosstenant;

/**
 * Cross-tenant ACL chokepoint.
 *
 * <p>Every call-site that bridges a parent run in tenant A to a child run /
 * event in tenant B (B ≠ A) MUST consult this service first. Same-tenant
 * (A == B) call-sites SHOULD short-circuit before calling — the cross-tenant
 * ACL is irrelevant there and skipping the cache lookup keeps the same-tenant
 * fast path identical to its pre-C.2 latency.
 *
 * <p>Default-deny: a missing / expired / revoked grant returns a denied
 * {@link CrossTenantDecision}. The feature flag
 * {@code aura.security.cross-tenant.enabled} (default {@code true}) is the
 * top-level kill switch — when {@code false} every cross-tenant evaluation
 * returns {@link CrossTenantDecision#DENIED_FEATURE_DISABLED}.
 */
public interface CrossTenantAclService {

    /**
     * Convenience wrapper around {@link #evaluate(Long, Long, String)} that
     * returns the boolean answer and discards the decision metadata.
     */
    boolean allows(Long parentTenantId, Long childTenantId, String grantType);

    /**
     * Evaluate the (parent → child, grantType) triple against the active
     * grant table.
     *
     * <p>Same-tenant (parentTenantId.equals(childTenantId)) returns
     * {@link CrossTenantDecision#allowed(Long)} with a null grantId — every
     * caller is free to bypass the cache for this case but the service still
     * answers consistently when called.
     *
     * @return non-null decision; never throws on bad input — null arg → denied
     *         with {@link CrossTenantDecision#DENIED_NO_GRANT}.
     */
    CrossTenantDecision evaluate(Long parentTenantId, Long childTenantId, String grantType);

    /**
     * Drop any cached decision for the given key. Called from the admin
     * controller after grant / revoke writes so the change is visible
     * immediately rather than waiting for the 10s TTL.
     */
    void invalidate(Long parentTenantId, Long childTenantId, String grantType);
}
