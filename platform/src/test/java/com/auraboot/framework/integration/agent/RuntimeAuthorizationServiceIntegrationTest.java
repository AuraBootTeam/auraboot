package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.authorization.BlastRadius;
import com.auraboot.framework.agent.authorization.EffectClass;
import com.auraboot.framework.agent.authorization.EffectLifetime;
import com.auraboot.framework.agent.authorization.GrantScope;
import com.auraboot.framework.agent.authorization.RuntimeAuthorizationService;
import com.auraboot.framework.agent.authorization.RuntimeAuthorizationService.IncrementalAuthorization;
import com.auraboot.framework.agent.authorization.RuntimeAuthorizationService.PlanAuthorization;
import com.auraboot.framework.agent.authorization.RuntimeAuthorizationService.PlanAuthorizationInput;
import com.auraboot.framework.agent.authorization.RuntimeAuthorizationService.PlannedCall;
import com.auraboot.framework.agent.authorization.RuntimeAuthorizationService.ToolCallIntent;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GAP-272: RuntimeAuthorizationService — default impl plan/incremental
 * decisions persist to ab_agent_authorization_decision; GrantScope
 * matching rules.
 */
@Commit
@DisplayName("RuntimeAuthorizationService — default impl + audit + GrantScope matching")
class RuntimeAuthorizationServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private RuntimeAuthorizationService authzService;
    @Autowired private JdbcTemplate jdbc;

    private long tenantId;
    private String runId;

    @BeforeEach
    void setup() {
        tenantId = 6_6001L + System.nanoTime() % 10000;
        runId = UniqueIdGenerator.generate();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_authorization_decision WHERE tenant_id = ?", tenantId);
    }

    @Test
    @DisplayName("authorizePlan emits one plan-decision row and returns grants for all planned effects")
    void authorizePlan_persistsAndReturnsGrants() {
        PlannedCall call = new PlannedCall(
                "crm.lead.advance",
                "dsl:cmd_crm_lead_transition",
                Set.of(EffectClass.WRITE_PLATFORM_STATE, EffectClass.READ_PLATFORM_DATA),
                BlastRadius.SHARED_STATE,
                null,
                Map.of("leadId", "L_001"));

        String planHash = "plan_hash_" + System.nanoTime();
        PlanAuthorization result = authzService.authorizePlan(new PlanAuthorizationInput(
                tenantId, 999L, runId, "bif_001", planHash, List.of(call), "session_001"));

        assertThat(result.planHash()).isEqualTo(planHash);
        assertThat(result.preAuthorizedGrants()).hasSize(2);
        assertThat(result.preAuthorizedGrants())
                .extracting(GrantScope::effect)
                .containsExactlyInAnyOrder(EffectClass.WRITE_PLATFORM_STATE, EffectClass.READ_PLATFORM_DATA);
        assertThat(result.forbiddenEffects()).isEmpty();

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ab_agent_authorization_decision WHERE tenant_id = ? AND decision_kind = 'plan'",
                Integer.class, tenantId);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("authorizeIncremental grants the call and persists row with subject info")
    void authorizeIncremental_grantsAndPersists() {
        IncrementalAuthorization result = authzService.authorizeIncremental(new ToolCallIntent(
                tenantId, runId, 0, 0,
                "dsl:cmd_crm_lead_transition",
                "crm.lead.advance",
                "plan_hash_xyz",
                Set.of(EffectClass.WRITE_PLATFORM_STATE),
                BlastRadius.SHARED_STATE,
                "abc123hashprefix",
                Map.of(),
                "session_001"));

        assertThat(result.granted()).isTrue();
        assertThat(result.requireApproval()).isFalse();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT tool_ref, skill_code, arg_hash, blast_radius, decision_kind, session_scope_key " +
                        "FROM ab_agent_authorization_decision WHERE tenant_id = ? AND decision_kind = 'incremental'",
                tenantId);
        assertThat(row.get("tool_ref")).isEqualTo("dsl:cmd_crm_lead_transition");
        assertThat(row.get("skill_code")).isEqualTo("crm.lead.advance");
        assertThat(row.get("arg_hash")).isEqualTo("abc123hashprefix");
        assertThat(row.get("blast_radius")).isEqualTo("SHARED_STATE");
        assertThat(row.get("session_scope_key")).asString().contains("session_001");
    }

    @Test
    @DisplayName("GrantScope.matches honors effect / toolRefPattern / blastRadius / argHashConstraint")
    void grantScope_matchingRules() {
        ToolCallIntent intent = new ToolCallIntent(
                tenantId, runId, 0, 0,
                "dsl:cmd_crm_lead_transition",
                "crm.lead.advance",
                "plan_hash",
                Set.of(EffectClass.WRITE_PLATFORM_STATE),
                BlastRadius.SHARED_STATE,
                "abc123hashprefix",
                Map.of(),
                null);

        // exact match
        assertThat(new GrantScope(EffectClass.WRITE_PLATFORM_STATE,
                "dsl:cmd_crm_lead_transition", "crm.lead.advance",
                BlastRadius.SHARED_STATE, null, EffectLifetime.PER_TURN, "p", 1)
                .matches(intent)).isTrue();

        // glob match
        assertThat(new GrantScope(EffectClass.WRITE_PLATFORM_STATE,
                "dsl:cmd_crm_*", null,
                BlastRadius.IRREVERSIBLE, null, EffectLifetime.PER_TURN, "p", 1)
                .matches(intent)).isTrue();

        // wrong effect
        assertThat(new GrantScope(EffectClass.READ_PLATFORM_DATA,
                "dsl:cmd_crm_*", null,
                BlastRadius.IRREVERSIBLE, null, EffectLifetime.PER_TURN, "p", 1)
                .matches(intent)).isFalse();

        // blast radius too high
        assertThat(new GrantScope(EffectClass.WRITE_PLATFORM_STATE,
                null, null,
                BlastRadius.REVERSIBLE, null, EffectLifetime.PER_TURN, "p", 1)
                .matches(intent)).isFalse();

        // argHash prefix matches
        assertThat(new GrantScope(EffectClass.WRITE_PLATFORM_STATE,
                null, null, BlastRadius.IRREVERSIBLE,
                "abc123", EffectLifetime.PER_TURN, "p", 1)
                .matches(intent)).isTrue();

        // argHash prefix doesn't match
        assertThat(new GrantScope(EffectClass.WRITE_PLATFORM_STATE,
                null, null, BlastRadius.IRREVERSIBLE,
                "zzz", EffectLifetime.PER_TURN, "p", 1)
                .matches(intent)).isFalse();
    }
}
