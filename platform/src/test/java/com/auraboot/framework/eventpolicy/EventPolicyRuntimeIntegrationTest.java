package com.auraboot.framework.eventpolicy;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyDefinitionEntity;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.eventpolicy.service.EventPolicyVersionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-stack integration test for the EventPolicy module (service → MyBatis → real Postgres →
 * EventPolicyEvaluator → EventPolicyResult). Verifies the wiring that unit tests cannot:
 * JsonNodeTypeHandler JSONB round-trip, MetaContext tenant injection, publish state machine
 * over real rows, version resolution by policyCode, and the full evaluate → result path.
 *
 * <p>Extends {@link BaseIntegrationTest} so it inherits a committed test tenant + MetaContext
 * and a per-test transaction rolled back at the end.
 *
 * <p>Rules used in the happy-path test mirror the spec mockup s1:
 * <ul>
 *   <li>R-101: record.data.priority == "HIGH" → NOTIFY, priority=1
 *   <li>R-102: record.data.amount > 10000 → START_PROCESS, priority=2
 *   <li>R-103: record.data.customerLevel == "VIP" → CREATE_TASK, priority=3
 * </ul>
 * matchMode=COLLECT_ALL so all three fire for the MATCHED case.
 */
class EventPolicyRuntimeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private EventPolicyDefinitionService definitionService;
    @Autowired
    private EventPolicyVersionService versionService;
    @Autowired
    private EventPolicyRuntimeService runtimeService;
    @Autowired
    private DrtDefinitionService decisionDefinitionService;
    @Autowired
    private DecisionVersionService decisionVersionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MetaModelService metaModelService;

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Builds the three-rule rules_json that matches the spec mockup s1.
     * R-101 priority==HIGH, R-102 amount>10000, R-103 customerLevel==VIP.
     * matchMode=COLLECT_ALL so all three fire when all conditions hold.
     */
    private JsonNode buildThreeRuleRulesJson() throws Exception {
        return mapper.readTree("""
            [
              {
                "ruleCode": "R-101",
                "ruleName": "High priority notification",
                "priority": 1,
                "enabled": true,
                "condition": {
                  "type": "compare",
                  "left": { "type": "path", "scope": "record", "path": "data.priority", "dataType": "string" },
                  "operator": "EQ",
                  "right": { "type": "literal", "value": "HIGH", "dataType": "string" }
                },
                "actions": [
                  {
                    "type": "NOTIFY",
                    "target": "ROLE:support_manager",
                    "order": 1,
                    "payload": { "template": "high_priority_alert" },
                    "idempotencyKeyTemplate": "${record.data.recordPid}:${rule.ruleCode}:${action.type}"
                  }
                ]
              },
              {
                "ruleCode": "R-102",
                "ruleName": "Large amount process",
                "priority": 2,
                "enabled": true,
                "condition": {
                  "type": "compare",
                  "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
                  "operator": "GT",
                  "right": { "type": "literal", "value": 10000, "dataType": "decimal" }
                },
                "actions": [
                  {
                    "type": "START_PROCESS",
                    "target": "BPM:complaint_approval",
                    "order": 1,
                    "payload": { "processKey": "complaint_approval" },
                    "idempotencyKeyTemplate": "${record.data.recordPid}:${rule.ruleCode}:${action.type}"
                  }
                ]
              },
              {
                "ruleCode": "R-103",
                "ruleName": "VIP customer task",
                "priority": 3,
                "enabled": true,
                "condition": {
                  "type": "compare",
                  "left": { "type": "path", "scope": "record", "path": "data.customerLevel", "dataType": "string" },
                  "operator": "EQ",
                  "right": { "type": "literal", "value": "VIP", "dataType": "string" }
                },
                "actions": [
                  {
                    "type": "CREATE_TASK",
                    "target": "ASSIGNEE:account_manager",
                    "order": 1,
                    "payload": { "taskTemplate": "vip_follow_up" },
                    "idempotencyKeyTemplate": "${record.data.recordPid}:${rule.ruleCode}:${action.type}"
                  }
                ]
              }
            ]
            """);
    }

    private JsonNode amountGtAst(int threshold) throws Exception {
        return mapper.readTree(("""
            { "type": "compare",
              "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
              "operator": "GT",
              "right": { "type": "literal", "value": %d, "dataType": "decimal" } }
            """).formatted(threshold));
    }

    private JsonNode virtualRiskScoreAst() throws Exception {
        return mapper.readTree("""
            { "type": "compare",
              "left": { "type": "path", "scope": "record", "path": "data.slaRiskScore", "dataType": "integer" },
              "operator": "GT",
              "right": { "type": "literal", "value": 80, "dataType": "integer" } }
            """);
    }

    private void createPublishedDecision(String decisionCode) throws Exception {
        createPublishedDecision(decisionCode, amountGtAst(10000), "record.data.amount");
    }

    private void createPublishedDecision(String decisionCode, JsonNode ast, String expectedFieldRef) throws Exception {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(decisionCode);
        def.setDecisionName("EventPolicy Decision " + decisionCode);
        def.setScopeType("EVENT_POLICY");
        def.setOwnerModule("decision");
        decisionDefinitionService.create(def);

        DrtVersionCreateRequest version = new DrtVersionCreateRequest();
        version.setKind("SIMPLE_CONDITION");
        version.setRuntimeAdapter("AST_EVALUATOR");
        version.setContentJson(ast);
        DrtVersionDTO draft = decisionVersionService.createDraft(decisionCode, version);

        DecisionValidateResult validation = decisionVersionService.validate(draft.getPid());
        assertThat(validation.valid()).isTrue();
        assertThat(validation.fieldRefs()).contains(expectedFieldRef);

        DrtVersionDTO published = decisionVersionService.publish(draft.getPid());
        assertThat(published.getStatus()).isEqualTo(VersionStatus.PUBLISHED.name());
    }

    private JsonNode buildDecisionBindingRulesJson(String decisionCode) throws Exception {
        return mapper.readTree(("""
            [
              {
                "ruleCode": "R-DMN",
                "ruleName": "Decision-bound large amount",
                "priority": 1,
                "enabled": true,
                "decisionBinding": {
                  "decisionCode": "%s",
                  "versionPolicy": "LATEST_PUBLISHED",
                  "inputMappings": [
                    {
                      "input": "amount",
                      "source": { "kind": "FIELD", "scope": "record", "path": "data.amount" }
                    }
                  ],
                  "fallbackPolicy": {
                    "mode": "FAIL_CLOSED",
                    "reason": "Decision evaluation failed"
                  },
                  "enabled": true
                },
                "actions": [
                  {
                    "type": "NOTIFY",
                    "target": "ROLE:decision_manager",
                    "order": 1,
                    "payload": { "template": "decision_large_amount" },
                    "idempotencyKeyTemplate": "${record.data.recordPid}:${rule.ruleCode}:${action.type}"
                  }
                ]
              }
            ]
            """).formatted(decisionCode));
    }

    private JsonNode buildVirtualDecisionBindingRulesJson(String decisionCode) throws Exception {
        return mapper.readTree(("""
            [
              {
                "ruleCode": "R-VIRTUAL",
                "ruleName": "Virtual risk score decision",
                "priority": 1,
                "enabled": true,
                "decisionBinding": {
                  "decisionCode": "%s",
                  "versionPolicy": "LATEST_PUBLISHED",
                  "inputMappings": [
                    {
                      "input": "slaRiskScore",
                      "source": { "kind": "FIELD", "scope": "record", "path": "data.slaRiskScore" }
                    }
                  ],
                  "fallbackPolicy": {
                    "mode": "FAIL_CLOSED",
                    "reason": "Decision evaluation failed"
                  },
                  "enabled": true
                },
                "actions": [
                  {
                    "type": "NOTIFY",
                    "target": "ROLE:decision_manager",
                    "order": 1,
                    "payload": { "template": "virtual_risk_alert" },
                    "idempotencyKeyTemplate": "${record.data.recordPid}:${rule.ruleCode}:${action.type}"
                  }
                ]
              }
            ]
            """).formatted(decisionCode));
    }

    private JsonNode buildSinglePriorityRule(String ruleCode) throws Exception {
        return mapper.readTree(("""
            [
              {
                "ruleCode": "%s",
                "ruleName": "%s",
                "priority": 1,
                "enabled": true,
                "condition": {
                  "type": "compare",
                  "left": { "type": "path", "scope": "record", "path": "data.priority", "dataType": "string" },
                  "operator": "EQ",
                  "right": { "type": "literal", "value": "HIGH", "dataType": "string" }
                },
                "actions": [
                  {
                    "type": "NOTIFY",
                    "target": "ROLE:support_manager",
                    "order": 1,
                    "payload": { "template": "%s" },
                    "idempotencyKeyTemplate": "${record.data.recordPid}:${rule.ruleCode}:${action.type}"
                  }
                ]
              }
            ]
            """).formatted(ruleCode, ruleCode, ruleCode));
    }

    /**
     * Helper: create definition + draft + validate + publish, return definition entity.
     */
    private DrtPolicyDefinitionEntity createAndPublishPolicy(String policyCode) throws Exception {
        DrtPolicyDefinitionEntity def = definitionService.create(
                policyCode, "Complaint Form Policy",
                "FORM_SUBMITTED", "FORM", policyCode);

        JsonNode rulesJson = buildThreeRuleRulesJson();

        DrtPolicyVersionEntity draft = versionService.createDraft(
                policyCode,
                PolicyPhase.AFTER_COMMIT,
                MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED,
                FailureStrategy.FAIL_FAST,
                ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY,
                rulesJson);

        assertThat(draft.getStatus()).isEqualTo(VersionStatus.DRAFT.name());
        assertThat(draft.getVersion()).isEqualTo(1);

        DrtPolicyVersionEntity validated = versionService.validate(draft.getPid());
        assertThat(validated.getStatus()).isEqualTo(VersionStatus.VALIDATED.name());

        DrtPolicyVersionEntity published = versionService.publish(validated.getPid());
        assertThat(published.getStatus()).isEqualTo(VersionStatus.PUBLISHED.name());
        assertThat(published.getContentHash()).isNotBlank();

        return def;
    }

    private void createAndPublishDecisionBoundPolicy(String policyCode, String decisionCode) throws Exception {
        createAndPublishDecisionBoundPolicy(policyCode, buildDecisionBindingRulesJson(decisionCode));
    }

    private void createAndPublishDecisionBoundPolicy(String policyCode, JsonNode rulesJson) {
        definitionService.create(policyCode, "Decision Bound Policy",
                "FORM_SUBMITTED", "FORM", policyCode);

        DrtPolicyVersionEntity draft = versionService.createDraft(
                policyCode,
                PolicyPhase.AFTER_COMMIT,
                MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED,
                FailureStrategy.FAIL_FAST,
                ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY,
                rulesJson);

        DrtPolicyVersionEntity validated = versionService.validate(draft.getPid());
        assertThat(validated.getStatus()).isEqualTo(VersionStatus.VALIDATED.name());

        DrtPolicyVersionEntity published = versionService.publish(validated.getPid());
        assertThat(published.getStatus()).isEqualTo(VersionStatus.PUBLISHED.name());
    }

    private void createRiskScoreView(String viewName) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE VIEW %s AS
                SELECT id, id AS tenant_id, 91::integer AS "slaRiskScore"
                FROM ab_tenant
                """.formatted(viewName));
    }

    private void saveRiskScoreVirtualModel(String modelCode, String viewName) {
        List<FieldDefinition> fields = List.of(
                FieldDefinition.builder()
                        .code("id")
                        .name("id")
                        .displayName("id")
                        .dataType("integer")
                        .columnName("id")
                        .primaryKey(true)
                        .sortable(true)
                        .filterable(true)
                        .build(),
                FieldDefinition.builder()
                        .code("tenant_id")
                        .name("tenant_id")
                        .displayName("tenant_id")
                        .dataType("integer")
                        .columnName("tenant_id")
                        .build(),
                FieldDefinition.builder()
                        .code("slaRiskScore")
                        .name("slaRiskScore")
                        .displayName("SLA Risk Score")
                        .dataType("integer")
                        .columnName("slaRiskScore")
                        .build());

        ModelDefinition saved = metaModelService.saveDefinition(ModelDefinition.builder()
                .code(modelCode)
                .displayName("EventPolicy Virtual Risk " + modelCode)
                .modelType("virtual")
                .sourceType("sqlView")
                .sourceRef(viewName)
                .primaryKey("id")
                .capabilities(ModelCapabilities.virtualReadOnly().toBuilder()
                        .detailKeyField("id")
                        .build())
                .fields(fields)
                .status("published")
                .build());
        assertThat(saved.getSourceType()).isEqualTo("sqlView");
        assertThat(saved.getSourceRef()).isEqualTo(viewName);
    }

    @Test
    void fullLifecycle_create_validate_publish_run_allThreeRulesMatch() throws Exception {
        String policyCode = "ep_complaint_" + System.nanoTime();
        createAndPublishPolicy(policyCode);

        // Verify JSONB round-trip: rules_json stored and readable in Postgres
        Integer storedVersions = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_drt_policy_version WHERE policy_code = ? AND status = 'PUBLISHED'",
                Integer.class, policyCode);
        assertThat(storedVersions).isEqualTo(1);

        // Check a specific field inside rules_json JSONB via SQL
        String firstRuleCode = jdbcTemplate.queryForObject(
                "SELECT rules_json->0->>'ruleCode' FROM ab_drt_policy_version WHERE policy_code = ? AND version = 1",
                String.class, policyCode);
        assertThat(firstRuleCode).isEqualTo("R-101");

        // Run: all three conditions fire (priority=HIGH, amount=20000 > 10000, customerLevel=VIP)
        EventPolicyResult result = runtimeService.run(
                "FORM_SUBMITTED", "FORM", policyCode,
                Map.of("record", Map.of(
                        "data", Map.of(
                                "priority", "HIGH",
                                "amount", 20000,
                                "customerLevel", "VIP",
                                "entityCode", "complaint",
                                "recordPid", "CMP-1"
                        )
                ))
        );

        assertThat(result.status()).isEqualTo(EventPolicyResult.Status.MATCHED);
        assertThat(result.matchedRuleCodes()).containsExactlyInAnyOrder("R-101", "R-102", "R-103");
        assertThat(result.actionPlans()).hasSize(3);

        // Action plans are ordered by rule priority then action.order (COLLECT_ALL + ORDERED)
        List<String> actionTypes = result.actionPlans().stream()
                .map(p -> p.type())
                .toList();
        assertThat(actionTypes).containsExactly("NOTIFY", "START_PROCESS", "CREATE_TASK");

        // Verify idempotency keys were rendered (template uses record.data.recordPid)
        assertThat(result.actionPlans().get(0).idempotencyKey()).contains("CMP-1").contains("R-101").contains("NOTIFY");
        assertThat(result.actionPlans().get(1).idempotencyKey()).contains("CMP-1").contains("R-102").contains("START_PROCESS");
        assertThat(result.actionPlans().get(2).idempotencyKey()).contains("CMP-1").contains("R-103").contains("CREATE_TASK");

        // No errors
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void run_notMatchedCase_smallAmountNormalPriorityNonVip() throws Exception {
        String policyCode = "ep_complaint_nm_" + System.nanoTime();
        createAndPublishPolicy(policyCode);

        // Run: none of the conditions fire (priority=LOW, amount=500 < 10000, customerLevel=REGULAR)
        EventPolicyResult result = runtimeService.run(
                "FORM_SUBMITTED", "FORM", policyCode,
                Map.of("record", Map.of(
                        "data", Map.of(
                                "priority", "LOW",
                                "amount", 500,
                                "customerLevel", "REGULAR",
                                "entityCode", "complaint",
                                "recordPid", "CMP-2"
                        )
                ))
        );

        assertThat(result.status()).isEqualTo(EventPolicyResult.Status.NOT_MATCHED);
        assertThat(result.matchedRuleCodes()).isEmpty();
        assertThat(result.actionPlans()).isEmpty();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void run_decisionBindingRuleEvaluatesPublishedDecision() throws Exception {
        String decisionCode = "ep_decision_" + System.nanoTime();
        String policyCode = "ep_decision_policy_" + System.nanoTime();
        createPublishedDecision(decisionCode);
        createAndPublishDecisionBoundPolicy(policyCode, decisionCode);

        EventPolicyResult matched = runtimeService.run(
                "FORM_SUBMITTED", "FORM", policyCode,
                Map.of("record", Map.of(
                        "data", Map.of(
                                "amount", 20000,
                                "entityCode", "complaint",
                                "recordPid", "CMP-D1"
                        )
                ))
        );

        assertThat(matched.status()).isEqualTo(EventPolicyResult.Status.MATCHED);
        assertThat(matched.matchedRuleCodes()).containsExactly("R-DMN");
        assertThat(matched.actionPlans()).hasSize(1);
        assertThat(matched.actionPlans().get(0).idempotencyKey())
                .contains("CMP-D1")
                .contains("R-DMN")
                .contains("NOTIFY");

        EventPolicyResult notMatched = runtimeService.run(
                "FORM_SUBMITTED", "FORM", policyCode,
                Map.of("record", Map.of(
                        "data", Map.of(
                                "amount", 500,
                                "entityCode", "complaint",
                                "recordPid", "CMP-D2"
                        )
                ))
        );

        assertThat(notMatched.status()).isEqualTo(EventPolicyResult.Status.NOT_MATCHED);
        assertThat(notMatched.matchedRuleCodes()).isEmpty();
        assertThat(notMatched.actionPlans()).isEmpty();
    }

    @Test
    void run_decisionBindingPropagatesMetaVirtualSourcesToDecisionRuntime() throws Exception {
        String decisionCode = "ep_virtual_decision_" + System.nanoTime();
        String policyCode = "ep_virtual_policy_" + System.nanoTime();
        createPublishedDecision(decisionCode, virtualRiskScoreAst(), "record.data.slaRiskScore");
        createAndPublishDecisionBoundPolicy(policyCode, buildVirtualDecisionBindingRulesJson(decisionCode));

        String suffix = Long.toString(Math.abs(System.nanoTime()), 36);
        String viewName = "v_ep_src_" + suffix;
        createRiskScoreView(viewName);
        saveRiskScoreVirtualModel("ep_virtual_risk_" + suffix, viewName);

        try {
            EventPolicyResult result = runtimeService.run(
                    "FORM_SUBMITTED", "FORM", policyCode,
                    Map.of(
                            "record", Map.of("data", Map.of(
                                    "entityCode", "complaint",
                                    "recordPid", "CMP-VIRTUAL")),
                            "meta", Map.of("virtualSources", List.of(Map.of(
                                    "sourceRef", viewName,
                                    "recordId", MetaContext.getCurrentTenantId().toString())))));

            assertThat(result.status()).isEqualTo(EventPolicyResult.Status.MATCHED);
            assertThat(result.matchedRuleCodes()).containsExactly("R-VIRTUAL");
            assertThat(result.actionPlans()).hasSize(1);
            assertThat(result.primaryDecisionTraceId()).isNotBlank();
            Integer matchedLogRows = jdbcTemplate.queryForObject(
                    "select count(*) from ab_drt_log where trace_id = ? and matched = true and status = 'MATCHED'",
                    Integer.class, result.primaryDecisionTraceId());
            assertThat(matchedLogRows).isEqualTo(1);
            String loggedSourceRef = jdbcTemplate.queryForObject(
                    "select trace_snapshot->'virtualSources'->0->>'sourceRef' from ab_drt_log where trace_id = ?",
                    String.class, result.primaryDecisionTraceId());
            Integer loggedRiskScore = jdbcTemplate.queryForObject(
                    """
                    select (trace_snapshot->'virtualSources'->0->'fields'->>'slaRiskScore')::int
                    from ab_drt_log where trace_id = ?
                    """,
                    Integer.class, result.primaryDecisionTraceId());
            assertThat(loggedSourceRef).isEqualTo(viewName);
            assertThat(loggedRiskScore).isEqualTo(91);
        } finally {
            jdbcTemplate.execute("DROP VIEW IF EXISTS " + viewName);
        }
    }

    @Test
    void publish_deprecatesPreviousPublishedVersionAndRuntimeUsesNewestActive() throws Exception {
        String policyCode = "ep_latest_pub_" + System.nanoTime();
        definitionService.create(policyCode, "Latest Published Policy",
                "FORM_SUBMITTED", "FORM", policyCode);

        DrtPolicyVersionEntity draft1 = versionService.createDraft(
                policyCode,
                PolicyPhase.AFTER_COMMIT,
                MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED,
                FailureStrategy.FAIL_FAST,
                ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY,
                buildSinglePriorityRule("R-OLD"));
        versionService.validate(draft1.getPid());
        versionService.publish(draft1.getPid());

        DrtPolicyVersionEntity draft2 = versionService.createDraft(
                policyCode,
                PolicyPhase.AFTER_COMMIT,
                MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED,
                FailureStrategy.FAIL_FAST,
                ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY,
                buildSinglePriorityRule("R-LATEST"));
        versionService.validate(draft2.getPid());
        versionService.publish(draft2.getPid());

        Integer publishedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_drt_policy_version WHERE policy_code = ? AND status = 'PUBLISHED'",
                Integer.class, policyCode);
        assertThat(publishedCount).isEqualTo(1);

        String oldStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM ab_drt_policy_version WHERE pid = ?",
                String.class, draft1.getPid());
        String latestStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM ab_drt_policy_version WHERE pid = ?",
                String.class, draft2.getPid());
        assertThat(oldStatus).isEqualTo(VersionStatus.DEPRECATED.name());
        assertThat(latestStatus).isEqualTo(VersionStatus.PUBLISHED.name());

        EventPolicyResult result = runtimeService.run(
                "FORM_SUBMITTED", "FORM", policyCode,
                Map.of("record", Map.of(
                        "data", Map.of(
                                "priority", "HIGH",
                                "entityCode", "complaint",
                                "recordPid", "CMP-LATEST"
                        )
                ))
        );

        assertThat(result.status()).isEqualTo(EventPolicyResult.Status.MATCHED);
        assertThat(result.matchedRuleCodes()).containsExactly("R-LATEST");
        assertThat(result.actionPlans().get(0).idempotencyKey()).contains("R-LATEST");
    }

    @Test
    void run_usesHighestPublishedVersionWhenLegacyDuplicatePublishedRowsExist() throws Exception {
        String policyCode = "ep_legacy_dup_pub_" + System.nanoTime();
        definitionService.create(policyCode, "Legacy Duplicate Published Policy",
                "FORM_SUBMITTED", "FORM", policyCode);

        DrtPolicyVersionEntity draft1 = versionService.createDraft(
                policyCode,
                PolicyPhase.AFTER_COMMIT,
                MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED,
                FailureStrategy.FAIL_FAST,
                ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY,
                buildSinglePriorityRule("R-OLD"));
        versionService.validate(draft1.getPid());
        versionService.publish(draft1.getPid());

        DrtPolicyVersionEntity draft2 = versionService.createDraft(
                policyCode,
                PolicyPhase.AFTER_COMMIT,
                MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED,
                FailureStrategy.FAIL_FAST,
                ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY,
                buildSinglePriorityRule("R-LATEST"));
        versionService.validate(draft2.getPid());
        versionService.publish(draft2.getPid());

        // Simulate pre-fix or imported historical data that still has duplicate PUBLISHED rows.
        jdbcTemplate.update("UPDATE ab_drt_policy_version SET status = 'PUBLISHED' WHERE pid = ?", draft1.getPid());

        Integer publishedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ab_drt_policy_version WHERE policy_code = ? AND status = 'PUBLISHED'",
                Integer.class, policyCode);
        assertThat(publishedCount).isEqualTo(2);

        EventPolicyResult result = runtimeService.run(
                "FORM_SUBMITTED", "FORM", policyCode,
                Map.of("record", Map.of(
                        "data", Map.of(
                                "priority", "HIGH",
                                "entityCode", "complaint",
                                "recordPid", "CMP-LEGACY-DUP"
                        )
                ))
        );

        assertThat(result.status()).isEqualTo(EventPolicyResult.Status.MATCHED);
        assertThat(result.matchedRuleCodes()).containsExactly("R-LATEST");
        assertThat(result.actionPlans().get(0).idempotencyKey()).contains("R-LATEST");
    }

    @Test
    void run_noPolicyFound_returnsNotMatched() {
        // No definition created for this event+target — should return NOT_MATCHED cleanly
        EventPolicyResult result = runtimeService.run(
                "UNKNOWN_EVENT", "UNKNOWN_TYPE", "unknown_key",
                Map.of()
        );

        assertThat(result.status()).isEqualTo(EventPolicyResult.Status.NOT_MATCHED);
        assertThat(result.actionPlans()).isEmpty();
    }

    @Test
    void createDraft_versionIncrement_andRulesJsonRoundTrip() throws Exception {
        String policyCode = "ep_ver_test_" + System.nanoTime();
        definitionService.create(policyCode, "Version Test Policy",
                "FORM_SUBMITTED", "FORM", "test_form");

        JsonNode rulesJson = buildThreeRuleRulesJson();

        // Create first draft
        DrtPolicyVersionEntity draft1 = versionService.createDraft(
                policyCode, PolicyPhase.BEFORE_SUBMIT, MatchMode.FIRST_MATCH,
                ExecutionMode.UNORDERED, FailureStrategy.CONTINUE_ON_ERROR,
                ConflictStrategy.PRIORITY_WINS, DedupStrategy.NONE, rulesJson);
        assertThat(draft1.getVersion()).isEqualTo(1);
        assertThat(draft1.getStatus()).isEqualTo("DRAFT");
        assertThat(draft1.getMatchMode()).isEqualTo("FIRST_MATCH");
        assertThat(draft1.getPhase()).isEqualTo("BEFORE_SUBMIT");

        // Validate and publish version 1
        versionService.validate(draft1.getPid());
        versionService.publish(draft1.getPid());

        // Create second draft — version should be 2
        DrtPolicyVersionEntity draft2 = versionService.createDraft(
                policyCode, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED, FailureStrategy.FAIL_FAST,
                ConflictStrategy.REJECT_ON_CONFLICT, DedupStrategy.BY_IDEMPOTENCY_KEY, rulesJson);
        assertThat(draft2.getVersion()).isEqualTo(2);

        // Verify version 1 content_hash was set (rules_json round-trip through JSONB)
        DrtPolicyVersionEntity v1 = versionService.findByPid(draft1.getPid());
        assertThat(v1.getContentHash()).isNotBlank();
        assertThat(v1.getRulesJson()).isNotNull();
        assertThat(v1.getRulesJson().isArray()).isTrue();
        assertThat(v1.getRulesJson().size()).isEqualTo(3);
    }
}
