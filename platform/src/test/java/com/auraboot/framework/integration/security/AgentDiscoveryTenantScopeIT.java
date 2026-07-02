package com.auraboot.framework.integration.security;

import com.auraboot.framework.agent.service.AgentCardService;
import com.auraboot.framework.application.tenant.MetaContext;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REG-3 regression guard (DDR-2026-06-30): A2A discovery is tenant-scoped. Previously the
 * {@code /.well-known/agent.json} endpoints were anonymous (WhiteList) and {@link AgentCardService}
 * filtered only {@code status='active'} — leaking every tenant's agent metadata. The endpoints are
 * now removed from the WhiteList (auth required) and the queries are scoped to the caller's tenant.
 * This IT locks the tenant scoping: discovery and single-card lookup never cross tenants.
 */
@Commit
@Transactional(propagation = Propagation.NEVER)
@DisplayName("A2A discovery is tenant-scoped (REG-3)")
class AgentDiscoveryTenantScopeIT extends BaseIntegrationTest {

    @Autowired private AgentCardService agentCardService;
    @Autowired private JdbcTemplate jdbc;

    private String ownCode;
    private String foreignCode;
    private Long foreignTenantId;

    @BeforeEach
    void setup() {
        MetaContext.setContext(testTenant.getId(), testUser.getId(),
                testUser.getPid(), testUser.getUserName());
        foreignTenantId = jdbc.queryForObject(
                "SELECT id FROM ab_tenant WHERE id <> ? AND deleted_flag = false ORDER BY id LIMIT 1",
                Long.class, testTenant.getId());
        assertThat(foreignTenantId).as("test needs a second tenant").isNotNull();

        ownCode = "reg3own" + (System.nanoTime() % 1_000_000_000L);
        foreignCode = "reg3fgn" + (System.nanoTime() % 1_000_000_000L);
        insertAgent(testTenant.getId(), ownCode);
        insertAgent(foreignTenantId, foreignCode);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM ab_agent_definition WHERE agent_code IN (?, ?)", ownCode, foreignCode);
        MetaContext.clear();
    }

    private void insertAgent(Long tenantId, String code) {
        jdbc.update(
                "INSERT INTO ab_agent_definition (pid, tenant_id, agent_code, name, agent_type, status, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, 'reactive', 'active', false)",
                "ad_" + code, tenantId, code, "reg3_agent_" + code);
    }

    @Test
    @DisplayName("discovery lists only the caller tenant's agents; foreign agent card is not resolvable")
    void discoveryAndCard_areTenantScoped() {
        // Discovery (caller = testTenant) must include own agent, exclude the foreign one.
        Map<String, Object> doc = agentCardService.generateDiscoveryDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> agents = (List<Map<String, Object>>) doc.get("agents");
        List<String> codes = agents.stream().map(a -> (String) a.get("code")).toList();
        assertThat(codes).as("own-tenant agent must be discoverable").contains(ownCode);
        assertThat(codes).as("foreign-tenant agent must NOT leak into discovery").doesNotContain(foreignCode);

        // Single-card lookup is tenant-scoped too.
        assertThat(agentCardService.generateAgentCard(ownCode)).as("own agent card resolves").isNotNull();
        assertThat(agentCardService.generateAgentCard(foreignCode))
                .as("foreign agent card must NOT resolve from another tenant").isNull();
    }
}
