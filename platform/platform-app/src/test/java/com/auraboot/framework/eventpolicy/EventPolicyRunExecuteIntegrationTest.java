package com.auraboot.framework.eventpolicy;

import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.auraboot.framework.eventpolicy.executor.ActionHandler;
import com.auraboot.framework.eventpolicy.executor.ActionExecutionStatus;
import com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.model.ResolvedActionPlan;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.eventpolicy.service.EventPolicyVersionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end EventPolicy run-AND-execute over the real stack (docs/2.md §2): event → matched rules →
 * resolved action plans → PolicyExecutor dispatches to a registered handler → idempotency logged.
 * A {@link TestConfiguration} registers a recording NOTIFY handler so the executor has something to
 * dispatch to (production wires domain handlers — a later slice).
 */
@Import(EventPolicyRunExecuteIntegrationTest.TestHandlers.class)
class EventPolicyRunExecuteIntegrationTest extends BaseIntegrationTest {

    static final AtomicInteger NOTIFY_INVOCATIONS = new AtomicInteger();

    @TestConfiguration
    static class TestHandlers {
        @Bean
        ActionHandler notifyTestHandler() {
            return new ActionHandler() {
                // test-specific action type so it doesn't shadow the production NotifyActionHandler (NOTIFY)
                @Override public boolean supports(String type) { return "TEST_NOTIFY".equals(type); }
                @Override public void execute(ResolvedActionPlan plan, DecisionContext ctx) {
                    NOTIFY_INVOCATIONS.incrementAndGet();
                }
                @Override public Map<String, Object> executeWithResult(ResolvedActionPlan plan, DecisionContext ctx) {
                    NOTIFY_INVOCATIONS.incrementAndGet();
                    return Map.of("sentCount", 1, "channel", "test", "target", plan.target());
                }
            };
        }
    }

    @Autowired private DrtDefinitionService decisionDefinitionService;
    @Autowired private DecisionVersionService decisionVersionService;
    @Autowired private EventPolicyDefinitionService definitionService;
    @Autowired private EventPolicyVersionService versionService;
    @Autowired private EventPolicyRuntimeService runtimeService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MetaModelService metaModelService;
    @Autowired private MetaFieldService metaFieldService;
    @Autowired private UserService userService;

    private final ObjectMapper mapper = new ObjectMapper();

    private String publishNotifyPolicy(String code, String targetKey) throws Exception {
        definitionService.create(code, "Run+Exec IT", "FORM_SUBMITTED", "FORM", targetKey);

        String rules = """
            [{"ruleCode":"R-NOTIFY","ruleName":"notify high","priority":100,"enabled":true,
              "condition":{"type":"compare",
                 "left":{"type":"path","scope":"record","path":"data.priority","dataType":"enum"},
                 "operator":"EQ","right":{"type":"literal","value":"HIGH","dataType":"enum"}},
              "actions":[{"type":"TEST_NOTIFY","target":"ROLE:mgr","order":10,"payload":{},
                 "idempotencyKeyTemplate":"${record.entityCode}:${record.recordPid}:${rule.ruleCode}:NOTIFY"}]}]
            """;
        JsonNode rulesJson = mapper.readTree(rules);
        DrtPolicyVersionEntity draft = versionService.createDraft(
                code, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL, ExecutionMode.ORDERED,
                FailureStrategy.CONTINUE_ON_ERROR, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, rulesJson);
        versionService.validate(draft.getPid());
        versionService.publish(draft.getPid());
        return code;
    }

    private Map<String, Map<String, Object>> ctx(String targetKey, String priority) {
        return Map.of("record", Map.of("entityCode", targetKey, "recordPid", "CMP-RX-1",
                "data", Map.of("priority", priority)));
    }

    private JsonNode amountGtAst(int threshold) throws Exception {
        return mapper.readTree(("""
            { "type": "compare",
              "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
              "operator": "GT",
              "right": { "type": "literal", "value": %d, "dataType": "decimal" } }
            """).formatted(threshold));
    }

    private void createPublishedDecision(String decisionCode) throws Exception {
        createPublishedDecision(decisionCode, amountGtAst(10000), "record.data.amount");
    }

    private void createPublishedDecision(String decisionCode,
                                         JsonNode contentJson,
                                         String expectedFieldRef) {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(decisionCode);
        def.setDecisionName("Run+Exec Decision " + decisionCode);
        def.setScopeType("EVENT_POLICY");
        def.setOwnerModule("decision");
        decisionDefinitionService.create(def);

        DrtVersionCreateRequest version = new DrtVersionCreateRequest();
        version.setKind("SIMPLE_CONDITION");
        version.setRuntimeAdapter("AST_EVALUATOR");
        version.setContentJson(contentJson);
        DrtVersionDTO draft = decisionVersionService.createDraft(decisionCode, version);

        DecisionValidateResult validation = decisionVersionService.validate(draft.getPid());
        assertThat(validation.valid()).isTrue();
        assertThat(validation.fieldRefs()).contains(expectedFieldRef);

        DrtVersionDTO published = decisionVersionService.publish(draft.getPid());
        assertThat(published.getStatus()).isEqualTo(VersionStatus.PUBLISHED.name());
    }

    private JsonNode userReferenceEqAst(String fieldCode, String userPid) {
        return mapper.valueToTree(Map.of(
                "type", "compare",
                "left", Map.of(
                        "type", "path",
                        "scope", "record",
                        "path", "data." + fieldCode,
                        "dataType", "user"),
                "operator", "EQ",
                "right", Map.of(
                        "type", "literal",
                        "value", userPid,
                        "dataType", "user")));
    }

    private void saveUserReferenceModel(String modelCode, String fieldCode) {
        MetaModelCreateRequest modelRequest = new MetaModelCreateRequest();
        modelRequest.setCode(modelCode);
        modelRequest.setDisplayName("EventPolicy Reference Metadata " + modelCode);
        modelRequest.setModelType("entity");
        modelRequest.setSourceType("physical");
        modelRequest.setPrimaryKey("pid");

        MetaModelDTO model = metaModelService.create(modelRequest);
        assertThat(model.getPid()).isNotBlank();

        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("reference");
        fieldRequest.setRefTarget(Map.of(
                "targetEntity", "sys_user",
                "displayField", "displayName",
                "valueField", "pid"));
        fieldRequest.setExtension(Map.of("displayName", "申请人"));
        fieldRequest.setAutoPublish(true);

        MetaFieldDTO field = metaFieldService.create(fieldRequest);
        assertThat(field.getPid()).isNotBlank();
        assertThat(field.getRefTarget()).containsEntry("targetEntity", "sys_user");
        metaModelService.bindFieldToModel(
                model.getId(),
                field.getId(),
                1,
                false,
                true,
                true,
                null,
                null,
                null,
                null);

        MetaModelDTO published = metaModelService.publish(
                model.getPid(),
                "EventPolicyRunExecuteIntegrationTest reference fact metadata fixture",
                true,
                "EventPolicy trace reference fact metadata fixture");
        assertThat(published.getStatus()).isEqualToIgnoringCase("published");
    }

    private void publishDecisionBoundNotifyPolicy(String code, String targetKey, String decisionCode) throws Exception {
        definitionService.create(code, "Run+Exec Decision Policy", "FORM_SUBMITTED", "FORM", targetKey);

        JsonNode rulesJson = mapper.readTree(("""
            [{
              "ruleCode":"R-DECISION-NOTIFY",
              "ruleName":"decision matched notify",
              "priority":100,
              "enabled":true,
              "decisionBinding":{
                "decisionCode":"%s",
                "versionPolicy":"LATEST_PUBLISHED",
                "inputMappings":[
                  {"input":"amount","source":{"kind":"FIELD","scope":"record","path":"data.amount"}}
                ],
                "fallbackPolicy":{"mode":"FAIL_CLOSED","reason":"Decision evaluation failed"},
                "enabled":true
              },
              "actions":[{"type":"TEST_NOTIFY","target":"ROLE:mgr","order":10,"payload":{},
                 "idempotencyKeyTemplate":"${record.entityCode}:${record.recordPid}:${rule.ruleCode}:NOTIFY"}]}]
            """).formatted(decisionCode));

        DrtPolicyVersionEntity draft = versionService.createDraft(
                code, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL, ExecutionMode.ORDERED,
                FailureStrategy.CONTINUE_ON_ERROR, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, rulesJson);
        versionService.validate(draft.getPid());
        versionService.publish(draft.getPid());
    }

    private void publishApplicantDecisionBoundNotifyPolicy(String policyCode,
                                                           String modelCode,
                                                           String eventType,
                                                           String decisionCode,
                                                           String fieldCode) throws Exception {
        definitionService.create(policyCode, "Run+Exec Applicant Policy", eventType, "MODEL", modelCode);

        JsonNode rulesJson = mapper.readTree(("""
            [{
              "ruleCode":"R-APPLICANT-NOTIFY",
              "ruleName":"applicant matched notify",
              "priority":100,
              "enabled":true,
              "decisionBinding":{
                "decisionCode":"%s",
                "versionPolicy":"LATEST_PUBLISHED",
                "inputMappings":[
                  {"input":"%s","source":{"kind":"FIELD","scope":"record","path":"data.%s"}}
                ],
                "fallbackPolicy":{"mode":"FAIL_CLOSED","reason":"Decision evaluation failed"},
                "enabled":true
              },
              "actions":[{"type":"TEST_NOTIFY","target":"ROLE:mgr","order":10,"payload":{},
                 "idempotencyKeyTemplate":"${record.entityCode}:${record.recordPid}:${rule.ruleCode}:NOTIFY"}]}]
            """).formatted(decisionCode, fieldCode, fieldCode));

        DrtPolicyVersionEntity draft = versionService.createDraft(
                policyCode, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL, ExecutionMode.ORDERED,
                FailureStrategy.CONTINUE_ON_ERROR, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, rulesJson);
        versionService.validate(draft.getPid());
        versionService.publish(draft.getPid());
    }

    private Map<String, Map<String, Object>> decisionCtx(String targetKey, String recordPid, int amount) {
        return Map.of("record", Map.of(
                "entityCode", targetKey,
                "recordPid", recordPid,
                "data", Map.of("amount", amount)));
    }

    private Map<String, Map<String, Object>> applicantDecisionCtx(String modelCode,
                                                                  String recordPid,
                                                                  String fieldCode,
                                                                  String applicantPid) {
        return Map.of("record", Map.of(
                "modelCode", modelCode,
                "entityCode", modelCode,
                "recordPid", recordPid,
                "data", Map.of(fieldCode, applicantPid)));
    }

    @Test
    void runAndExecute_dispatchesToHandler_andLogsIdempotency() throws Exception {
        int before = NOTIFY_INVOCATIONS.get();
        String code = "it_runexec_" + System.nanoTime();
        String targetKey = code + "_form";
        publishNotifyPolicy(code, targetKey);

        EventPolicyExecutionResult r = runtimeService.runAndExecute("FORM_SUBMITTED", "FORM", targetKey, ctx(targetKey, "HIGH"));

        // decision half matched + resolved one NOTIFY plan
        assertThat(r.policy().status()).isEqualTo(EventPolicyResult.Status.MATCHED);
        assertThat(r.policy().actionPlans()).hasSize(1);
        // execution half: handler invoked, success, idempotency row written
        assertThat(r.execution().overallStatus()).isEqualTo(PolicyExecutionResult.OverallStatus.ALL_SUCCESS);
        assertThat(r.execution().actions().get(0).status()).isEqualTo(ActionExecutionStatus.SUCCESS);
        assertThat(NOTIFY_INVOCATIONS.get()).isEqualTo(before + 1);

        String key = r.execution().actions().get(0).idempotencyKey();
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ? and status = 'SUCCESS'",
                Integer.class, getTestTenant().getId(), key);
        assertThat(rows).isEqualTo(1);

        Integer policyRows = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id = ? and idempotency_key = ? and policy_code = ?",
                Integer.class, getTestTenant().getId(), key, code);
        assertThat(policyRows).isEqualTo(1);
    }

    @Test
    void runAndExecute_linksActionLogToDecisionTraceAndPolicyCorrelation() throws Exception {
        int before = NOTIFY_INVOCATIONS.get();
        String decisionCode = "it_runexec_decision_" + System.nanoTime();
        String code = "it_runexec_trace_" + System.nanoTime();
        String targetKey = code + "_form";
        String recordPid = "CMP-TRACE-" + System.nanoTime();
        createPublishedDecision(decisionCode);
        publishDecisionBoundNotifyPolicy(code, targetKey, decisionCode);

        EventPolicyExecutionResult r = runtimeService.runAndExecute(
                "FORM_SUBMITTED", "FORM", targetKey, decisionCtx(targetKey, recordPid, 20000));

        assertThat(r.policy().status()).isEqualTo(EventPolicyResult.Status.MATCHED);
        assertThat(r.policy().correlationId()).isNotBlank();
        assertThat(r.policy().decisionTraceIds()).hasSize(1);
        assertThat(r.execution().overallStatus()).isEqualTo(PolicyExecutionResult.OverallStatus.ALL_SUCCESS);
        assertThat(NOTIFY_INVOCATIONS.get()).isEqualTo(before + 1);

        String key = r.execution().actions().get(0).idempotencyKey();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select decision_trace_id, correlation_id, result_payload from ab_drt_policy_exec_log "
                        + "where tenant_id = ? and idempotency_key = ?",
                getTestTenant().getId(), key);
        assertThat(row.get("decision_trace_id")).isEqualTo(r.policy().decisionTraceIds().get(0));
        assertThat(row.get("correlation_id")).isEqualTo(r.policy().correlationId());
        assertThat(String.valueOf(row.get("result_payload"))).contains("sentCount").contains("channel");

        Integer decisionRows = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_log where tenant_id = ? and trace_id = ? and correlation_id = ?",
                Integer.class,
                getTestTenant().getId(),
                r.policy().decisionTraceIds().get(0),
                r.policy().correlationId());
        assertThat(decisionRows).isEqualTo(1);
    }

    @Test
    void runAndExecute_decisionBindingWritesApplicantReferenceFactMetadata() throws Exception {
        int before = NOTIFY_INVOCATIONS.get();
        String suffix = Long.toString(Math.abs(System.nanoTime()), 36);
        String modelCode = "ep_applicant_model_" + suffix;
        String fieldCode = "applicant_ref_" + suffix;
        String decisionCode = "ep_applicant_decision_" + suffix;
        String policyCode = "ep_applicant_policy_" + suffix;
        String eventType = "EP_APPLICANT_CREATED_" + suffix;
        String recordPid = "EP-APPLICANT-" + suffix;
        String applicantPid = getTestUser().getPid();

        saveUserReferenceModel(modelCode, fieldCode);
        UserSearchDTO applicant = userService.findInTenantByPid(getTestTenant().getId(), applicantPid);
        assertThat(applicant).isNotNull();
        assertThat(applicant.getDisplayName()).isNotBlank();
        createPublishedDecision(
                decisionCode,
                userReferenceEqAst(fieldCode, applicantPid),
                "record.data." + fieldCode);
        publishApplicantDecisionBoundNotifyPolicy(policyCode, modelCode, eventType, decisionCode, fieldCode);

        EventPolicyExecutionResult result = runtimeService.runAndExecute(
                eventType,
                "MODEL",
                modelCode,
                applicantDecisionCtx(modelCode, recordPid, fieldCode, applicantPid));

        assertThat(result.policy().status()).isEqualTo(EventPolicyResult.Status.MATCHED);
        assertThat(result.policy().matchedRuleCodes()).containsExactly("R-APPLICANT-NOTIFY");
        assertThat(result.policy().decisionTraceIds()).hasSize(1);
        assertThat(result.execution().overallStatus()).isEqualTo(PolicyExecutionResult.OverallStatus.ALL_SUCCESS);
        assertThat(NOTIFY_INVOCATIONS.get()).isEqualTo(before + 1);

        String traceId = result.policy().decisionTraceIds().get(0);
        Map<String, Object> metadata = jdbcTemplate.queryForMap(
                """
                select
                  trace_snapshot->'factMetadata'->?->>'label' as label,
                  trace_snapshot->'factMetadata'->?->>'modelCode' as model_code,
                  trace_snapshot->'factMetadata'->?->>'dataType' as data_type,
                  trace_snapshot->'factMetadata'->?->'valueLabels'->>? as value_label
                from ab_drt_log
                where tenant_id = ? and trace_id = ?
                """,
                "record.data." + fieldCode,
                "record.data." + fieldCode,
                "record.data." + fieldCode,
                "record.data." + fieldCode,
                applicantPid,
                getTestTenant().getId(),
                traceId);
        assertThat(metadata.get("label")).isEqualTo("申请人");
        assertThat(metadata.get("model_code")).isEqualTo(modelCode);
        assertThat(metadata.get("data_type")).isEqualTo("reference");
        assertThat(metadata.get("value_label")).isEqualTo(applicant.getDisplayName());
    }

    @Test
    void runAndExecute_noMatch_nothingToDo() throws Exception {
        String code = "it_runexec_nm_" + System.nanoTime();
        String targetKey = code + "_form";
        publishNotifyPolicy(code, targetKey);
        EventPolicyExecutionResult r = runtimeService.runAndExecute("FORM_SUBMITTED", "FORM", targetKey, ctx(targetKey, "LOW"));
        assertThat(r.policy().status()).isEqualTo(EventPolicyResult.Status.NOT_MATCHED);
        assertThat(r.execution().overallStatus()).isEqualTo(PolicyExecutionResult.OverallStatus.NOTHING_TO_DO);
    }
}
