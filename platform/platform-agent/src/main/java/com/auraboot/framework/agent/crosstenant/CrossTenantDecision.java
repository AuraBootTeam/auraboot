package com.auraboot.framework.agent.crosstenant;

/**
 * Outcome of a single {@link CrossTenantAclService#evaluate(Long, Long, String)}
 * call. The decision is consumed by the caller (SubAgentRunner /
 * ParentJoinService / PlatformToolProvider#delegate_task) to decide
 * whether to proceed and what to write into the audit log.
 *
 * <p>String codes are persisted into
 * {@code ab_cross_tenant_spawn_audit.decision} and exposed in the deny
 * error message — no magic-string drift across modules.
 */
public final class CrossTenantDecision {

    public static final String ALLOWED = "allowed";
    public static final String DENIED_NO_GRANT = "denied_no_grant";
    public static final String DENIED_EXPIRED = "denied_expired";
    public static final String DENIED_REVOKED = "denied_revoked";
    public static final String DENIED_FEATURE_DISABLED = "denied_feature_disabled";

    private final String code;
    private final Long grantId;
    private final String reason;

    private CrossTenantDecision(String code, Long grantId, String reason) {
        this.code = code;
        this.grantId = grantId;
        this.reason = reason;
    }

    public static CrossTenantDecision allowed(Long grantId) {
        return new CrossTenantDecision(ALLOWED, grantId, null);
    }

    public static CrossTenantDecision denied(String code, String reason) {
        return new CrossTenantDecision(code, null, reason);
    }

    public boolean isAllowed() {
        return ALLOWED.equals(code);
    }

    public String code() {
        return code;
    }

    public Long grantId() {
        return grantId;
    }

    public String reason() {
        return reason;
    }
}
