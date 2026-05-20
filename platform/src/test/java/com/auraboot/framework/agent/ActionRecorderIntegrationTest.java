package com.auraboot.framework.agent;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.agent.service.ActionRecorder;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ActionRecorderIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ActionRecorder actionRecorder;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private String createCommandCode;
    private String updateCommandCode;
    private String leadListQueryCode;

    @BeforeEach
    void seedCommandDefinitions() throws Exception {
        Long tenantId = getTestTenant().getId();
        String suffix = UniqueIdGenerator.generate();
        createCommandCode = "crm:create_lead_" + suffix;
        updateCommandCode = "crm:update_lead_" + suffix;
        leadListQueryCode = "crm_lead_list_" + suffix;

        // Seed crm:create_lead command definition
        insertCommandDef(tenantId, createCommandCode, "crm_lead",
                objectMapper.writeValueAsString(Map.of("type", "create")));

        // Seed crm:update_lead command definition
        insertCommandDef(tenantId, updateCommandCode, "crm_lead",
                objectMapper.writeValueAsString(Map.of("type", "update")));

        // Seed ab_named_query for crm_lead_list
        insertNamedQuery(tenantId, leadListQueryCode, "SELECT * FROM mt_crm_lead WHERE tenant_id = :tenantId");
    }

    @AfterEach
    void cleanupCommandDefinitions() {
        Long tenantId = getTestTenant().getId();
        cleanupCommandDefinition(tenantId, createCommandCode);
        cleanupCommandDefinition(tenantId, updateCommandCode);
        cleanupNamedQuery(tenantId, leadListQueryCode);
    }

    private void cleanupCommandDefinition(Long tenantId, String commandCode) {
        if (commandCode == null) {
            return;
        }
        dynamicDataMapper.delete("ab_agent_action", Map.of("tenant_id", tenantId, "command_code", commandCode));
        dynamicDataMapper.delete("ab_command_definition", Map.of("tenant_id", tenantId, "code", commandCode));
    }

    private void cleanupNamedQuery(Long tenantId, String queryCode) {
        if (queryCode == null) {
            return;
        }
        dynamicDataMapper.delete("ab_agent_action", Map.of("tenant_id", tenantId, "command_code", queryCode));
        dynamicDataMapper.delete("ab_named_query", Map.of("tenant_id", tenantId, "code", queryCode));
    }

    private void insertCommandDef(Long tenantId, String code, String modelCode, String executionConfig) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", UniqueIdGenerator.generate());
        row.put("tenant_id", tenantId);
        row.put("code", code);
        row.put("model_code", modelCode);
        row.put("execution_config", executionConfig);
        row.put("input_schema", "{}");
        row.put("target_models", "[]");
        row.put("extension", "{}");
        row.put("status", "published");
        row.put("version", 1);
        row.put("is_current", true);
        row.put("row_version", 1);
        row.put("deleted_flag", false);
        row.put("created_at", LocalDateTime.now());
        row.put("updated_at", LocalDateTime.now());

        Set<String> jsonbColumns = Set.of("input_schema", "target_models", "extension", "execution_config");
        dynamicDataMapper.insertWithJsonb("ab_command_definition", row, jsonbColumns);
    }

    private void insertNamedQuery(Long tenantId, String code, String fromSql) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", UniqueIdGenerator.generate());
        row.put("tenant_id", tenantId);
        row.put("code", code);
        row.put("title", code);
        row.put("from_sql", fromSql);
        row.put("base_where", "[]");
        row.put("policy", "{}");
        row.put("status", "published");
        row.put("current_version", 1);
        row.put("created_at", LocalDateTime.now());
        row.put("updated_at", LocalDateTime.now());

        Set<String> jsonbColumns = Set.of("base_where", "policy");
        dynamicDataMapper.insertWithJsonb("ab_named_query", row, jsonbColumns);
    }

    @Test
    void testRecordAction_createCommand_success() {
        Long tenantId = getTestTenant().getId();
        String runId = UniqueIdGenerator.generate();

        String actionPid = actionRecorder.recordAction(
                tenantId, runId, createCommandCode,
                null,
                Map.of("crm_lead_company", "TestCo"),
                null, null, null, null
        );

        // Query the recorded action
        String sql = "SELECT * FROM ab_agent_action WHERE pid = #{params.pid}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of("pid", actionPid));
        assertThat(rows).hasSize(1);

        Map<String, Object> action = rows.get(0);
        assertThat(action.get("action_code")).isEqualTo("crm_lead.create");
        assertThat(action.get("action_type")).isEqualTo("create");
        assertThat(action.get("target_model")).isEqualTo("crm_lead");
        assertThat(action.get("action_status")).isEqualTo("success");
        assertThat(action.get("business_domain")).isEqualTo("crm");
        assertThat(action.get("run_id")).isEqualTo(runId);
        assertThat(action.get("tenant_id")).isEqualTo(tenantId);
    }

    @Test
    void testRecordAction_failedCommand() {
        Long tenantId = getTestTenant().getId();
        String runId = UniqueIdGenerator.generate();

        String actionPid = actionRecorder.recordAction(
                tenantId, runId, updateCommandCode,
                null,
                Map.of(),
                null, null, null,
                "Validation failed"
        );

        String sql = "SELECT * FROM ab_agent_action WHERE pid = #{params.pid}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of("pid", actionPid));
        assertThat(rows).hasSize(1);

        Map<String, Object> action = rows.get(0);
        assertThat(action.get("action_status")).isEqualTo("failed");
        assertThat(action.get("error_message").toString()).contains("Validation failed");
        assertThat(action.get("command_result")).isEqualTo("failed");
    }

    @Test
    void testRecordReadAction_query() {
        Long tenantId = getTestTenant().getId();
        String runId = UniqueIdGenerator.generate();

        String actionPid = actionRecorder.recordReadAction(
                tenantId, runId, leadListQueryCode,
                null,
                Map.of(),
                25,
                null
        );

        String sql = "SELECT * FROM ab_agent_action WHERE pid = #{params.pid}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of("pid", actionPid));
        assertThat(rows).hasSize(1);

        Map<String, Object> action = rows.get(0);
        assertThat(action.get("action_type")).isEqualTo("read");
        assertThat(action.get("risk_level")).isEqualTo("L0");
        assertThat(((Number) action.get("affected_count")).intValue()).isEqualTo(25);
        assertThat(action.get("transaction_scope")).isEqualTo("read_only");
        assertThat(action.get("action_status")).isEqualTo("success");
        assertThat(action.get("target_model")).isEqualTo("crm_lead");
    }

    @Test
    void testRecordAction_returnsActionPid() {
        Long tenantId = getTestTenant().getId();
        String runId = UniqueIdGenerator.generate();

        String actionPid = actionRecorder.recordAction(
                tenantId, runId, createCommandCode,
                null,
                Map.of("crm_lead_company", "PidTestCo"),
                null, null, null, null
        );

        assertThat(actionPid).isNotNull();
        assertThat(actionPid).hasSize(26);
    }
}
