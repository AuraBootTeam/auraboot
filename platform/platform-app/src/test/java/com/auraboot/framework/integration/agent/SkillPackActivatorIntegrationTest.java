package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.SkillPackActivator;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-30: SkillPack Activation Filter.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("SkillPackActivator (PR-30)")
class SkillPackActivatorIntegrationTest extends BaseIntegrationTest {

    @Autowired private SkillPackActivator activator;
    @Autowired private JdbcTemplate jdbc;

    private Long tenantId;

    @BeforeEach
    void setup() {
        tenantId = 9_050_000L + System.nanoTime() % 100_000;
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_skill_pack_binding WHERE tenant_id = ?", tenantId);
        jdbc.update("DELETE FROM ab_agent_skill_pack WHERE tenant_id = ?", tenantId);
    }

    private String seedPack(String code, String skillsJson) {
        String pid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_skill_pack " +
                        "(pid, tenant_id, pack_code, pack_name, skill_codes, active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?::jsonb, TRUE, NOW(), NOW())",
                pid, tenantId, code, code, skillsJson);
        return pid;
    }

    private void bind(String packPid, String profileId, String channel, String runKind) {
        jdbc.update("INSERT INTO ab_agent_skill_pack_binding " +
                        "(pid, tenant_id, pack_pid, profile_id, channel, run_kind, priority, active, " +
                        " created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, 100, TRUE, NOW(), NOW())",
                UniqueIdGenerator.generate(), tenantId, packPid, profileId, channel, runKind);
    }

    // -----------------------------------------------------------------------

    @Test
    @DisplayName("no bindings configured → candidates pass through unchanged (progressive rollout)")
    void no_bindings_passes_through() {
        List<String> candidates = List.of("dsl.query", "dsl.command", "crm.lead.update");
        SkillPackActivator.ActivationResult r = activator.filter(
                tenantId, "prof_sales", "web", "interactive", candidates);
        assertThat(r.getActivatedCandidates()).isEqualTo(candidates);
        assertThat(r.getRemovedCount()).isZero();
        assertThat(r.getReason()).isEqualTo("no_bindings_configured");
    }

    @Test
    @DisplayName("single binding — only its skills pass; others dropped")
    void single_binding_filters() {
        String crmPack = seedPack("crm-read",
                "[\"dsl.query\", \"crm.lead.list\", \"crm.account.list\"]");
        bind(crmPack, null, null, null); // match any

        List<String> candidates = List.of("dsl.query", "crm.lead.list", "dsl.command", "hr.delete_user");
        SkillPackActivator.ActivationResult r = activator.filter(
                tenantId, "prof_sales", "web", "interactive", candidates);
        assertThat(r.getActivatedCandidates()).containsExactly("dsl.query", "crm.lead.list");
        assertThat(r.getRemovedCount()).isEqualTo(2);
        assertThat(r.getReason()).isEqualTo("filter_applied");
    }

    @Test
    @DisplayName("union of multiple matching packs — all skills from both packs are allowed")
    void union_of_packs() {
        String pack1 = seedPack("crm-read", "[\"crm.lead.list\"]");
        String pack2 = seedPack("crm-write", "[\"crm.lead.update\"]");
        bind(pack1, null, null, null);
        bind(pack2, null, null, null);

        SkillPackActivator.ActivationResult r = activator.filter(
                tenantId, null, null, null,
                List.of("crm.lead.list", "crm.lead.update", "dsl.query"));
        assertThat(r.getActivatedCandidates()).containsExactlyInAnyOrder("crm.lead.list", "crm.lead.update");
        assertThat(r.getRemovedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("binding dimensions — profile-specific binding only activates for that profile")
    void profile_scoped_binding() {
        String salesPack = seedPack("sales-only", "[\"crm.lead.assign\"]");
        bind(salesPack, "prof_sales", null, null);

        SkillPackActivator.ActivationResult sales = activator.filter(
                tenantId, "prof_sales", "web", "interactive",
                List.of("crm.lead.assign", "dsl.query"));
        assertThat(sales.getActivatedCandidates()).containsExactly("crm.lead.assign");

        // Different profile — the binding doesn't match; no other bindings exist → progressive permit.
        SkillPackActivator.ActivationResult service = activator.filter(
                tenantId, "prof_service", "web", "interactive",
                List.of("crm.lead.assign", "dsl.query"));
        assertThat(service.getReason()).isEqualTo("no_bindings_configured");
        assertThat(service.getActivatedCandidates()).hasSize(2);
    }

    @Test
    @DisplayName("inactive bindings are ignored")
    void inactive_binding_ignored() {
        String pack = seedPack("p", "[\"crm.lead.list\"]");
        String bindingPid = UniqueIdGenerator.generate();
        jdbc.update("INSERT INTO ab_agent_skill_pack_binding " +
                        "(pid, tenant_id, pack_pid, priority, active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 100, FALSE, NOW(), NOW())",
                bindingPid, tenantId, pack);

        // Effectively no active bindings → progressive permit.
        SkillPackActivator.ActivationResult r = activator.filter(
                tenantId, null, null, null, List.of("dsl.query"));
        assertThat(r.getReason()).isEqualTo("no_bindings_configured");
    }

    @Test
    @DisplayName("inactive pack is ignored even if binding is active")
    void inactive_pack_ignored() {
        String pack = seedPack("p", "[\"crm.lead.list\"]");
        jdbc.update("UPDATE ab_agent_skill_pack SET active = FALSE WHERE pid = ?", pack);
        bind(pack, null, null, null);

        // Binding joins the pack which is inactive → no packs resolve → progressive permit.
        SkillPackActivator.ActivationResult r = activator.filter(
                tenantId, null, null, null, List.of("dsl.query"));
        assertThat(r.getReason()).isEqualTo("no_bindings_configured");
    }

    @Test
    @DisplayName("empty candidate list returns empty without touching DB")
    void empty_candidates_short_circuit() {
        SkillPackActivator.ActivationResult r = activator.filter(
                tenantId, null, null, null, List.of());
        assertThat(r.getActivatedCandidates()).isEmpty();
        assertThat(r.getReason()).isEqualTo("empty_input");
    }

    @Test
    @DisplayName("tenant isolation — rules from other tenants don't apply")
    void tenant_isolation() {
        String pack = seedPack("p", "[\"dsl.query\"]");
        bind(pack, null, null, null);

        Long otherTenant = tenantId + 1_000_000;
        // Other tenant has no bindings → progressive permit.
        SkillPackActivator.ActivationResult r = activator.filter(
                otherTenant, null, null, null, List.of("dsl.query", "dsl.command"));
        assertThat(r.getReason()).isEqualTo("no_bindings_configured");
        assertThat(r.getActivatedCandidates()).hasSize(2);
    }
}
