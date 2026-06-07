package com.auraboot.framework.decision;

import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtEvaluateRequest;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.model.DecisionResult;
import com.auraboot.framework.decision.model.DecisionStatus;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.model.VersionBinding;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.decision.service.DecisionEvaluationService;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode amountGtAst() throws Exception {
        return mapper.readTree("""
            { "type": "compare",
              "left": { "type": "path", "scope": "record", "path": "data.amount", "dataType": "decimal" },
              "operator": "GT",
              "right": { "type": "literal", "value": 10000, "dataType": "decimal" } }
            """);
    }

    private String createPublishedDecision(String code) throws Exception {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(code);
        def.setDecisionName("IT " + code);
        def.setScopeType("AUTOMATION");
        def.setOwnerModule("decision");
        definitionService.create(def);

        DrtVersionCreateRequest ver = new DrtVersionCreateRequest();
        ver.setKind("SIMPLE_CONDITION");
        ver.setRuntimeAdapter("AST_EVALUATOR");
        ver.setContentJson(amountGtAst());
        DrtVersionDTO draft = versionService.createDraft(code, ver);

        DecisionValidateResult validation = versionService.validate(draft.getPid());
        assertThat(validation.valid()).isTrue();
        assertThat(validation.fieldRefs()).contains("record.data.amount");

        DrtVersionDTO published = versionService.publish(draft.getPid());
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");
        assertThat(published.getVersion()).isEqualTo(1);
        return code;
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
}
