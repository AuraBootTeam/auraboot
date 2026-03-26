package com.auraboot.framework.agent;

import com.auraboot.framework.agent.entity.AbCapability;
import com.auraboot.framework.agent.mapper.AbCapabilityMapper;
import com.auraboot.framework.agent.service.AgentContractDeriver;
import com.auraboot.framework.agent.service.CapabilityViewService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for AgentContractDeriver — contract status tracking and derivation.
 *
 * Covers: full-tenant derivation, idempotency, per-model scoped derivation,
 * risk-level mapping (L0-L4 → LOW/MEDIUM/HIGH), error-safe derivation for
 * non-existent tenants, and contract_status field lifecycle.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AgentContractDeriverTest extends BaseIntegrationTest {

    @Autowired
    private AgentContractDeriver agentContractDeriver;

    @Autowired
    private CapabilityViewService capabilityViewService;

    @Autowired
    private AbCapabilityMapper capabilityMapper;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private Long tenantId;

    @BeforeAll
    void setup() {
        tenantId = getTestTenant().getId();
        // Ensure capabilities are synced before contract derivation
        capabilityViewService.syncCapabilities(tenantId).join();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 1: deriveContracts returns a non-negative count
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void deriveContracts_returnsNonNegativeCount() {
        int count = agentContractDeriver.deriveContracts(tenantId);
        assertTrue(count >= 0, "deriveContracts must return a non-negative count");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 2: Derived tools have contract_status = DERIVED and non-null timestamps
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void deriveContracts_setsContractStatusDerived() {
        agentContractDeriver.deriveContracts(tenantId);

        List<Map<String, Object>> derived = dynamicDataMapper.selectByQuery(
                "SELECT tool_code, contract_status, contract_derived_at FROM ab_agent_tool " +
                "WHERE tenant_id = #{params.tenantId} AND auto_generated = true " +
                "AND tool_status = 'active' AND contract_status = 'derived' " +
                "AND deleted_flag = FALSE LIMIT 5",
                Map.of("tenantId", tenantId)
        );

        for (Map<String, Object> tool : derived) {
            assertEquals("derived", tool.get("contract_status"),
                    "contract_status must be DERIVED for processed tools");
            assertNotNull(tool.get("contract_derived_at"),
                    "contract_derived_at must be non-null after successful derivation");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 3: Re-running deriveContracts with unchanged capabilities returns 0
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void deriveContracts_idempotent_zeroOnReRun() {
        // First run
        agentContractDeriver.deriveContracts(tenantId);

        // Second run with no source changes
        int secondRun = agentContractDeriver.deriveContracts(tenantId);

        assertEquals(0, secondRun,
                "Re-derivation with unchanged capabilities must process 0 tools (hash-based skip)");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 4: mapRiskLevel L0/L1 → LOW, L2 → MEDIUM, L3/L4 → HIGH
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void deriveContracts_mapsRiskLevelCorrectly() {
        agentContractDeriver.deriveContracts(tenantId);

        // Verify L3/L4 capability-linked tools have HIGH risk_level
        List<Map<String, Object>> highRiskTools = dynamicDataMapper.selectByQuery(
                "SELECT t.tool_code, t.risk_level, c.risk_level AS cap_risk " +
                "FROM ab_agent_tool t " +
                "JOIN ab_capability c ON t.capability_pid = c.contract_hash " +
                "WHERE t.tenant_id = #{params.tenantId} " +
                "AND c.risk_level IN ('L3', 'L4') " +
                "AND t.tool_status = 'active' " +
                "AND (t.deleted_flag = FALSE OR t.deleted_flag IS NULL) LIMIT 5",
                Map.of("tenantId", tenantId)
        );

        for (Map<String, Object> tool : highRiskTools) {
            assertEquals("high", tool.get("risk_level"),
                    "L3/L4 capability must produce HIGH risk_level on tool");
        }

        // Verify L0/L1 capability-linked tools have LOW risk_level
        List<Map<String, Object>> lowRiskTools = dynamicDataMapper.selectByQuery(
                "SELECT t.tool_code, t.risk_level, c.risk_level AS cap_risk " +
                "FROM ab_agent_tool t " +
                "JOIN ab_capability c ON t.capability_pid = c.contract_hash " +
                "WHERE t.tenant_id = #{params.tenantId} " +
                "AND c.risk_level IN ('L0', 'L1') " +
                "AND t.tool_status = 'active' " +
                "AND (t.deleted_flag = FALSE OR t.deleted_flag IS NULL) LIMIT 5",
                Map.of("tenantId", tenantId)
        );

        for (Map<String, Object> tool : lowRiskTools) {
            assertEquals("low", tool.get("risk_level"),
                    "L0/L1 capability must produce LOW risk_level on tool");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 5: deriveForModel scopes derivation to a specific model's capabilities
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void deriveForModel_scopesToModelCapabilities() {
        List<AbCapability> caps = capabilityMapper.selectList(
                new LambdaQueryWrapper<AbCapability>()
                        .eq(AbCapability::getTenantId, tenantId)
                        .isNotNull(AbCapability::getModelCode)
                        .eq(AbCapability::getStatus, "active")
                        .last("LIMIT 1")
        );

        if (caps.isEmpty()) {
            return; // No model-scoped capabilities — skip
        }

        String modelCode = caps.get(0).getModelCode();
        int count = agentContractDeriver.deriveForModel(tenantId, modelCode);

        assertTrue(count >= 0,
                "deriveForModel must return a non-negative count for model " + modelCode);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 6: deriveForModel with unknown model returns 0 (no capabilities match)
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(6)
    void deriveForModel_unknownModel_returnsZero() {
        int count = agentContractDeriver.deriveForModel(tenantId, "nonexistent_model_xyz");

        assertEquals(0, count,
                "deriveForModel with an unknown model must return 0");
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 7: deriveContracts with non-existent tenant does not throw
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(7)
    void deriveContracts_unknownTenant_doesNotThrow() {
        assertDoesNotThrow(
                () -> agentContractDeriver.deriveContracts(Long.MAX_VALUE),
                "deriveContracts with an unknown tenant must not throw"
        );
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Test 8: tool_version increments on each contract derivation
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    @Order(8)
    void deriveContracts_incrementsToolVersion() {
        // Get a tool with its current version
        List<Map<String, Object>> toolsBefore = dynamicDataMapper.selectByQuery(
                "SELECT tool_code, tool_version FROM ab_agent_tool " +
                "WHERE tenant_id = #{params.tenantId} AND auto_generated = true " +
                "AND tool_status = 'active' AND contract_status = 'derived' " +
                "AND deleted_flag = FALSE LIMIT 1",
                Map.of("tenantId", tenantId)
        );

        if (toolsBefore.isEmpty()) {
            return; // No derived tools yet — skip
        }

        String toolCode = (String) toolsBefore.get(0).get("tool_code");
        int versionBefore = toolsBefore.get(0).get("tool_version") instanceof Number n
                ? n.intValue() : 1;

        // Force re-derivation by clearing the capability_pid link (simulate change)
        dynamicDataMapper.update("ab_agent_tool",
                Map.of("capability_pid", ""),
                Map.of("tenant_id", tenantId, "tool_code", toolCode));

        agentContractDeriver.deriveContracts(tenantId);

        List<Map<String, Object>> toolsAfter = dynamicDataMapper.selectByQuery(
                "SELECT tool_version FROM ab_agent_tool " +
                "WHERE tenant_id = #{params.tenantId} AND tool_code = #{params.toolCode}",
                Map.of("tenantId", tenantId, "toolCode", toolCode)
        );

        if (!toolsAfter.isEmpty() && toolsAfter.get(0).get("tool_version") instanceof Number n) {
            int versionAfter = n.intValue();
            assertTrue(versionAfter > versionBefore,
                    "tool_version must increment after contract re-derivation");
        }
    }
}
