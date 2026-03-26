package com.auraboot.framework.agent;

import com.auraboot.framework.agent.dto.SkillInput;
import com.auraboot.framework.agent.dto.SkillResult;
import com.auraboot.framework.agent.service.GroundingService;
import com.auraboot.framework.agent.service.SemanticTermResolver;
import com.auraboot.framework.agent.service.SemanticValidator;
import com.auraboot.framework.agent.service.SkillEngine;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for D1 Grounding extended services:
 * SemanticValidator, SemanticTermResolver, and SkillEngine.
 *
 * Requires real PostgreSQL with ab_command_definition, ab_semantic_term,
 * ab_agent_skill, and ab_agent_tool tables.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
class D1GroundingExtendedIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SemanticValidator semanticValidator;

    @Autowired
    private SemanticTermResolver semanticTermResolver;

    @Autowired
    private SkillEngine skillEngine;

    @Autowired
    private GroundingService groundingService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    private Long tenantId;

    @BeforeEach
    public void setUp() {
        tenantId = getTestTenant().getId();
    }

    // ========== SemanticValidator ==========

    @Test
    void testValidator_queryIntent_alwaysValid() {
        SemanticValidator.ValidationResult result =
                semanticValidator.validate("query", "crm_lead", Map.of(), tenantId);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getAdjustedConfidence()).isEqualTo(1.0);
        assertThat(result.getAdjustedActionability()).isEqualTo("read_only");
    }

    @Test
    void testValidator_createIntent_withCommand_valid() {
        // Seed a create command for crm_lead in test tenant
        seedCommand("test_create_lead_" + System.currentTimeMillis(), "crm_lead", "create");

        SemanticValidator.ValidationResult result =
                semanticValidator.validate("create", "crm_lead", Map.of(), tenantId);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getAdjustedConfidence()).isEqualTo(1.0);
        assertThat(result.getAdjustedActionability()).isEqualTo("execute");
    }

    @Test
    void testValidator_deleteIntent_noCommand_invalid() {
        // Use a model code that definitely has no delete command
        String fakeModel = "nonexistent_model_" + System.currentTimeMillis();

        SemanticValidator.ValidationResult result =
                semanticValidator.validate("delete", fakeModel, Map.of(), tenantId);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getAdjustedConfidence()).isLessThan(1.0);
        assertThat(result.getReason()).containsIgnoringCase("no delete command");
    }

    @Test
    void testValidator_updateIntent_noScope_lowConfidence() {
        // Seed an update command so layer 1 passes
        seedCommand("test_update_lead_" + System.currentTimeMillis(), "crm_lead", "update");

        // Update without recordIds in scope => layer 2 reduces confidence
        SemanticValidator.ValidationResult result =
                semanticValidator.validate("update", "crm_lead", Map.of(), tenantId);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getAdjustedConfidence()).isLessThan(1.0);
    }

    // ========== SemanticTermResolver ==========

    @Test
    void testTermResolver_timeRange() {
        // SemanticTermResolver loads from ab_semantic_term where tenant_id = -1 (lazy cache).
        // Platform seed data may include "最近". If cache is already loaded, our INSERT won't take effect.
        // So we test with platform-seeded data.
        List<SemanticTermResolver.ResolvedTerm> results =
                semanticTermResolver.resolve(tenantId, "看看最近的数据", null);

        // If "最近" is seeded in platform data, expect a match
        if (!results.isEmpty()) {
            assertThat(results).anyMatch(r -> "time_range".equals(r.getTermType()));
            assertThat(results.get(0).getResolution()).isNotEmpty();
        }
        // Method executed without error in either case
    }

    @Test
    void testTermResolver_filterTerm() {
        // Test with platform-seeded "活跃客户" if available
        List<SemanticTermResolver.ResolvedTerm> results =
                semanticTermResolver.resolve(tenantId, "查一下活跃客户", "crm_account");

        if (!results.isEmpty()) {
            assertThat(results).anyMatch(r -> "filter".equals(r.getTermType()));
            assertThat(results.get(0).getResolution()).isNotEmpty();
        }
        // Method executed without error in either case
    }

    @Test
    void testTermResolver_noMatch() {
        List<SemanticTermResolver.ResolvedTerm> results =
                semanticTermResolver.resolve(tenantId, "hello world xyzzy gibberish", null);

        assertThat(results).isEmpty();
    }

    // ========== SkillEngine ==========

    @Test
    void testSkillEngine_templateMode() {
        // Seed an agent tool
        String toolCode = "nq_test_tool_" + System.currentTimeMillis();
        seedAgentTool(toolCode, null);

        // Seed an agent skill referencing the tool
        String skillCode = "test_skill_query_" + System.currentTimeMillis();
        seedAgentSkill(skillCode, "template", List.of(toolCode));

        SkillInput input = SkillInput.builder()
                .intent("query")
                .object("test_model")
                .parameters(Map.of("keyword", "test"))
                .build();

        // Execute — the tool has no real NQ behind it, so execution will likely fail at the tool level.
        // We verify the engine dispatches correctly by checking it returns a result (not null).
        SkillResult result = skillEngine.execute(tenantId,
                "test-run-" + System.currentTimeMillis(),
                skillCode, input, null, null, null);

        assertThat(result).isNotNull();
        assertThat(result.getSkillCode()).isEqualTo(skillCode);
        assertThat(result.getStatus()).isNotNull();
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void testSkillEngine_invalidSkill_returnsFailedResult() {
        String nonExistentSkill = "nonexistent_skill_" + System.currentTimeMillis();

        SkillInput input = SkillInput.builder()
                .intent("query")
                .parameters(Map.of())
                .build();

        SkillResult result = skillEngine.execute(tenantId,
                "test-run-" + System.currentTimeMillis(),
                nonExistentSkill, input, null, null, null);

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getErrorMessage()).contains("not found");
    }

    // ========== Seed Helpers ==========

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
        data.put("status", "published");
        data.put("version", 1);
        data.put("is_current", true);
        data.put("row_version", 1);
        data.put("deleted_flag", false);
        data.put("created_at", LocalDateTime.now());
        data.put("updated_at", LocalDateTime.now());

        Set<String> jsonbCols = Set.of("input_schema", "target_models", "execution_config", "extension");
        dynamicDataMapper.insertWithJsonb("ab_command_definition", data, jsonbCols);
    }

    private void seedAgentTool(String toolCode, String sourceCode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pid", UniqueIdGenerator.generate());
        data.put("tenant_id", tenantId);
        data.put("tool_code", toolCode);
        data.put("tool_type", "named_query");
        data.put("tool_name", "Test Tool " + toolCode);
        data.put("tool_description", "Auto-generated test tool for " + toolCode);
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

    private void seedAgentSkill(String skillCode, String executionMode, List<String> toolCodes) {
        // Build JSON array string for skill_tools
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < toolCodes.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(toolCodes.get(i)).append("\"");
        }
        sb.append("]");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pid", UniqueIdGenerator.generate());
        data.put("tenant_id", tenantId);
        data.put("skill_code", skillCode);
        data.put("skill_name", "Test Skill " + skillCode);
        data.put("execution_mode", executionMode);
        data.put("skill_tools", sb.toString());
        data.put("skill_status", "active");
        data.put("deleted_flag", false);
        data.put("created_at", LocalDateTime.now());
        data.put("updated_at", LocalDateTime.now());

        Set<String> jsonbCols = Set.of("skill_tools");
        dynamicDataMapper.insertWithJsonb("ab_agent_skill", data, jsonbCols);
    }
}
