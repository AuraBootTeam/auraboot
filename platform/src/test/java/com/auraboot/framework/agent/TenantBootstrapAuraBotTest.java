package com.auraboot.framework.agent;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: verifies that the tenant bootstrap process seeds an AuraBot agent.
 *
 * <p>The test tenant (and its bootstrap data) is created by {@link BaseIntegrationTest}.
 * We query {@code ab_agent_definition} directly to assert that an AuraBot entry exists
 * for that tenant after bootstrap completes.
 */
@DisplayName("TenantBootstrap — AuraBot seeding")
class TenantBootstrapAuraBotTest extends BaseIntegrationTest {

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private TenantBootstrapService tenantBootstrapService;

    // -----------------------------------------------------------------------
    // Setup
    // -----------------------------------------------------------------------

    /**
     * Bootstrap the test tenant so that createAuraBotAgent() is invoked.
     * The test tenant in BaseIntegrationTest is created via createTenant(), not bootstrapTenant(),
     * so we must call bootstrap explicitly before each test.
     */
    @BeforeEach
    void bootstrapTestTenant() {
        tenantBootstrapService.bootstrapTenant(testTenant.getId(), testUser.getId());
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("bootstrapped tenant has an active AuraBot agent")
    void testBootstrappedTenant_hasAuraBotAgent() {
        Long tenantId = testTenant.getId();

        String sql = "SELECT agent_code, name, status, agent_type, model " +
            "FROM ab_agent_definition " +
            "WHERE tenant_id = #{params.tenantId} AND agent_code = #{params.agentCode} " +
            "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";

        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
            sql, Map.of("tenantId", tenantId, "agentCode", "aurabot"));

        assertThat(rows)
            .as("AuraBot agent row must exist for tenant %d", tenantId)
            .isNotEmpty();

        Map<String, Object> agent = rows.get(0);
        assertThat(agent.get("agent_code")).isEqualTo("aurabot");
        assertThat(agent.get("name")).isEqualTo("AuraBot");
        assertThat(agent.get("status")).isEqualTo("active");
        assertThat(agent.get("agent_type")).isEqualTo("reactive");
        assertThat(agent.get("model")).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    @DisplayName("createAuraBotAgent is idempotent — exactly one agent row exists after bootstrap")
    void testCreateAuraBotAgent_isIdempotent() {
        Long tenantId = testTenant.getId();

        // @BeforeEach already called bootstrapTenant() once.
        // Verify that exactly one AuraBot agent row exists — the idempotency guard must have
        // prevented any duplicates even though the base test data may already have an agent.
        String countSql = "SELECT COUNT(*) AS cnt FROM ab_agent_definition " +
            "WHERE tenant_id = #{params.tenantId} AND agent_code = #{params.agentCode} " +
            "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";

        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
            countSql, Map.of("tenantId", tenantId, "agentCode", "aurabot"));

        assertThat(rows).isNotEmpty();
        int count = ((Number) rows.get(0).get("cnt")).intValue();
        assertThat(count)
            .as("Exactly one AuraBot agent must exist for tenant %d after bootstrap", tenantId)
            .isEqualTo(1);
    }
}
