package com.auraboot.framework.tenant;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.tenant.service.TenantBootstrapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test: verifies that TenantBootstrapServiceImpl seeds an AuraBot agent
 * for new tenants during bootstrapTenant().
 *
 * <p>Each test first calls bootstrapTenant() to trigger the AuraBot seeding step,
 * then asserts against ab_agent_definition. The @Rollback(true) annotation on the
 * base class ensures the DB is clean between tests.
 */
@DisplayName("TenantBootstrap — AuraBot integration")
@Transactional
@Rollback(true)
class TenantBootstrapAuraBotIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private TenantBootstrapService tenantBootstrapService;

    /**
     * Bootstrap the test tenant so that createAuraBotAgent() is called.
     * The test tenant is created by BaseIntegrationTest via createTenant() —
     * not bootstrapTenant() — so we must run bootstrap explicitly here.
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
    void existingTenant_hasAuraBotAgent() {
        Long tenantId = testTenant.getId();

        String sql = "SELECT agent_code, name, status FROM ab_agent_definition " +
            "WHERE tenant_id = #{params.tenantId} AND agent_code = #{params.agentCode} " +
            "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";

        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
            sql, Map.of("tenantId", tenantId, "agentCode", "aurabot"));

        assertThat(rows)
            .as("AuraBot agent row must exist for tenant %d", tenantId)
            .isNotEmpty();

        Map<String, Object> agent = rows.get(0);
        assertThat(agent.get("agent_code"))
            .as("agent_code must be 'aurabot'")
            .isEqualTo("aurabot");
        assertThat(agent.get("status"))
            .as("AuraBot agent must be active")
            .isEqualTo("active");
    }

    @Test
    @DisplayName("AuraBot agent has correct configuration values")
    void auraBotAgent_hasCorrectConfig() {
        Long tenantId = testTenant.getId();

        String sql = "SELECT agent_type, model, max_concurrent_runs, execution_timeout_seconds " +
            "FROM ab_agent_definition " +
            "WHERE tenant_id = #{params.tenantId} AND agent_code = #{params.agentCode} " +
            "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";

        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
            sql, Map.of("tenantId", tenantId, "agentCode", "aurabot"));

        assertThat(rows)
            .as("AuraBot config row must exist for tenant %d", tenantId)
            .isNotEmpty();

        Map<String, Object> agent = rows.get(0);
        assertThat(agent.get("agent_type"))
            .as("agent_type must be 'reactive'")
            .isEqualTo("reactive");
        assertThat(agent.get("model"))
            .as("model should be null (resolved at runtime from first enabled LLM provider)")
            .isNull();
        assertThat(((Number) agent.get("max_concurrent_runs")).intValue())
            .as("max_concurrent_runs must be > 0")
            .isGreaterThan(0);
        assertThat(((Number) agent.get("execution_timeout_seconds")).intValue())
            .as("execution_timeout_seconds must be > 0")
            .isGreaterThan(0);
    }

    @Test
    @DisplayName("createAuraBotAgent is idempotent — exactly one agent row exists after bootstrap")
    void auraBotAgent_isIdempotent() {
        Long tenantId = testTenant.getId();

        // @BeforeEach already called bootstrapTenant() once.
        // Assert that exactly one AuraBot agent row exists — the idempotency guard in
        // createAuraBotAgent() must have prevented any duplicate insertion.
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
