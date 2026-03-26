package com.auraboot.framework.agent;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for AuraBot agent seed data.
 * Verifies that the 'aurabot' agent definition is correctly seeded
 * with all required fields for active tenants.
 */
class AuraBotAgentSeedTest extends BaseIntegrationTest {

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Test
    void aurabotAgentDefinition_existsForActiveTenants() {
        Long tenantId = getTestTenant().getId();
        String sql = "SELECT agent_code, name, status FROM ab_agent_definition " +
                "WHERE agent_code = 'aurabot' AND tenant_id = #{params.tenantId} " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                sql, Map.of("tenantId", tenantId));

        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("agent_code")).isEqualTo("aurabot");
        assertThat(rows.get(0).get("name")).isEqualTo("AuraBot");
        assertThat(rows.get(0).get("status")).isEqualTo("active");
    }

    @Test
    void aurabotAgentDefinition_hasRequiredFields() {
        Long tenantId = getTestTenant().getId();
        String sql = "SELECT agent_code, name, agent_type, model, max_concurrent_runs, execution_timeout_seconds " +
                "FROM ab_agent_definition WHERE agent_code = 'aurabot' AND tenant_id = #{params.tenantId} " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL)";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQueryWithoutTenant(
                sql, Map.of("tenantId", tenantId));

        assertThat(rows).isNotEmpty();
        Map<String, Object> aurabot = rows.get(0);
        assertThat(aurabot.get("agent_type")).isNotNull();
        assertThat(aurabot.get("max_concurrent_runs")).isNotNull();
        assertThat(((Number) aurabot.get("execution_timeout_seconds")).intValue()).isGreaterThan(0);
    }
}
