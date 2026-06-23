package com.auraboot.framework.agent;

import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.provider.DslToolProvider;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase-2 device operations agent — runtime seam + confirmation-gate gates (deterministic,
 * no LLM key). Two things the phase-2 slice must prove without the full device runtime stack:
 *
 * <ol>
 *   <li><b>seed</b> — {@code device_operations_agent} imports with {@code allowedOperations}
 *       including {@code execute} and the device write commands in its tool scope.</li>
 *   <li><b>confirmation gate</b> — a device control command at {@code L3} and an alarm
 *       ack/clear at {@code L2} produce agent tools that require approval / confirmation, so
 *       the platform's confirmation gate actually engages for device writes. (This is why the
 *       iot commands' riskLevels were set: without them, the writes default to L1 = no gate.)</li>
 * </ol>
 */
@DisplayName("Phase-2 device operations agent: seed (execute scope) + confirmation gate")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeviceOperationsAgentIT extends BaseIntegrationTest {

    private static final String AGENT_CODE = "device_operations_agent";
    private static final List<String> DEVICE_WRITE_TOOLS = List.of(
            "cmd:iot_device:invoke_service", "cmd:iot_alarm_event:ack", "cmd:iot_alarm_event:clear");

    @Autowired private PluginResourceImporter resourceImporter;
    @Autowired private AgentDefinitionMapper agentDefinitionMapper;
    @Autowired private DslToolProvider dslToolProvider;
    @Autowired private DynamicDataMapper dynamicDataMapper;
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
    @DisplayName("imports device_operations_agent with execute scope and device write tools")
    void importsOperationsAgentWithExecuteScope() {
        AgentDefinitionDTO dto = AgentDefinitionDTO.builder()
                .agentCode(AGENT_CODE)
                .name("Device Operations Agent")
                .description("Diagnose read-first, execute device control with confirmation.")
                .agentType("reactive")
                .model("deepseek-chat")
                .systemPrompt("Diagnose first; execute control actions only after explicit user confirmation.")
                .tools(List.of(
                        "list:iot_alarm_event", "list:iot_device", "nq:pe_andon_open_stats",
                        "cmd:iot_device:invoke_service", "cmd:iot_alarm_event:ack", "cmd:iot_alarm_event:clear"))
                .skills(List.of("dsl.query", "dsl.command"))
                .allowedModels(List.of("iot_alarm_event", "iot_device", "mfg_equipment_pcba_asset"))
                .allowedOperations(List.of("query", "execute"))
                .maxTools(16)
                .status("active")
                .visibility("tenant")
                .build();

        assertTrue(dto.isValid(), "operations agent DTO must pass the importer's validity gate");

        resourceImporter.importAgentDefinition(
                dto, "test-pcba-mfg-pid", "test-import-id", tenantId, ImportRequest.ConflictStrategy.OVERWRITE);

        AgentDefinition seeded = agentDefinitionMapper.selectOne(
                new LambdaQueryWrapper<AgentDefinition>()
                        .eq(AgentDefinition::getTenantId, tenantId)
                        .eq(AgentDefinition::getAgentCode, AGENT_CODE)
                        .eq(AgentDefinition::getDeletedFlag, false));

        assertNotNull(seeded, "device operations agent must be persisted");
        assertEquals("active", seeded.getStatus());
        assertEquals(List.of("query", "execute"), seeded.getAllowedOperations(),
                "operations agent must carry execute scope (not query-only)");
        String toolsJson = seeded.getTools() == null ? "" : seeded.getTools();
        for (String write : DEVICE_WRITE_TOOLS) {
            assertTrue(toolsJson.contains(write),
                    "operations agent tool scope must include device write tool " + write + ", tools=" + toolsJson);
        }
    }

    @Test
    @DisplayName("device control command (L3) requires approval; alarm ack (L2) requires confirmation")
    void deviceWriteCommandsAreConfirmationGated() {
        long n = System.nanoTime();
        String modelCode = "dops_model_" + n;
        // L3 device-control command (mirrors iot_device:invoke_service)
        String invokeCode = "dops_device_" + n + ":invoke_service";
        // L2 alarm command (mirrors iot_alarm_event:ack)
        String ackCode = "dops_alarm_" + n + ":ack";

        insertCommand(invokeCode, modelCode, "L3");
        insertCommand(ackCode, modelCode, "L2");

        List<ToolDefinition> tools = dslToolProvider.discover(ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .modelHint(modelCode)
                .maxResults(20)
                .build());

        ToolDefinition invokeTool = tools.stream()
                .filter(t -> ("cmd:" + invokeCode).equals(t.getToolCode()))
                .findFirst().orElseThrow(() -> new AssertionError("invoke_service tool not discovered"));
        assertEquals("L3", invokeTool.getRiskLevel());
        assertTrue(invokeTool.isRequiresApproval(),
                "an L3 device-control command must require approval (confirmation gate engaged)");
        assertEquals("confirm_with_detail", invokeTool.getConfirmationPolicy(),
                "L3 confirmation policy must be confirm_with_detail");

        ToolDefinition ackTool = tools.stream()
                .filter(t -> ("cmd:" + ackCode).equals(t.getToolCode()))
                .findFirst().orElseThrow(() -> new AssertionError("alarm ack tool not discovered"));
        assertEquals("L2", ackTool.getRiskLevel());
        assertTrue(ackTool.isRequiresConfirmation(),
                "an L2 alarm command must require confirmation");
        assertEquals("confirm", ackTool.getConfirmationPolicy());
    }

    private void insertCommand(String code, String modelCode, String riskLevel) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("pid", "dops_cmd_" + System.nanoTime());
        command.put("tenant_id", tenantId);
        command.put("code", code);
        command.put("display_name", code);
        command.put("description", "Device control fixture " + code);
        command.put("model_code", modelCode);
        command.put("input_schema", "{}");
        command.put("target_models", "[]");
        command.put("execution_config", "{\"type\":\"custom\"}");
        command.put("extension", "{}");
        command.put("cmd_risk_level", riskLevel);
        command.put("version", 1);
        command.put("is_current", true);
        command.put("status", "published");
        command.put("deleted_flag", false);
        dynamicDataMapper.insertWithJsonb("ab_command_definition", command,
                Set.of("input_schema", "target_models", "execution_config", "extension"));
    }
}
