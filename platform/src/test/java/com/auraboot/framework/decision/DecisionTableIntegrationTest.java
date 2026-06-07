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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-stack IT for a DECISION_TABLE decision through the full runtime (service → MyBatis → real
 * Postgres → DecisionTableAdapter → DecisionTableEvaluator). Verifies the table content JSONB
 * round-trip + adapter selection by kind + hitPolicy FIRST outputs + default output over real PG.
 */
class DecisionTableIntegrationTest extends BaseIntegrationTest {

    @Autowired private DrtDefinitionService definitionService;
    @Autowired private DecisionVersionService versionService;
    @Autowired private DecisionEvaluationService evaluationService;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String TABLE = """
        { "hitPolicy":"FIRST",
          "inputs":[
            {"id":"amount","label":"Amount","expr":{"type":"path","scope":"record","path":"data.amount","dataType":"decimal"}},
            {"id":"priority","label":"Priority","expr":{"type":"path","scope":"record","path":"data.priority","dataType":"enum"}}],
          "outputs":[{"id":"route","label":"Route","dataType":"string"}],
          "rules":[
            {"ruleId":"row-2","priority":20,
             "when":{"amount":{"operator":"GT","value":10000},"priority":{"operator":"EQ","value":"HIGH"}},
             "then":{"route":"director"}}],
          "defaultOutput":{"route":"manager"} }
        """;

    private DrtEvaluateRequest evalReq(String code, Object amount, Object priority) {
        DrtEvaluateRequest req = new DrtEvaluateRequest();
        req.setDecisionCode(code);
        req.setBinding(VersionBinding.LATEST);
        req.setCallerType("API");
        req.setContext(Map.of("record", Map.of("data", Map.of("amount", amount, "priority", priority))));
        return req;
    }

    @Test
    void decisionTable_fullLifecycle_routesByHitPolicyOverRealStack() throws Exception {
        String code = "it_table_" + System.nanoTime();
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(code);
        def.setDecisionName("IT Table");
        def.setScopeType("BPM");
        def.setOwnerModule("decision");
        definitionService.create(def);

        DrtVersionCreateRequest ver = new DrtVersionCreateRequest();
        ver.setKind("DECISION_TABLE");
        ver.setRuntimeAdapter("PLATFORM_DECISION_TABLE");
        JsonNode tableNode = mapper.readTree(TABLE);
        ver.setContentJson(tableNode);
        DrtVersionDTO draft = versionService.createDraft(code, ver);

        DecisionValidateResult validation = versionService.validate(draft.getPid());
        assertThat(validation.valid()).isTrue();
        assertThat(validation.fieldRefs()).contains("record.data.amount", "record.data.priority");

        DrtVersionDTO published = versionService.publish(draft.getPid());
        assertThat(published.getStatus()).isEqualTo("PUBLISHED");

        // big amount + HIGH → row-2 → director
        DecisionResult director = evaluationService.evaluate(evalReq(code, 20000, "HIGH"));
        assertThat(director.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(director.outputs()).containsEntry("route", "director");
        assertThat(director.matchedRules()).extracting(DecisionResult.MatchedRule::ruleId).contains("row-2");

        // no row matches → default → manager
        DecisionResult fallback = evaluationService.evaluate(evalReq(code, 500, "NORMAL"));
        assertThat(fallback.status()).isEqualTo(DecisionStatus.MATCHED);
        assertThat(fallback.outputs()).containsEntry("route", "manager");
    }
}
