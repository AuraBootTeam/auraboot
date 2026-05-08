package com.auraboot.framework.agent;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.service.ActionRecorder;
import com.auraboot.framework.agent.service.CapabilityRouter;
import com.auraboot.framework.agent.service.GroundingService;
import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Integration tests for the 3 newly implemented P1 features:
 * 1. Risk 3-State (estimated_risk, risk_deviation on ab_agent_action;
 *    approval_subject_type, revalidate_policy on ab_agent_approval)
 * 2. Capability Layer (CapabilityRouter intent+object pattern matching)
 * 3. Grounding → Capability routing integration
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
class AcpP1FeaturesIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ActionRecorder actionRecorder;

    @Autowired
    private CapabilityRouter capabilityRouter;

    @Autowired
    private GroundingService groundingService;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    @Autowired
    private ObjectMapper objectMapper;

    private Long tenantId;

    @BeforeEach
    void seedTestData() throws Exception {
        tenantId = getTestTenant().getId();

        // Seed command definition for crm:create_lead
        insertCommandDef(tenantId, "crm:create_lead", "crm_lead",
                objectMapper.writeValueAsString(Map.of("type", "create")));
    }

    // ========== Risk 3-State Tests ==========

    @Test
    void testActionRecord_hasEstimatedRisk() {
        String runId = UniqueIdGenerator.generate();

        AgentToolDefinition toolDef = AgentToolDefinition.builder()
                .name("crm:create_lead")
                .riskLevel("L1")
                .build();

        String actionPid = actionRecorder.recordAction(
                tenantId, runId, "crm:create_lead",
                toolDef,
                Map.of("crm_lead_company", "RiskTestCo"),
                null, null, null, null
        );

        assertThat(actionPid).isNotNull();

        String sql = "SELECT estimated_risk, risk_deviation FROM ab_agent_action WHERE run_id = #{params.runId}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of("runId", runId));
        assertThat(rows).hasSize(1);

        Map<String, Object> action = rows.get(0);
        assertThat(action.get("estimated_risk")).isEqualTo("L1");
        assertThat(action.get("risk_deviation")).isEqualTo(false);
    }

    @Test
    void testActionRecord_riskDeviationDefaultFalse() {
        String runId = UniqueIdGenerator.generate();

        String actionPid = actionRecorder.recordAction(
                tenantId, runId, "crm:create_lead",
                null,
                Map.of("crm_lead_company", "DeviationTestCo"),
                null, null, null, null
        );

        assertThat(actionPid).isNotNull();

        String sql = "SELECT risk_deviation FROM ab_agent_action WHERE run_id = #{params.runId}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of("runId", runId));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("risk_deviation")).isEqualTo(false);
    }

    @Test
    void testApproval_hasNewFields() {
        String approvalPid = UniqueIdGenerator.generate();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", approvalPid);
        row.put("tenant_id", tenantId);
        row.put("approval_type", "tool_call");
        row.put("approval_title", "Test P1 Approval");
        row.put("approval_status", "pending");
        row.put("approval_subject_type", "step");
        row.put("revalidate_policy", "data_freshness");
        row.put("created_at", LocalDateTime.now());
        row.put("updated_at", LocalDateTime.now());

        dynamicDataMapper.insert("ab_agent_approval", row);

        String sql = "SELECT approval_subject_type, revalidate_policy FROM ab_agent_approval WHERE pid = #{params.pid}";
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(sql, Map.of("pid", approvalPid));
        assertThat(rows).hasSize(1);

        Map<String, Object> approval = rows.get(0);
        assertThat(approval.get("approval_subject_type")).isEqualTo("step");
        assertThat(approval.get("revalidate_policy")).isEqualTo("data_freshness");
    }

    // ========== Capability Layer Tests ==========

    @Test
    void testCapabilityRouter_crmQueryMatch() {
        // Seed capability
        insertCapability(tenantId, "test_crm.query", "CRM Query",
                "[\"query\",\"analyze\"]", "[\"crm_*\"]", "[\"crm_lead.query\"]");

        // Seed skill so loadSkill returns non-null
        insertSkill(tenantId, "crm_lead.query", "CRM Lead Query", "atomic");

        List<String> skills = capabilityRouter.route(tenantId, "query", "crm_lead");
        assertThat(skills).isNotEmpty();
        assertThat(skills).contains("crm_lead.query");
    }

    @Test
    void testCapabilityRouter_noMatch() {
        // Platform-default capability CAP_GENERIC_QUERY (object_patterns="*")
        // routes ANY "query" intent to "dsl.query" — including unknown models.
        // This is the documented behaviour: when no domain-specific capability
        // matches, the generic DSL query fallback is wired.
        List<String> skills = capabilityRouter.route(tenantId, "query", "nonexistent_model");
        assertThat(skills).containsExactly("dsl.query");
    }

    @Test
    void testCapabilityRouter_intentMismatch() {
        // Seed capability with create intent only
        insertCapability(tenantId, "test_crm.create_only", "CRM Create Only",
                "[\"create\"]", "[\"crm_*\"]", "[\"crm_lead.create\"]");

        insertSkill(tenantId, "crm_lead.create", "CRM Lead Create", "atomic");

        // Route with "query" intent — does NOT match the seeded "create" capability,
        // but DOES match the platform-default CAP_GENERIC_QUERY which routes any
        // "query" intent to "dsl.query".
        List<String> skills = capabilityRouter.route(tenantId, "query", "crm_lead");
        assertThat(skills).containsExactly("dsl.query");
    }

    @Test
    void testGroundingWithCapability_routesViaCapability() {
        // Seed capability + skill for CRM query
        insertCapability(tenantId, "test_crm.ground_query", "CRM Ground Query",
                "[\"query\",\"analyze\"]", "[\"crm_*\"]", "[\"crm_lead.query\"]");

        insertSkill(tenantId, "crm_lead.query", "CRM Lead Query", "atomic");

        GroundingService.GroundingContext ctx = GroundingService.GroundingContext.builder().build();
        BusinessIntentFrame bif = groundingService.ground(tenantId, "查一下CRM线索", ctx);

        assertThat(bif).isNotNull();
        assertThat(bif.getIntent()).isEqualTo("query");
        assertThat(bif.getObject()).isEqualTo("crm_lead");
        assertThat(bif.getCandidateSkills()).isNotEmpty();
        assertThat(bif.getCandidateSkills()).contains("crm_lead.query");
    }

    // ========== Seed Helpers ==========

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

    private void insertCapability(Long tenantId, String code, String name,
                                   String intentPatterns, String objectPatterns, String skills) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", UniqueIdGenerator.generate());
        row.put("tenant_id", tenantId);
        row.put("capability_code", code);
        row.put("capability_name", name);
        row.put("domain", "crm");
        row.put("intent_patterns", intentPatterns);
        row.put("object_patterns", objectPatterns);
        row.put("skills", skills);
        row.put("selection_strategy", "auto_first");
        row.put("capability_status", "active");
        row.put("deleted_flag", false);
        row.put("created_at", LocalDateTime.now());
        row.put("updated_at", LocalDateTime.now());

        Set<String> jsonbColumns = Set.of("intent_patterns", "object_patterns", "skills");
        dynamicDataMapper.insertWithJsonb("ab_agent_capability", row, jsonbColumns);
    }

    private void insertSkill(Long tenantId, String skillCode, String skillName, String level) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("pid", UniqueIdGenerator.generate());
        row.put("tenant_id", tenantId);
        row.put("skill_code", skillCode);
        row.put("skill_name", skillName);
        row.put("skill_level", level);
        row.put("skill_category", "crm");
        row.put("skill_status", "active");
        row.put("is_builtin", false);
        row.put("deleted_flag", false);
        row.put("usage_count", 0);
        row.put("avg_rating", 0);
        row.put("created_at", LocalDateTime.now());
        row.put("updated_at", LocalDateTime.now());

        dynamicDataMapper.insert("ab_agent_skill", row);
    }
}
