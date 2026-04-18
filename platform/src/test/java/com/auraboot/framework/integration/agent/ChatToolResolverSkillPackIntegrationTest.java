package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.port.GroundingPort;
import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import com.auraboot.framework.aurabot.service.ChatToolResolver;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Commit;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * PR-36: SkillPack Activation Filter wired into ChatToolResolver. Verifies
 * the filter narrows the candidate list handed to ToolDiscoveryPort.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("ChatToolResolver ↔ SkillPackActivator (PR-36)")
class ChatToolResolverSkillPackIntegrationTest extends BaseIntegrationTest {

    @Autowired private ChatToolResolver resolver;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private GroundingPort groundingPort;
    @MockBean private ToolDiscoveryPort toolDiscoveryPort;

    private Long tenantId;

    @BeforeEach
    void setup() {
        // Use a dedicated tenant id but don't rely on MetaContext — the
        // current-tenant fallback is what ChatToolResolver reads. Test DB
        // state lives on this tenant id via direct SQL fixtures.
        tenantId = 9_450_000L + System.nanoTime() % 100_000;
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

    private void bindAnyDim(String packPid) {
        jdbc.update("INSERT INTO ab_agent_skill_pack_binding " +
                        "(pid, tenant_id, pack_pid, priority, active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 100, TRUE, NOW(), NOW())",
                UniqueIdGenerator.generate(), tenantId, packPid);
    }

    @Test
    @DisplayName("tenant with bindings → candidate skills get narrowed before discoverTools()")
    void pack_filter_narrows_candidates() {
        String pack = seedPack("crm-read", "[\"crm.lead.list\", \"dsl.query\"]");
        bindAnyDim(pack);

        var grounding = new com.auraboot.framework.agent.port.GroundingPort.GroundingResult(
                "query", "crm_lead", 0.9,
                List.of("crm.lead.list", "dsl.query", "crm.lead.update", "hr.delete_user"),
                true);
        when(groundingPort.ground(anyLong(), any(), any(), any())).thenReturn(grounding);
        when(toolDiscoveryPort.discoverTools(anyLong(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        // ChatToolResolver reads MetaContext — seed via ThreadLocal for this call.
        com.auraboot.framework.application.tenant.MetaContext.setCurrentTenantId(tenantId);
        try {
            resolver.resolveTools("list my leads", "crm_lead", null);
        } finally {
            com.auraboot.framework.application.tenant.MetaContext.clear();
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(toolDiscoveryPort).discoverTools(
                eq(tenantId), captor.capture(), any(), any(), anyInt());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("crm.lead.list", "dsl.query");
    }

    @Test
    @DisplayName("tenant without bindings → candidate list passes through unchanged")
    void no_bindings_passthrough() {
        var grounding = new com.auraboot.framework.agent.port.GroundingPort.GroundingResult(
                "query", "crm_lead", 0.9,
                List.of("crm.lead.list", "hr.delete_user"),
                true);
        when(groundingPort.ground(anyLong(), any(), any(), any())).thenReturn(grounding);
        when(toolDiscoveryPort.discoverTools(anyLong(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        com.auraboot.framework.application.tenant.MetaContext.setCurrentTenantId(tenantId);
        try {
            resolver.resolveTools("anything", "crm_lead", null);
        } finally {
            com.auraboot.framework.application.tenant.MetaContext.clear();
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(toolDiscoveryPort).discoverTools(
                eq(tenantId), captor.capture(), any(), any(), anyInt());
        assertThat(captor.getValue()).containsExactly("crm.lead.list", "hr.delete_user");
    }
}
