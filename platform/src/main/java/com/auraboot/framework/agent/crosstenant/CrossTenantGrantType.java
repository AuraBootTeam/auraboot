package com.auraboot.framework.agent.crosstenant;

/**
 * Stable string codes for {@code ab_cross_tenant_grant.grant_type} —
 * extracted to a constants holder per AGENTS.md "禁止魔术字符串" rule.
 *
 * <p>Phase 1 only ships {@link #SPAWN_SUB_AGENT}; future grant types
 * (e.g. {@code "send_message"}, {@code "read_audit_log"}) plug in here
 * without touching call-sites.
 */
public final class CrossTenantGrantType {

    /** Authorises {@code parent_tenant} to spawn child agent runs in {@code child_tenant}. */
    public static final String SPAWN_SUB_AGENT = "spawn_sub_agent";

    private CrossTenantGrantType() {}
}
