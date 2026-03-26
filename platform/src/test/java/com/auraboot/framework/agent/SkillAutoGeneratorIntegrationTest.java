package com.auraboot.framework.agent;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.agent.service.AgentSkillService;
import com.auraboot.framework.agent.service.SkillAutoGenerator;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for SkillAutoGenerator — verifies atomic skill generation
 * from published DSL model definitions.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SkillAutoGeneratorIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SkillAutoGenerator skillAutoGenerator;

    @Autowired
    private AgentSkillService skillService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private Long tenantId;

    @BeforeEach
    void seedTestData() {
        tenantId = getTestTenant().getId();
        seedPublishedModel("crm_lead");
        seedCommand("crm_lead_create", "crm_lead", "create");
        seedCommand("crm_lead_update", "crm_lead", "update");
        seedCommand("crm_lead_delete", "crm_lead", "delete");
        seedNamedQuery("crm_lead_list", "crm_lead");
        seedAgentTool("crm_lead_create", "crm_lead_create");
        seedAgentTool("crm_lead_update", "crm_lead_update");
        seedAgentTool("crm_lead_delete", "crm_lead_delete");
        seedAgentTool("nq_crm_lead_list", null); // NQ tool, no source_code match needed
    }

    @Test
    @Order(1)
    void testSyncSkills_generatesAtomicSkills() {
        SkillAutoGenerator.SyncResult result = skillAutoGenerator.syncSkills(tenantId);

        assertThat(result.created()).isGreaterThan(0);

        String sql = "SELECT COUNT(*) AS cnt FROM ab_agent_skill " +
                "WHERE tenant_id = #{params.tenantId} AND skill_code LIKE '%.%'";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql,
                Map.of("tenantId", tenantId));
        long count = ((Number) rows.get(0).get("cnt")).longValue();
        assertThat(count).isGreaterThan(0);
    }

    @Test
    @Order(2)
    void testSyncSkills_idempotent() {
        // First sync — creates skills
        SkillAutoGenerator.SyncResult first = skillAutoGenerator.syncSkills(tenantId);
        assertThat(first.created()).isGreaterThan(0);

        // Second sync — should only update, not create
        SkillAutoGenerator.SyncResult second = skillAutoGenerator.syncSkills(tenantId);
        assertThat(second.created()).isEqualTo(0);
        assertThat(second.updated()).isGreaterThan(0);
    }

    @Test
    @Order(3)
    void testSyncSkills_twoBuiltinSkillsGenerated() {
        skillAutoGenerator.syncSkills(tenantId);

        Map<String, Object> cmdSkill = skillService.loadSkill(tenantId, "dsl.command");
        assertThat(cmdSkill).isNotNull();
        assertThat(cmdSkill.get("skill_code")).isEqualTo("dsl.command");

        Map<String, Object> qrySkill = skillService.loadSkill(tenantId, "dsl.query");
        assertThat(qrySkill).isNotNull();
        assertThat(qrySkill.get("skill_code")).isEqualTo("dsl.query");
    }

    @Test
    @Order(4)
    void testDslQuerySkill_hasCorrectContract() {
        skillAutoGenerator.syncSkills(tenantId);

        Map<String, Object> contract = skillService.loadSkillContract(tenantId, "dsl.query");
        assertThat(contract).isNotNull();
        assertThat(contract.get("execution_mode")).isEqualTo("dsl_dispatch");
        assertThat(contract.get("actionability")).isEqualTo("read_only");
        assertThat(contract.get("output_type")).isEqualTo("structured_result");
        assertThat(contract.get("idempotency_mode")).isEqualTo("safe");
    }

    @Test
    @Order(5)
    void testDslCommandSkill_hasExecuteActionability() {
        skillAutoGenerator.syncSkills(tenantId);

        Map<String, Object> contract = skillService.loadSkillContract(tenantId, "dsl.command");
        assertThat(contract).isNotNull();
        assertThat(contract.get("actionability")).isEqualTo("execute");
        assertThat(contract.get("execution_mode")).isEqualTo("dsl_dispatch");
        assertThat(contract.get("idempotency_mode")).isEqualTo("not_idempotent");
    }

    // ──────────────────────────────────────────────────────────────
    // Seed helpers — insert minimal rows needed by SkillAutoGenerator
    // ──────────────────────────────────────────────────────────────

    private void seedPublishedModel(String code) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pid", UniqueIdGenerator.generate());
        data.put("tenant_id", tenantId);
        data.put("code", code);
        data.put("status", "published");
        data.put("model_category", "business");
        data.put("extension", "{}");
        data.put("version", 1);
        data.put("is_current", true);
        data.put("row_version", 1);
        data.put("deleted_flag", false);
        data.put("created_at", LocalDateTime.now());
        data.put("updated_at", LocalDateTime.now());
        dynamicDataMapper.insertWithJsonb("ab_meta_model", data, Set.of("extension"));
    }

    private void seedCommand(String code, String modelCode, String execType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pid", UniqueIdGenerator.generate());
        data.put("tenant_id", tenantId);
        data.put("code", code);
        data.put("model_code", modelCode);
        data.put("display_name", code);
        data.put("input_schema", "{}");
        data.put("target_models", "[]");
        data.put("execution_config", "{\"type\":\"" + execType + "\"}");
        data.put("extension", "{}");
        data.put("deleted_flag", false);
        data.put("created_at", LocalDateTime.now());
        data.put("updated_at", LocalDateTime.now());

        Set<String> jsonbCols = Set.of("input_schema", "target_models", "execution_config", "extension");
        dynamicDataMapper.insertWithJsonb("ab_command_definition", data, jsonbCols);
    }

    private void seedNamedQuery(String code, String modelCode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pid", UniqueIdGenerator.generate());
        data.put("tenant_id", tenantId);
        data.put("code", code);
        data.put("title", "List " + modelCode);
        data.put("base_where", "[]");
        dynamicDataMapper.insertWithJsonb("ab_named_query", data, Set.of("base_where"));
    }

    private void seedAgentTool(String toolCode, String sourceCode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pid", UniqueIdGenerator.generate());
        data.put("tenant_id", tenantId);
        data.put("tool_code", toolCode);
        data.put("tool_type", "command");
        data.put("tool_name", "Tool " + toolCode);
        data.put("tool_description", "Auto-generated tool for " + toolCode);
        data.put("tool_status", "active");
        data.put("auto_generated", true);
        data.put("deleted_flag", false);
        data.put("created_at", LocalDateTime.now());
        data.put("updated_at", LocalDateTime.now());
        if (sourceCode != null) {
            data.put("source_code", sourceCode);
        }
        dynamicDataMapper.insert("ab_agent_tool", data);
    }
}
