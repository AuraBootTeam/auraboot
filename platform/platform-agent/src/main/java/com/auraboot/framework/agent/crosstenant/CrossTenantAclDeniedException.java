package com.auraboot.framework.agent.crosstenant;

/**
 * Thrown by call-sites (SubAgentRunner / ParentJoinService.joinChildRun) when
 * a cross-tenant operation is rejected by {@link CrossTenantAclService}.
 *
 * <p>Extends {@link IllegalStateException} so existing
 * {@code catch (IllegalStateException)} blocks (e.g. PlatformToolProvider's
 * delegate_task) keep working — but carries the structured decision
 * ({@link #parentTenantId} / {@link #childTenantId} / {@link #decision}) so a
 * tool-layer caller can convert into a structured error response per Q11
 * ("delegate_task ACL deny → STRUCTURED ERROR, not exception").
 *
 * <p>Message format mirrors what gets written to
 * {@code ab_cross_tenant_spawn_audit.error_message} so logs and DB rows agree.
 */
public class CrossTenantAclDeniedException extends IllegalStateException {

    private final Long parentTenantId;
    private final Long childTenantId;
    private final CrossTenantDecision decision;

    public CrossTenantAclDeniedException(Long parentTenantId,
                                         Long childTenantId,
                                         CrossTenantDecision decision) {
        super(buildMessage(parentTenantId, childTenantId, decision));
        this.parentTenantId = parentTenantId;
        this.childTenantId = childTenantId;
        this.decision = decision;
    }

    public Long parentTenantId() {
        return parentTenantId;
    }

    public Long childTenantId() {
        return childTenantId;
    }

    public CrossTenantDecision decision() {
        return decision;
    }

    private static String buildMessage(Long parent, Long child, CrossTenantDecision decision) {
        return "cross-tenant spawn requires explicit grant "
                + "(parent_tenant=" + parent + " → child_tenant=" + child
                + ", decision=" + (decision == null ? "denied_no_grant" : decision.code()) + ")";
    }
}
