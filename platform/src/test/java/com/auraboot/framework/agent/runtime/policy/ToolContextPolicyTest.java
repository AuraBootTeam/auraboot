package com.auraboot.framework.agent.runtime.policy;

import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import com.auraboot.framework.agent.runtime.context.AgentContextProvenance;
import com.auraboot.framework.agent.runtime.context.AgentContextSensitivity;
import com.auraboot.framework.agent.runtime.context.AgentContextSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the L3 <strong>Context</strong> gate ({@link ToolContextPolicy}) — the
 * cross-tenant / record-scope guard that, for write/external tools, denies actions whose
 * target record or tenant falls outside the agent's current context.
 *
 * <p>Added 2026-06-17 (gate-negative verification campaign): this gate had <em>no</em>
 * dedicated test while the other four ToolPolicy layers (Capability/Argument/Durability/
 * Approval/Risk) each did — the one genuine gap left after verifying that the inventory's
 * other "gate gaps" (plan_hash tamper, ACL allow/deny, timeout enforcement) were already
 * covered. Pure function, no Spring/DB.
 */
@DisplayName("ToolContextPolicy (L3 context gate — cross-tenant / record-scope deny)")
class ToolContextPolicyTest {

    private final ToolContextPolicy policy = new ToolContextPolicy();

    // ---- the gate only applies to writes ------------------------------------

    @Test
    @DisplayName("read-only tool: gate does not apply → allow even with foreign context")
    void readOnlyOperationSkipsGate() {
        ToolContextPolicy.ContextDecision d = policy.evaluate(
                meta(ToolEffectType.INTERNAL_READ),
                Map.of("recordPid", "C-999"),
                List.of(block(1L, List.of("C-1"), true)),
                actor(2L)); // foreign tenant + out-of-scope record, but it's a read
        assertThat(d.allowed()).isTrue();
    }

    @Test
    @DisplayName("null metadata → allow (nothing to gate)")
    void nullMetadataAllows() {
        assertThat(policy.evaluate(null, Map.of("recordPid", "C-2"),
                List.of(block(2L, List.of("C-1"), true)), actor(1L)).allowed()).isTrue();
    }

    @Test
    @DisplayName("write with no context → allow (no scope to enforce)")
    void writeWithNoContextAllows() {
        assertThat(policy.evaluate(meta(ToolEffectType.INTERNAL_WRITE),
                Map.of("recordPid", "C-2"), List.of(), actor(1L)).allowed()).isTrue();
    }

    // ---- cross-tenant deny (the key negative) -------------------------------

    @Test
    @DisplayName("write whose context tenant != actor tenant → DENY context_tenant_mismatch")
    void writeCrossTenantDenied() {
        ToolContextPolicy.ContextDecision d = policy.evaluate(
                meta(ToolEffectType.INTERNAL_WRITE),
                Map.of(),
                List.of(block(1L, List.of(), false)), // context belongs to tenant 1
                actor(2L));                            // actor is tenant 2
        assertThat(d.allowed()).isFalse();
        assertThat(d.reasonCode()).isEqualTo("context_tenant_mismatch");
    }

    @Test
    @DisplayName("write whose context tenant == actor tenant → allow")
    void writeSameTenantAllowed() {
        assertThat(policy.evaluate(meta(ToolEffectType.INTERNAL_WRITE), Map.of(),
                List.of(block(1L, List.of(), false)), actor(1L)).allowed()).isTrue();
    }

    @Test
    @DisplayName("EXTERNAL_ACTION cross-tenant → DENY (gate also covers external side effects)")
    void externalActionCrossTenantDenied() {
        ToolContextPolicy.ContextDecision d = policy.evaluate(
                meta(ToolEffectType.EXTERNAL_ACTION), Map.of(),
                List.of(block(1L, List.of(), false)), actor(2L));
        assertThat(d.allowed()).isFalse();
        assertThat(d.reasonCode()).isEqualTo("context_tenant_mismatch");
    }

    // ---- record-scope deny (the second key negative) ------------------------

    @Test
    @DisplayName("write targeting a record IN the context scope → allow")
    void writeRecordInScopeAllowed() {
        assertThat(policy.evaluate(meta(ToolEffectType.INTERNAL_WRITE),
                Map.of("recordPid", "C-1"),
                List.of(block(1L, List.of("C-1"), true)), actor(1L)).allowed()).isTrue();
    }

    @Test
    @DisplayName("write targeting a record OUTSIDE the context scope → DENY context_scope_violation")
    void writeRecordOutOfScopeDenied() {
        ToolContextPolicy.ContextDecision d = policy.evaluate(
                meta(ToolEffectType.INTERNAL_WRITE),
                Map.of("recordPid", "C-2"),
                List.of(block(1L, List.of("C-1"), true)), // scope only has C-1
                actor(1L));
        assertThat(d.allowed()).isFalse();
        assertThat(d.reasonCode()).isEqualTo("context_scope_violation");
    }

    @Test
    @DisplayName("targetRecordPid key is recognized -> out-of-scope still DENY")
    void writeRecordOutOfScopeViaTargetRecordPidKey() {
        ToolContextPolicy.ContextDecision d = policy.evaluate(
                meta(ToolEffectType.INTERNAL_WRITE),
                Map.of("targetRecordPid", "C-2"),
                List.of(block(1L, List.of("C-1"), true)), actor(1L));
        assertThat(d.allowed()).isFalse();
        assertThat(d.reasonCode()).isEqualTo("context_scope_violation");
    }

    @Test
    @DisplayName("write with a target record but EMPTY record scope → allow (no scope to enforce)")
    void writeRecordButEmptyScopeAllowed() {
        // readWriteRelevant=false -> provenance contributes its tenant but no recordPids.
        assertThat(policy.evaluate(meta(ToolEffectType.INTERNAL_WRITE),
                Map.of("recordPid", "C-2"),
                List.of(block(1L, List.of("C-1"), false)), actor(1L)).allowed()).isTrue();
    }

    // ---- fixtures -----------------------------------------------------------

    private static ToolMetadata meta(ToolEffectType effect) {
        return ToolMetadata.builder().toolName("cmd:crm_customer_update").effectType(effect).build();
    }

    private static ToolPolicyActor actor(Long tenantId) {
        return new ToolPolicyActor(tenantId, 99L, Set.of());
    }

    private static AgentContextBlock block(Long tenantId, List<String> recordPids, boolean rwRelevant) {
        return new AgentContextBlock("ctx", "{}",
                new AgentContextProvenance(
                        AgentContextSource.RECORD, "scope", "fresh", "perm",
                        AgentContextSensitivity.CONFIDENTIAL,
                        recordPids, tenantId, "web", rwRelevant, Map.of()));
    }
}
