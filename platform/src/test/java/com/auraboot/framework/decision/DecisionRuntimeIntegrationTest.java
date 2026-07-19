package com.auraboot.framework.decision;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.dto.DecisionRolloutActionRequest;
import com.auraboot.framework.decision.dto.DecisionRolloutCreateRequest;
import com.auraboot.framework.decision.dto.DecisionRolloutDTO;
import com.auraboot.framework.decision.dto.DrtLogDTO;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.VersionBinding;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import com.auraboot.framework.decision.service.DecisionRolloutService;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.DictCreateRequest;
import com.auraboot.framework.meta.dto.DictDTO;
import com.auraboot.framework.meta.dto.FieldOption;
import com.auraboot.framework.meta.dto.FieldOptionRequest;
import com.auraboot.framework.meta.dto.FieldDefinition;
import com.auraboot.framework.meta.dto.MetaFieldCreateRequest;
import com.auraboot.framework.meta.dto.MetaFieldDTO;
import com.auraboot.framework.meta.dto.ModelCapabilities;
import com.auraboot.framework.meta.dto.ModelDefinition;
import com.auraboot.framework.meta.dto.MetaModelCreateRequest;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.DictService;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaFieldService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-stack integration test for the Decision Runtime (service → MyBatis → real Postgres →
 * runtime → DecisionLog), running under the {@code integration-test} profile against the
 * {@code aura_boot} DB. Verifies the wiring a unit test cannot: the {@code JsonNodeTypeHandler}
 * jsonb round-trip in {@code ab_drt_version.content_json}, MetaContext tenant injection, the
 * publish state machine over real rows, version resolution by binding, and the
 * {@code ab_drt_log} write.
 *
 * <p>Extends {@link BaseIntegrationTest} so it inherits a committed test tenant + MetaContext and
 * a per-test transaction rolled back at the end (so created definitions/versions/logs clean up).
 */
class DecisionRuntimeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DrtDefinitionService definitionService;
    @Autowired
    private DecisionVersionService versionService;
    @Autowired
    private DecisionEvaluationService evaluationService;
    @Autowired
    private DecisionRolloutService rolloutService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MetaModelService metaModelService;
    @Autowired
    private MetaFieldService metaFieldService;
    @Autowired
    private DictService dictService;
    @Autowired
    private DynamicDataService dynamicDataService;
    @Autowired
    private UserService userService;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode amountGtAst() throws Exception {
        return amountGtAst(10000);
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

    private JsonNode statusEqAst(String fieldCode) throws Exception {
        return mapper.readTree(("""
            { "type": "compare",
              "left": { "type": "path", "scope": "record", "path": "data.%s", "dataType": "string" },
              "operator": "EQ",
              "right": { "type": "literal", "value": "pending", "dataType": "string" } }
            """).formatted(fieldCode));
    }

    private JsonNode statusSetAst(String fieldCode, String operator, List<String> values) {
        return mapper.valueToTree(Map.of(
                "type", "compare",
                "left", Map.of(
                        "type", "path",
                        "scope", "record",
                        "path", "data." + fieldCode,
                        "dataType", "enum"),
                "operator", operator,
                "right", Map.of(
                        "type", "literal",
                        "value", values,
                        "dataType", "enum")));
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

    private JsonNode businessReferenceEqAst(String fieldCode, String recordPid) {
        return mapper.valueToTree(Map.of(
                "type", "compare",
                "left", Map.of(
                        "type", "path",
                        "scope", "record",
                        "path", "data." + fieldCode,
                        "dataType", "reference"),
                "operator", "EQ",
                "right", Map.of(
                        "type", "literal",
                        "value", recordPid,
                        "dataType", "reference")));
    }

    private String createPublishedDecision(String code) throws Exception {
        return createPublishedDecision(code, amountGtAst());
    }

    private String createPublishedDecision(String code, JsonNode ast) {
        return createPublishedDecision(code, ast, "record.data.amount");
    }

    private String createPublishedDecision(String code, JsonNode ast, String expectedFieldRef) {
        createDefinition(code);
        createAndPublishVersion(code, ast, expectedFieldRef);
        return code;
    }

    private void createDefinition(String code) {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(code);
        def.setDecisionName("IT " + code);
        def.setScopeType("AUTOMATION");
        def.setOwnerModule("decision");
        definitionService.create(def);
    }

    private DrtVersionDTO createAndPublishVersion(String code, JsonNode ast) {
        return createAndPublishVersion(code, ast, "record.data.amount");
    }

    private DrtVersionDTO createAndPublishVersion(String code, JsonNode ast, String expectedFieldRef) {
        DrtVersionCreateRequest ver = new DrtVersionCreateRequest();
        ver.setKind("SIMPLE_CONDITION");
        ver.setRuntimeAdapter("AST_EVALUATOR");
        ver.setContentJson(ast);
        DrtVersionDTO draft = versionService.createDraft(code, ver);

        DecisionValidateResult validation = versionService.validate(draft.getPid());
        assertThat(validation.valid()).isTrue();
        assertThat(validation.fieldRefs()).contains(expectedFieldRef);

        DrtVersionDTO published = versionService.publish(draft.getPid());
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
        return published;
    }

    private DrtEvaluateRequest evalReq(String code, Object amount) {
        DrtEvaluateRequest req = new DrtEvaluateRequest();
        req.setDecisionCode(code);
        req.setBinding(VersionBinding.LATEST);
        req.setCallerType("API");
        req.setCallerRef("it-" + UUID.randomUUID());
        req.setCorrelationId("corr-" + UUID.randomUUID());
        req.setContext(Map.of("record", Map.of("data", Map.of("amount", amount))));
        return req;
    }

    private DrtEvaluateRequest dictEvalReq(String code, String modelCode, String fieldCode, String value) {
        DrtEvaluateRequest req = new DrtEvaluateRequest();
        req.setDecisionCode(code);
        req.setBinding(VersionBinding.LATEST);
        req.setCallerType("API");
        req.setCallerRef("dict-array-it-" + UUID.randomUUID());
        req.setCorrelationId("dict-array-" + UUID.randomUUID());
        req.setContext(Map.of(
                "record",
                Map.of(
                        "modelCode", modelCode,
                        "data", Map.of(fieldCode, value))));
        return req;
    }

    private DrtEvaluateRequest referenceEvalReq(String code, String modelCode, String fieldCode, String userPid) {
        DrtEvaluateRequest req = new DrtEvaluateRequest();
        req.setDecisionCode(code);
        req.setBinding(VersionBinding.LATEST);
        req.setCallerType("API");
        req.setCallerRef("reference-user-it-" + UUID.randomUUID());
        req.setCorrelationId("reference-user-" + UUID.randomUUID());
        req.setContext(Map.of(
                "record",
                Map.of(
                        "modelCode", modelCode,
                        "data", Map.of(fieldCode, userPid))));
        return req;
    }

    @Test
    void fullLifecycle_create_validate_publish_evaluate_persistsLog() throws Exception {
        String code = "it_big_amount_" + System.nanoTime();
        createPublishedDecision(code);

        // matched
        DecisionResult matched = evaluationService.evaluate(evalReq(code, 20000));
        assertThat(matched.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(matched.matched()).isTrue();
        assertThat(matched.traceId()).isNotBlank();
        assertThat(matched.decisionVersion()).isEqualTo(1);

        // the JSONB content_json round-tripped through the typehandler and the version resolved
        Integer storedVersions = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_version where decision_code = ? and status = 'PUBLISHED'",
                Integer.class, code);
        assertThat(storedVersions).isEqualTo(1);
        String contentOp = jdbcTemplate.queryForObject(
                "select content_json->>'operator' from ab_drt_version where decision_code = ? and version = 1",
                String.class, code);
        assertThat(contentOp).isEqualTo("GT");

        // the evaluation wrote an ab_drt_log row with the same traceId + matched=true
        Integer logRows = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_log where trace_id = ? and matched = true and status = 'MATCHED'",
                Integer.class, matched.traceId());
        assertThat(logRows).isEqualTo(1);
    }

    @Test
    void evaluate_notMatched_and_unknown_overRealStack() throws Exception {
        String code = "it_amt_" + System.nanoTime();
        createPublishedDecision(code);

        DecisionResult notMatched = evaluationService.evaluate(evalReq(code, 500));
        assertThat(notMatched.status()).isEqualTo(DecisionStatus.NOT_MATCHED);

        // missing field -> UNKNOWN (three-valued logic preserved through the full stack)
        DrtEvaluateRequest missing = new DrtEvaluateRequest();
        missing.setDecisionCode(code);
        missing.setBinding(VersionBinding.LATEST);
        missing.setCallerType("API");
        missing.setContext(Map.of("record", Map.of("data", Map.of())));
        DecisionResult unknown = evaluationService.evaluate(missing);
        assertThat(unknown.status()).isEqualTo(DecisionStatus.UNKNOWN);
        assertThat(unknown.matched()).isFalse();
    }

    @Test
    void evaluate_virtualModelFieldUsesInjectedSourceContext_andMissingSourceIsUnknown() throws Exception {
        String code = "it_virtual_risk_" + System.nanoTime();
        createPublishedDecision(code, virtualRiskScoreAst(), "record.data.slaRiskScore");

        DrtEvaluateRequest injected = new DrtEvaluateRequest();
        injected.setDecisionCode(code);
        injected.setBinding(VersionBinding.LATEST);
        injected.setCallerType("API");
        injected.setCallerRef("virtual-source-it");
        injected.setCorrelationId("virtual-" + UUID.randomUUID());
        injected.setContext(Map.of(
                "record",
                Map.of("data", Map.of(
                        "slaRiskScore", 91,
                        "_sourceRef", "virtual.leave_request_summary.v1"))));

        DecisionResult matched = evaluationService.evaluate(injected);
        assertThat(matched.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(matched.matched()).isTrue();
        assertThat(matched.outputs()).containsEntry("truth", "TRUE");

        DrtEvaluateRequest missing = new DrtEvaluateRequest();
        missing.setDecisionCode(code);
        missing.setBinding(VersionBinding.LATEST);
        missing.setCallerType("API");
        missing.setCallerRef("virtual-source-it");
        missing.setCorrelationId("virtual-missing-" + UUID.randomUUID());
        missing.setContext(Map.of("record", Map.of("data", Map.of())));

        DecisionResult unknown = evaluationService.evaluate(missing);
        assertThat(unknown.status()).isEqualTo(DecisionStatus.UNKNOWN);
        assertThat(unknown.matched()).isFalse();
        assertThat(unknown.unknownReasons())
                .anySatisfy(reason -> assertThat(reason).contains("data.slaRiskScore"));

        Integer unknownLogRows = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_log where trace_id = ? and status = 'UNKNOWN'",
                Integer.class, unknown.traceId());
        assertThat(unknownLogRows).isEqualTo(1);
    }

    @Test
    void evaluate_persistsFactMetadataSnapshotForDictBackedModelFields() throws Exception {
        String suffix = Long.toString(Math.abs(System.nanoTime()), 36);
        String modelCode = "drt_trace_meta_" + suffix;
        String fieldCode = "review_status_" + suffix;
        String dictCode = "drt_trace_status_" + suffix;
        createStatusDict(dictCode);
        saveDictBackedModel(modelCode, fieldCode, dictCode);

        String code = "it_trace_fact_meta_" + suffix;
        createPublishedDecision(code, statusEqAst(fieldCode), "record.data." + fieldCode);

        DrtEvaluateRequest req = new DrtEvaluateRequest();
        req.setDecisionCode(code);
        req.setBinding(VersionBinding.LATEST);
        req.setCallerType("API");
        req.setCallerRef("trace-fact-metadata-it");
        req.setCorrelationId("trace-fact-metadata-" + UUID.randomUUID());
        req.setContext(Map.of(
                "record",
                Map.of(
                        "modelCode", modelCode,
                        "data", Map.of(fieldCode, "pending"))));

        DecisionResult matched = evaluationService.evaluate(req);
        assertThat(matched.status()).isEqualTo(DecisionStatus.MATCHED);
        List<DrtLogDTO> logs = evaluationService.findLogsByTraceId(matched.traceId());
        assertThat(logs).hasSize(1);
        JsonNode factMetadata = logs.get(0).getTraceSnapshot().path("factMetadata");
        assertThat(factMetadata.path(fieldCode).path("label").asText()).isEqualTo("审批状态");
        assertThat(factMetadata.path(fieldCode).path("valueLabels").path("pending").asText())
                .isEqualTo("待审批");
        assertThat(factMetadata.path("record.data." + fieldCode).path("dictCode").asText())
                .isEqualTo(dictCode);
    }

    @Test
    void evaluate_persistsFactMetadataSnapshotForUserReferenceModelFields() {
        String suffix = Long.toString(Math.abs(System.nanoTime()), 36);
        String modelCode = "drt_ref_meta_" + suffix;
        String fieldCode = "applicant_ref_" + suffix;
        saveUserReferenceModel(modelCode, fieldCode);

        String applicantPid = testUser.getPid();
        UserSearchDTO applicant = userService.findInTenantByPid(testTenant.getId(), applicantPid);
        assertThat(applicant).isNotNull();
        assertThat(applicant.getDisplayName()).isNotBlank();

        String code = "it_ref_fact_meta_" + suffix;
        createPublishedDecision(code, userReferenceEqAst(fieldCode, applicantPid), "record.data." + fieldCode);

        DecisionResult matched = evaluationService.evaluate(
                referenceEvalReq(code, modelCode, fieldCode, applicantPid));
        assertThat(matched.status()).isEqualTo(DecisionStatus.MATCHED);
        List<DrtLogDTO> logs = evaluationService.findLogsByTraceId(matched.traceId());
        assertThat(logs).hasSize(1);

        JsonNode factMetadata = logs.get(0).getTraceSnapshot().path("factMetadata");
        JsonNode byPath = factMetadata.path("record.data." + fieldCode);
        assertThat(byPath.path("label").asText()).isEqualTo("申请人");
        assertThat(byPath.path("dataType").asText()).isEqualTo("reference");
        assertThat(byPath.path("valueLabels").path(applicantPid).asText())
                .isEqualTo(applicant.getDisplayName());
        assertThat(factMetadata.path(fieldCode).path("valueLabels").path(applicantPid).asText())
                .isEqualTo(applicant.getDisplayName());
    }

    @Test
    void evaluate_persistsFactMetadataSnapshotForBusinessReferenceModelFields() {
        String suffix = Long.toString(Math.abs(System.nanoTime()), 36);
        String supplierModel = "drt_supplier_" + suffix;
        String supplierNameField = "supplier_name_" + suffix;
        String ticketModel = "drt_ticket_" + suffix;
        String supplierRefField = "supplier_ref_" + suffix;
        saveBusinessReferenceModels(supplierModel, supplierNameField, ticketModel, supplierRefField);

        String supplierName = "华东审批供应商 " + suffix;
        Map<String, Object> supplier = MetaContext.runWithoutDataPermission(() ->
                dynamicDataService.create(supplierModel, Map.of(supplierNameField, supplierName)));
        String supplierPid = String.valueOf(supplier.get("pid"));
        assertThat(supplierPid).isNotBlank();

        List<FieldOption> options = MetaContext.runWithoutDataPermission(() ->
                dynamicDataService.getFieldOptions(
                        ticketModel,
                        supplierRefField,
                        FieldOptionRequest.builder().keyword("华东审批").limit(10).build()));
        assertThat(options).anySatisfy(option -> {
            assertThat(String.valueOf(option.getValue())).isEqualTo(supplierPid);
            assertThat(option.getLabel()).isEqualTo(supplierName);
        });

        Map<String, Object> ticket = MetaContext.runWithoutDataPermission(() ->
                dynamicDataService.create(ticketModel, Map.of(supplierRefField, supplierPid)));
        String ticketPid = String.valueOf(ticket.get("pid"));
        Map<String, Object> reloadedTicket = MetaContext.runWithoutDataPermission(() ->
                dynamicDataService.getById(ticketModel, ticketPid));
        assertThat(reloadedTicket.get(supplierRefField)).isEqualTo(supplierPid);
        assertThat(reloadedTicket.get(supplierRefField + "_display")).isEqualTo(supplierName);

        String code = "it_biz_ref_fact_meta_" + suffix;
        createPublishedDecision(
                code,
                businessReferenceEqAst(supplierRefField, supplierPid),
                "record.data." + supplierRefField);

        DecisionResult matched = MetaContext.runWithoutDataPermission(() ->
                evaluationService.evaluate(referenceEvalReq(code, ticketModel, supplierRefField, supplierPid)));
        assertThat(matched.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(matched.matched()).isTrue();
        List<DrtLogDTO> logs = evaluationService.findLogsByTraceId(matched.traceId());
        assertThat(logs).hasSize(1);

        JsonNode factMetadata = logs.get(0).getTraceSnapshot().path("factMetadata");
        JsonNode byPath = factMetadata.path("record.data." + supplierRefField);
        assertThat(byPath.path("label").asText()).isEqualTo("供应商");
        assertThat(byPath.path("dataType").asText()).isEqualTo("reference");
        assertThat(byPath.path("modelCode").asText()).isEqualTo(ticketModel);
        assertThat(byPath.path("valueLabels").path(supplierPid).asText()).isEqualTo(supplierName);
        assertThat(factMetadata.path(supplierRefField).path("valueLabels").path(supplierPid).asText())
                .isEqualTo(supplierName);
    }

    @Test
    void evaluate_dictBackedInAndNotInUseRawValueArraysOverRealStack() {
        String suffix = Long.toString(Math.abs(System.nanoTime()), 36);
        String modelCode = "drt_dict_array_" + suffix;
        String fieldCode = "review_status_" + suffix;
        String dictCode = "drt_dict_array_status_" + suffix;
        createStatusDict(dictCode);
        saveDictBackedModel(modelCode, fieldCode, dictCode);

        String inCode = "it_dict_in_" + suffix;
        createDefinition(inCode);
        DrtVersionDTO inVersion = createAndPublishVersion(
                inCode,
                statusSetAst(fieldCode, "IN", List.of("pending", "approved")),
                "record.data." + fieldCode);
        JsonNode inRawValues = inVersion.getContentJson().path("right").path("value");
        assertThat(inRawValues.isArray()).isTrue();
        assertThat(inRawValues).hasSize(2);
        assertThat(List.of(inRawValues.get(0).asText(), inRawValues.get(1).asText()))
                .isEqualTo(List.of("pending", "approved"));

        DecisionResult inMatched = evaluationService.evaluate(
                dictEvalReq(inCode, modelCode, fieldCode, "pending"));
        assertThat(inMatched.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(inMatched.matched()).isTrue();
        assertThat(inMatched.outputs()).containsEntry("truth", "TRUE");

        DecisionResult inNotMatched = evaluationService.evaluate(
                dictEvalReq(inCode, modelCode, fieldCode, "rejected"));
        assertThat(inNotMatched.status()).isEqualTo(DecisionStatus.NOT_MATCHED);
        assertThat(inNotMatched.matched()).isFalse();

        List<DrtLogDTO> inLogs = evaluationService.findLogsByTraceId(inMatched.traceId());
        assertThat(inLogs).hasSize(1);
        JsonNode inFactMetadata = inLogs.get(0).getTraceSnapshot().path("factMetadata");
        assertThat(inFactMetadata.path("record.data." + fieldCode).path("valueLabels").path("pending").asText())
                .isEqualTo("待审批");
        assertThat(inFactMetadata.path("record.data." + fieldCode).path("valueLabels").path("approved").asText())
                .isEqualTo("已通过");

        String notInCode = "it_dict_not_in_" + suffix;
        createDefinition(notInCode);
        DrtVersionDTO notInVersion = createAndPublishVersion(
                notInCode,
                statusSetAst(fieldCode, "NOT_IN", List.of("pending", "approved")),
                "record.data." + fieldCode);
        JsonNode notInRawValues = notInVersion.getContentJson().path("right").path("value");
        assertThat(notInRawValues.isArray()).isTrue();
        assertThat(notInRawValues).hasSize(2);

        DecisionResult notInMatched = evaluationService.evaluate(
                dictEvalReq(notInCode, modelCode, fieldCode, "rejected"));
        assertThat(notInMatched.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(notInMatched.matched()).isTrue();
        assertThat(notInMatched.outputs()).containsEntry("truth", "TRUE");

        DecisionResult notInNotMatched = evaluationService.evaluate(
                dictEvalReq(notInCode, modelCode, fieldCode, "approved"));
        assertThat(notInNotMatched.status()).isEqualTo(DecisionStatus.NOT_MATCHED);
        assertThat(notInNotMatched.matched()).isFalse();
    }

    @Test
    void evaluate_virtualModelSourceRefResolverFetchesSqlViewRecord_andCallerValuesWin() throws Exception {
        String code = "it_virtual_resolver_" + System.nanoTime();
        createPublishedDecision(code, virtualRiskScoreAst(), "record.data.slaRiskScore");

        String suffix = Long.toString(Math.abs(System.nanoTime()), 36);
        String viewName = "v_drt_src_" + suffix;
        String modelCode = "drt_virtual_risk_" + suffix;
        createRiskScoreView(viewName);
        saveRiskScoreVirtualModel(modelCode, viewName);

        try {
            DrtEvaluateRequest sourceRefOnly = new DrtEvaluateRequest();
            sourceRefOnly.setDecisionCode(code);
            sourceRefOnly.setBinding(VersionBinding.LATEST);
            sourceRefOnly.setCallerType("API");
            sourceRefOnly.setCallerRef("virtual-source-resolver-it");
            sourceRefOnly.setCorrelationId("virtual-resolver-" + UUID.randomUUID());
            sourceRefOnly.setContext(Map.of(
                    "record", Map.of("data", Map.of()),
                    "meta", Map.of("virtualSources", List.of(Map.of(
                            "sourceRef", viewName,
                            "recordId", MetaContext.getCurrentTenantId().toString())))));

            DecisionResult matched = evaluationService.evaluate(sourceRefOnly);
            assertThat(matched.status()).isEqualTo(DecisionStatus.MATCHED);
            assertThat(matched.matched()).isTrue();
            List<DrtLogDTO> matchedLogs = evaluationService.findLogsByTraceId(matched.traceId());
            assertThat(matchedLogs).hasSize(1);
            JsonNode traceSnapshot = matchedLogs.get(0).getTraceSnapshot();
            assertThat(traceSnapshot.path("virtualSources").isArray()).isTrue();
            assertThat(traceSnapshot.path("virtualSources").get(0).path("sourceRef").asText()).isEqualTo(viewName);
            assertThat(traceSnapshot.path("virtualSources").get(0).path("status").asText()).isEqualTo("RESOLVED");
            assertThat(traceSnapshot.path("virtualSources").get(0).path("fields").path("slaRiskScore").asInt())
                    .isEqualTo(91);
            assertThat(traceSnapshot.path("virtualSources").get(0).path("fields").has("tenant_id")).isFalse();
            JsonNode virtualFactMetadata = firstPresent(
                    traceSnapshot.path("factMetadata"),
                    "record.data.slaRiskScore",
                    "data.slaRiskScore",
                    "slaRiskScore");
            assertThat(traceSnapshot.path("factMetadata").has("record.data.tenant_id")).isFalse();
            assertThat(traceSnapshot.path("factMetadata").has("data.tenant_id")).isFalse();
            assertThat(traceSnapshot.path("factMetadata").has("tenant_id")).isFalse();
            assertThat(virtualFactMetadata.path("label").asText()).isEqualTo("SLA Risk Score");
            assertThat(virtualFactMetadata.path("modelCode").asText()).isEqualTo(modelCode);
            assertThat(virtualFactMetadata.path("dataType").asText()).isEqualTo("integer");
            assertThat(virtualFactMetadata.path("sourceRef").asText()).isEqualTo(viewName);

            DrtEvaluateRequest callerValueWins = new DrtEvaluateRequest();
            callerValueWins.setDecisionCode(code);
            callerValueWins.setBinding(VersionBinding.LATEST);
            callerValueWins.setCallerType("API");
            callerValueWins.setCallerRef("virtual-source-resolver-it");
            callerValueWins.setCorrelationId("virtual-resolver-override-" + UUID.randomUUID());
            callerValueWins.setContext(Map.of(
                    "record", Map.of("data", Map.of("slaRiskScore", 40)),
                    "meta", Map.of("virtualSources", List.of(Map.of(
                            "sourceRef", viewName,
                            "recordId", MetaContext.getCurrentTenantId().toString())))));

            DecisionResult notMatched = evaluationService.evaluate(callerValueWins);
            assertThat(notMatched.status()).isEqualTo(DecisionStatus.NOT_MATCHED);
            assertThat(notMatched.matched()).isFalse();

            DrtEvaluateRequest missingVirtualRow = new DrtEvaluateRequest();
            missingVirtualRow.setDecisionCode(code);
            missingVirtualRow.setBinding(VersionBinding.LATEST);
            missingVirtualRow.setCallerType("API");
            missingVirtualRow.setCallerRef("virtual-source-resolver-it");
            missingVirtualRow.setCorrelationId("vr-missing-row-" + UUID.randomUUID());
            missingVirtualRow.setContext(Map.of(
                    "record", Map.of("data", Map.of()),
                    "meta", Map.of("virtualSources", List.of(Map.of(
                            "sourceRef", viewName,
                            "recordId", "-1")))));

            DecisionResult unknown = evaluationService.evaluate(missingVirtualRow);
            assertThat(unknown.status()).isEqualTo(DecisionStatus.UNKNOWN);
            assertThat(unknown.matched()).isFalse();
            assertThat(unknown.unknownReasons())
                    .anySatisfy(reason -> assertThat(reason).contains("data.slaRiskScore"));
        } finally {
            jdbcTemplate.execute("DROP VIEW IF EXISTS " + viewName);
        }
    }

    @Test
    void evaluate_virtualModelSourceRefResolverSqlErrorFailsClosed_withoutPoisoningOuterLogTransaction()
            throws Exception {
        String code = "it_virtual_resolver_broken_" + System.nanoTime();
        createPublishedDecision(code, virtualRiskScoreAst(), "record.data.slaRiskScore");

        String suffix = Long.toString(Math.abs(System.nanoTime()), 36);
        String viewName = "v_drt_broken_src_" + suffix;
        createRiskScoreView(viewName);
        saveRiskScoreVirtualModel("drt_virtual_broken_" + suffix, viewName);
        jdbcTemplate.execute("DROP VIEW IF EXISTS " + viewName);

        try {
            DrtEvaluateRequest brokenSource = new DrtEvaluateRequest();
            brokenSource.setDecisionCode(code);
            brokenSource.setBinding(VersionBinding.LATEST);
            brokenSource.setCallerType("API");
            brokenSource.setCallerRef("virtual-source-resolver-it");
            brokenSource.setCorrelationId("vr-broken-" + UUID.randomUUID());
            brokenSource.setContext(Map.of(
                    "record", Map.of("data", Map.of()),
                    "meta", Map.of("virtualSources", List.of(Map.of(
                            "sourceRef", viewName,
                            "recordId", MetaContext.getCurrentTenantId().toString())))));

            DecisionResult unknown = evaluationService.evaluate(brokenSource);
            assertThat(unknown.status()).isEqualTo(DecisionStatus.UNKNOWN);
            assertThat(unknown.matched()).isFalse();
            assertThat(unknown.unknownReasons())
                    .anySatisfy(reason -> assertThat(reason).contains("data.slaRiskScore"));

            Integer unknownLogRows = jdbcTemplate.queryForObject(
                    "select count(*) from ab_drt_log where trace_id = ? and status = 'UNKNOWN'",
                    Integer.class, unknown.traceId());
            assertThat(unknownLogRows).isEqualTo(1);
        } finally {
            jdbcTemplate.execute("DROP VIEW IF EXISTS " + viewName);
        }
    }

    @Test
    void rolloutBinding_selectsCandidateAndPersistsRolloutMetadata() throws Exception {
        ensureRolloutSchema();
        String code = "it_rollout_" + System.nanoTime();
        createPublishedDecision(code);
        DrtVersionDTO candidate = createAndPublishVersion(code, amountGtAst(5000));
        assertThat(candidate.getVersion()).isEqualTo(2);

        DecisionRolloutCreateRequest create = new DecisionRolloutCreateRequest();
        create.setBaselineVersion(1);
        create.setCandidateVersion(2);
        create.setPercentage(100);
        create.setSalt("it-salt");
        DecisionRolloutDTO draft = rolloutService.create(code, create);

        DecisionRolloutActionRequest action = new DecisionRolloutActionRequest();
        action.setNote("integration activate");
        DecisionRolloutDTO active = rolloutService.activate(draft.getPid(), action);
        assertThat(active.getStatus()).isEqualTo("ACTIVE");

        DrtEvaluateRequest req = evalReq(code, 6000);
        req.setBinding(VersionBinding.ROLLOUT);
        req.setRoutingKey("account-123");

        DecisionResult result = evaluationService.evaluate(req);

        assertThat(result.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(result.decisionVersion()).isEqualTo(2);

        Map<String, Object> log = jdbcTemplate.queryForMap("""
                select selected_version, rollout_policy_pid, rollout_arm, routing_key, rollout_result_key
                from ab_drt_log
                where trace_id = ?
                """, result.traceId());
        assertThat(log.get("selected_version")).isEqualTo(2);
        assertThat(log.get("rollout_policy_pid")).isEqualTo(active.getPid());
        assertThat(log.get("rollout_arm")).isEqualTo("CANDIDATE");
        assertThat(log.get("routing_key")).isEqualTo("account-123");
        assertThat(log.get("rollout_result_key")).isEqualTo("matched=true,truth=TRUE");

        var metrics = rolloutService.metrics(active.getPid());
        assertThat(metrics.getCandidate().getEvaluations()).isEqualTo(1);
        assertThat(metrics.getCandidate().getMatchedRate()).isEqualTo(1.0);
        assertThat(metrics.getCandidate().getResultDistribution())
                .containsEntry("matched=true,truth=TRUE", 1L);
    }

    private void ensureRolloutSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ab_drt_rollout_policy (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    pid VARCHAR(26) UNIQUE NOT NULL,
                    tenant_id BIGINT NOT NULL,
                    decision_code VARCHAR(100) NOT NULL,
                    baseline_version INTEGER NOT NULL,
                    candidate_version INTEGER NOT NULL,
                    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
                    percentage INTEGER NOT NULL DEFAULT 0,
                    cohort_json JSONB,
                    segment_json JSONB,
                    routing_key_expr VARCHAR(200),
                    salt VARCHAR(100),
                    started_by VARCHAR(26),
                    started_at TIMESTAMPTZ,
                    ended_by VARCHAR(26),
                    ended_at TIMESTAMPTZ,
                    audit_json JSONB,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
                """);
        jdbcTemplate.execute("ALTER TABLE ab_drt_log ADD COLUMN IF NOT EXISTS selected_version INTEGER");
        jdbcTemplate.execute("ALTER TABLE ab_drt_log ADD COLUMN IF NOT EXISTS rollout_policy_pid VARCHAR(26)");
        jdbcTemplate.execute("ALTER TABLE ab_drt_log ADD COLUMN IF NOT EXISTS rollout_bucket INTEGER");
        jdbcTemplate.execute("ALTER TABLE ab_drt_log ADD COLUMN IF NOT EXISTS rollout_arm VARCHAR(20)");
        jdbcTemplate.execute("ALTER TABLE ab_drt_log ADD COLUMN IF NOT EXISTS routing_key VARCHAR(200)");
        jdbcTemplate.execute("ALTER TABLE ab_drt_log ADD COLUMN IF NOT EXISTS rollout_result_key VARCHAR(200)");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ab_drt_rollout_metric_bucket (
                    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                    tenant_id BIGINT NOT NULL,
                    rollout_policy_pid VARCHAR(26) NOT NULL,
                    decision_code VARCHAR(100) NOT NULL,
                    rollout_arm VARCHAR(20) NOT NULL,
                    bucket_seconds INTEGER NOT NULL,
                    bucket_start TIMESTAMPTZ NOT NULL,
                    evaluations BIGINT NOT NULL DEFAULT 0,
                    matched BIGINT NOT NULL DEFAULT 0,
                    errors BIGINT NOT NULL DEFAULT 0,
                    p95_latency_ms BIGINT,
                    result_distribution_json JSONB NOT NULL DEFAULT '{}'::jsonb,
                    refreshed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    CONSTRAINT uk_drt_rollout_metric_bucket UNIQUE (
                        tenant_id, rollout_policy_pid, bucket_seconds, bucket_start, rollout_arm
                    )
                )
                """);
    }

    private void createRiskScoreView(String viewName) {
        jdbcTemplate.execute("""
                CREATE OR REPLACE VIEW %s AS
                SELECT id, id AS tenant_id, 91::integer AS "slaRiskScore"
                FROM ab_tenant
                """.formatted(viewName));
    }

    private JsonNode firstPresent(JsonNode object, String... keys) {
        for (String key : keys) {
            JsonNode node = object.path(key);
            if (!node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return object.path(keys.length == 0 ? "" : keys[0]);
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
                .displayName("Decision Virtual Risk " + modelCode)
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

    private void saveDictBackedModel(String modelCode, String fieldCode, String dictCode) {
        MetaModelCreateRequest modelRequest = new MetaModelCreateRequest();
        modelRequest.setCode(modelCode);
        modelRequest.setDisplayName("Decision Trace Metadata " + modelCode);
        modelRequest.setModelType("entity");
        modelRequest.setSourceType("physical");
        modelRequest.setPrimaryKey("id");

        MetaModelDTO model = metaModelService.create(modelRequest);
        assertThat(model.getPid()).isNotBlank();

        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType("enum");
        fieldRequest.setExtension(Map.of("displayName", "审批状态"));
        fieldRequest.setAutoPublish(true);

        MetaFieldDTO field = metaFieldService.create(fieldRequest);
        assertThat(field.getPid()).isNotBlank();
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
        assertThat(metaFieldService.bindDictionary(field.getPid(), dictCode)).isTrue();

        MetaModelDTO published = metaModelService.publish(
                model.getPid(),
                "DecisionRuntimeIntegrationTest fact metadata fixture",
                true,
                "Decision trace fact metadata fixture");
        assertThat(published.getStatus()).isEqualToIgnoringCase("published");
    }

    private void saveUserReferenceModel(String modelCode, String fieldCode) {
        MetaModelCreateRequest modelRequest = new MetaModelCreateRequest();
        modelRequest.setCode(modelCode);
        modelRequest.setDisplayName("Decision Reference Metadata " + modelCode);
        modelRequest.setModelType("entity");
        modelRequest.setSourceType("physical");
        modelRequest.setPrimaryKey("id");

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
                "DecisionRuntimeIntegrationTest reference fact metadata fixture",
                true,
                "Decision trace reference fact metadata fixture");
        assertThat(published.getStatus()).isEqualToIgnoringCase("published");
    }

    private void saveBusinessReferenceModels(
            String targetModelCode,
            String targetDisplayField,
            String sourceModelCode,
            String sourceReferenceField) {
        MetaModelDTO targetModel = createDraftPhysicalModel(
                targetModelCode,
                "Decision Business Reference Target " + targetModelCode);
        MetaFieldDTO displayField = createPublishedField(
                targetDisplayField,
                "string",
                Map.of("displayName", "供应商名称", "displayField", true),
                null);
        bindField(targetModel, displayField, 1);
        MetaModelDTO publishedTarget = metaModelService.publish(
                targetModel.getPid(),
                "DecisionRuntimeIntegrationTest business reference target fixture",
                true,
                "Decision trace business reference target fixture");
        assertThat(publishedTarget.getStatus()).isEqualToIgnoringCase("published");

        MetaModelDTO sourceModel = createDraftPhysicalModel(
                sourceModelCode,
                "Decision Business Reference Source " + sourceModelCode);
        MetaFieldDTO referenceField = createPublishedField(
                sourceReferenceField,
                "reference",
                Map.of("displayName", "供应商"),
                Map.of(
                        "targetEntity", targetModelCode,
                        "displayField", targetDisplayField,
                        "valueField", "pid"));
        bindField(sourceModel, referenceField, 1);
        assertThat(referenceField.getRefTarget()).containsEntry("targetEntity", targetModelCode);
        MetaModelDTO publishedSource = metaModelService.publish(
                sourceModel.getPid(),
                "DecisionRuntimeIntegrationTest business reference source fixture",
                true,
                "Decision trace business reference source fixture");
        assertThat(publishedSource.getStatus()).isEqualToIgnoringCase("published");
    }

    private MetaModelDTO createDraftPhysicalModel(String modelCode, String displayName) {
        MetaModelCreateRequest modelRequest = new MetaModelCreateRequest();
        modelRequest.setCode(modelCode);
        modelRequest.setDisplayName(displayName);
        modelRequest.setModelType("entity");
        modelRequest.setSourceType("physical");
        modelRequest.setPrimaryKey("pid");
        MetaModelDTO model = metaModelService.create(modelRequest);
        assertThat(model.getPid()).isNotBlank();
        return model;
    }

    private MetaFieldDTO createPublishedField(
            String fieldCode,
            String dataType,
            Map<String, Object> extension,
            Map<String, Object> refTarget) {
        MetaFieldCreateRequest fieldRequest = new MetaFieldCreateRequest();
        fieldRequest.setCode(fieldCode);
        fieldRequest.setDataType(dataType);
        fieldRequest.setExtension(extension);
        fieldRequest.setRefTarget(refTarget);
        fieldRequest.setAutoPublish(true);
        MetaFieldDTO field = metaFieldService.create(fieldRequest);
        assertThat(field.getPid()).isNotBlank();
        return field;
    }

    private void bindField(MetaModelDTO model, MetaFieldDTO field, int sortOrder) {
        metaModelService.bindFieldToModel(
                model.getId(),
                field.getId(),
                sortOrder,
                false,
                true,
                true,
                null,
                null,
                null,
                null);
    }

    private DictDTO createStatusDict(String dictCode) {
        DictCreateRequest request = new DictCreateRequest();
        request.setCode(dictCode);
        request.setName("Trace Status Dict");
        request.setDictType("simple");
        request.setSourceType("static");
        request.setItems(List.of(
                dictItem("pending", "待审批", 1),
                dictItem("approved", "已通过", 2),
                dictItem("rejected", "已拒绝", 3)));
        DictDTO dict = dictService.create(request);
        assertThat(dict.getStatus()).isEqualTo("published");
        return dict;
    }

    private DictCreateRequest.DictItemCreateRequest dictItem(String value, String label, int sortOrder) {
        DictCreateRequest.DictItemCreateRequest item = new DictCreateRequest.DictItemCreateRequest();
        item.setValue(value);
        item.setLabel(label);
        item.setSortOrder(sortOrder);
        return item;
    }
}
