package com.auraboot.framework.agent;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.service.impl.PluginResourceImporter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runtime seam gate for the device read-first agent <em>seed</em>: drives the real
 * {@link PluginResourceImporter#importAgentDefinition} path (the same path
 * {@code config/agent-definitions.json} flows through during
 * {@code import-directory-sync}) and asserts the row lands in
 * {@code ab_agent_definition} with its <strong>read-first scope intact</strong> —
 * {@code allowedOperations=[query]} and no device write/control command in its tools.
 *
 * <p>This closes the "门禁绿 ≠ 功能可用" gap for the config seed without standing up
 * the full pcba-manufacturing hybrid stack: it exercises the importer + DB seam with
 * a DTO that mirrors {@code plugins/pcba-manufacturing/config/agent-definitions.json}.
 * Deterministic — no LLM key required, runs in any {@code testAgent} pass.
 */
@DisplayName("Device read-first agent seed: importAgentDefinition lands a read-only agent")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeviceAgentSeedImportIT extends BaseIntegrationTest {

    private static final String AGENT_CODE = "device_diagnostics_agent";
    private static final List<String> READ_TOOLS = List.of(
            "nq:pe_andon_open_stats",
            "list:iot_alarm_event",
            "list:iot_device",
            "get:iot_device",
            "list:mfg_operation_exception_pcba_execution",
            "list:mfg_equipment_pcba_asset",
            "get:mfg_equipment_pcba_asset",
            "list:mfg_equipment_downtime_pcba_asset");
    // a read-first agent must never carry any of these in its tool scope.
    private static final List<String> DEVICE_WRITE_COMMANDS =
            List.of("iot_device:invoke_service", "iot_alarm_event:ack", "iot_alarm_event:clear");

    @Autowired private PluginResourceImporter resourceImporter;
    @Autowired private AgentDefinitionMapper agentDefinitionMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Long tenantId;

    @BeforeEach
    void cleanSlate() {
        tenantId = getTestTenant().getId();
        jdbcTemplate.update("DELETE FROM ab_agent_definition WHERE tenant_id=? AND agent_code=?",
                tenantId, AGENT_CODE);
    }

    @AfterEach
    void cleanup() {
        if (tenantId != null) {
            jdbcTemplate.update("DELETE FROM ab_agent_definition WHERE tenant_id=? AND agent_code=?",
                    tenantId, AGENT_CODE);
        }
    }

    @Test
    @DisplayName("imports the read-first device agent with query-only scope and no write tools")
    void importsReadFirstDeviceAgent() {
        AgentDefinitionDTO dto = AgentDefinitionDTO.builder()
                .agentCode(AGENT_CODE)
                .name("Device Diagnostics Agent")
                .description("Read-first device diagnostic agent (alarms / andon / equipment status; advise-only).")
                .agentType("reactive")
                .model("deepseek-chat")
                .systemPrompt("You are the read-first Device Diagnostics Agent. Gather evidence, diagnose, advise only — never execute device control or write actions.")
                .tools(READ_TOOLS)
                .skills(List.of("dsl.query"))
                .guardrails(Map.of(
                        "evidenceFirst", true,
                        "writePolicy", "Read-only diagnostic agent. Never execute device control or write commands."))
                .allowedModels(List.of(
                        "iot_alarm_event", "iot_device", "iot_device_shadow",
                        "mfg_operation_exception_pcba_execution", "mfg_equipment_pcba_asset",
                        "mfg_equipment_downtime_pcba_asset"))
                .allowedOperations(List.of("query"))
                .maxTools(12)
                .status("active")
                .visibility("tenant")
                .build();

        // the importer's own validity gate must accept it (agentCode + name present).
        assertTrue(dto.isValid(), "seed DTO must pass the importer's validity gate");

        resourceImporter.importAgentDefinition(
                dto, "test-pcba-mfg-pid", "test-import-id", tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        AgentDefinition seeded = agentDefinitionMapper.selectOne(
                new LambdaQueryWrapper<AgentDefinition>()
                        .eq(AgentDefinition::getTenantId, tenantId)
                        .eq(AgentDefinition::getAgentCode, AGENT_CODE)
                        .eq(AgentDefinition::getDeletedFlag, false));

        assertNotNull(seeded, "device agent definition must be persisted to ab_agent_definition");
        assertEquals("active", seeded.getStatus(), "seeded agent must be active");
        assertEquals(List.of("query"), seeded.getAllowedOperations(),
                "read-first agent must be query-only (no create/update/delete)");

        // read-first boundary at the persisted scope: no device write/control command in tools.
        String toolsJson = seeded.getTools() == null ? "" : seeded.getTools();
        for (String write : DEVICE_WRITE_COMMANDS) {
            assertTrue(!toolsJson.contains(write),
                    "read-first agent's tool scope must NOT contain write command " + write
                            + ", tools=" + toolsJson);
        }
        // and the read tools it diagnoses with must survive the round-trip.
        assertTrue(toolsJson.contains("list:iot_alarm_event") && toolsJson.contains("nq:pe_andon_open_stats"),
                "read tools must persist, tools=" + toolsJson);
    }
}
