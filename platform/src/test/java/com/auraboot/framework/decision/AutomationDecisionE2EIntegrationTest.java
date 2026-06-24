package com.auraboot.framework.decision;

import com.auraboot.framework.automation.dto.AutomationCreateRequest;
import com.auraboot.framework.automation.dto.AutomationDTO;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.TriggerConfig;
import com.auraboot.framework.automation.service.AutomationService;
import com.auraboot.framework.automation.trigger.AutomationTriggerService;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M4 consumer integration end-to-end over the real stack: an automation whose trigger_config
 * references a DecisionRuntime decision and whose condition reads the injected {@code #decision}
 * variable. Fires the real automation engine (onRecordCreate) and asserts it triggered — which can
 * only happen if {@code withDecision} evaluated the published decision and injected
 * {@code #decision.matched=true} so the SpEL condition passed (docs/2.md M4). Proves the additive
 * decisionRef wiring works through the live trigger pipeline, not just the unit-level injection.
 *
 * <p>@Transactional(NOT_SUPPORTED) so the automation + decision commit (the @Async trigger runs on a
 * separate connection); @AfterEach cleans up.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AutomationDecisionE2EIntegrationTest extends BaseIntegrationTest {

    @Autowired private DrtDefinitionService drtDefinitionService;
    @Autowired private DecisionVersionService drtVersionService;
    @Autowired private AutomationService automationService;
    @Autowired private AutomationTriggerService triggerService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private Long automationId;
    private String automationPid; // ab_automation_log.automation_id stores the automation PID (VARCHAR)
    private String decisionCode;

    @AfterEach
    public void cleanup() {
        try {
            if (automationPid != null) {
                jdbcTemplate.update("DELETE FROM ab_automation_log WHERE automation_id=?", automationPid);
            }
            if (automationId != null) {
                jdbcTemplate.update("DELETE FROM ab_automation WHERE id=?", automationId);
            }
        } catch (Exception ignore) { }
    }

    private void publishDecision(String code) throws Exception {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(code);
        def.setDecisionName("M4 e2e decision");
        def.setScopeType("AUTOMATION");
        def.setOwnerModule("decision");
        drtDefinitionService.create(def);

        JsonNode ast = mapper.readTree("""
            { "type":"compare",
              "left":{"type":"path","scope":"record","path":"data.priority","dataType":"enum"},
              "operator":"EQ","right":{"type":"literal","value":"HIGH","dataType":"enum"} }""");
        DrtVersionCreateRequest ver = new DrtVersionCreateRequest();
        ver.setKind("SIMPLE_CONDITION");
        ver.setRuntimeAdapter("AST_EVALUATOR");
        ver.setContentJson(ast);
        DrtVersionDTO draft = drtVersionService.createDraft(code, ver);
        drtVersionService.validate(draft.getPid());
        drtVersionService.publish(draft.getPid());
    }

    @Test
    void automationTriggers_whenReferencedDecisionMatches_viaInjectedDecisionVar() throws Exception {
        String modelCode = "drt_m4_" + System.nanoTime();
        decisionCode = "drt_m4_dec_" + System.nanoTime();
        publishDecision(decisionCode);

        AutomationCreateRequest req = new AutomationCreateRequest();
        req.setName("M4 E2E " + System.nanoTime());
        req.setModelCode(modelCode);
        req.setTriggerType("on_record_create");
        TriggerConfig cfg = new TriggerConfig();
        cfg.setModelCode(modelCode);
        cfg.setDecisionRef(decisionCode);
        req.setTriggerConfig(cfg);
        // triggers only if the injected decision matched
        req.setTriggerCondition("#decision['matched'] == true");
        req.setActions(List.of(AutomationAction.builder()
                .type("update_record")
                .config(Map.of("modelCode", modelCode, "recordPid", "${recordPid}",
                        "fields", Map.of("note", "auto")))
                .build()));
        req.setEnabled(true);
        AutomationDTO dto = automationService.create(req);
        automationId = dto.getId();
        automationPid = dto.getPid();
        if (dto.getEnabled() == null || !dto.getEnabled()) {
            automationService.enable(dto.getPid());
        }

        // fire the real trigger pipeline with priority=HIGH (so the referenced decision matches)
        triggerService.onRecordCreate(modelCode, "REC-M4-1", Map.of("priority", "HIGH"));

        // @Async — poll ab_automation_log for a trigger record (only written if the condition passed,
        // which requires #decision to have been injected + matched)
        Integer count = 0;
        for (int i = 0; i < 40 && count == 0; i++) {
            count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ab_automation_log WHERE automation_id=?", Integer.class, automationPid);
            if (count == 0) {
                Thread.sleep(300);
            }
        }
        assertThat(count).as("automation triggered via injected #decision match").isGreaterThanOrEqualTo(1);
    }
}
