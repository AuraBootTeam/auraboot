package com.auraboot.framework.decision;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.service.DecisionVersionService;
import com.auraboot.framework.decision.service.DrtDefinitionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 consumer integration end-to-end over the real stack: an SLA config with deadlineMode=RULE whose
 * deadlineValue references a DecisionRuntime decision. Publishes the real task_assigned BpmEvent that
 * SlaActivationListener consumes (synchronous @EventListener) → computeDeadline(RULE) →
 * resolveRuleDeadlineMinutes evaluates the published decision (a DMN returning deadlineMinutes=120) →
 * creates an ab_sla_record whose deadline = start + 120min (NOT the 24h fallback), proving the
 * decision drove the SLA deadline through the live activation path (docs/2.md M5).
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SlaDecisionE2EIntegrationTest extends BaseIntegrationTest {

    private static final String DMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
                     namespace="https://auraboot/dmn/sla" name="sla" id="sla">
          <decision id="d_dl" name="deadlineMinutes">
            <variable name="deadlineMinutes" typeRef="number"/>
            <literalExpression><text>120</text></literalExpression>
          </decision>
        </definitions>
        """;

    @Autowired private DrtDefinitionService drtDefinitionService;
    @Autowired private DecisionVersionService drtVersionService;
    @Autowired private SlaConfigService slaConfigService;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String instanceId;
    private String slaConfigPid;

    @AfterEach
    public void cleanup() {
        try {
            if (instanceId != null) {
                jdbcTemplate.update("DELETE FROM ab_sla_record WHERE process_instance_id=?", instanceId);
            }
            if (slaConfigPid != null) {
                jdbcTemplate.update("DELETE FROM ab_sla_config WHERE pid=?", slaConfigPid);
            }
        } catch (Exception ignore) { }
    }

    private void publishDmnDeadlineDecision(String code) throws Exception {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(code);
        def.setDecisionName("M5 SLA deadline decision");
        def.setScopeType("SLA");
        def.setOwnerModule("decision");
        drtDefinitionService.create(def);

        DrtVersionCreateRequest ver = new DrtVersionCreateRequest();
        ver.setKind("DMN");
        ver.setRuntimeAdapter("DROOLS_DMN");
        ver.setContentJson(TextNode.valueOf(DMN));
        DrtVersionDTO draft = drtVersionService.createDraft(code, ver);
        drtVersionService.validate(draft.getPid());
        drtVersionService.publish(draft.getPid());
    }

    @Test
    void slaActivation_withRuleMode_setsDeadlineFromDecision() throws Exception {
        Long tid = getTestTenant().getId();
        String activityId = "node_review_" + System.nanoTime();
        instanceId = "PI-M5-" + System.nanoTime();
        String decisionCode = "drt_m5_dec_" + System.nanoTime();

        publishDmnDeadlineDecision(decisionCode);

        var cfg = slaConfigService.create(new SlaConfigService.CreateSlaConfigRequest(
                "M5 SLA " + System.nanoTime(), "NODE", activityId, null,
                "RULE", decisionCode, null, null, null, null, null, null, null));
        slaConfigPid = cfg.getPid();

        // publish the task_assigned BpmEvent the SlaActivationListener consumes (synchronous)
        BpmEvent event = BpmEvent.of(tid, "task_assigned", "task",
                "pk-m5", instanceId, activityId,
                Map.of("taskId", "TASK-M5-1", "activityId", activityId, "processInstanceId", instanceId));
        eventPublisher.publishEvent(event);

        // the listener created an SLA record whose deadline came from the decision (120 min), not 24h
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT start_time, deadline_time FROM ab_sla_record WHERE tenant_id=? AND process_instance_id=? AND node_id=?",
                tid, instanceId, activityId);
        Timestamp start = (Timestamp) row.get("start_time");
        Timestamp deadline = (Timestamp) row.get("deadline_time");
        long minutes = (deadline.getTime() - start.getTime()) / 60000L;
        assertThat(minutes).as("SLA deadline minutes from the decision (120), not the 24h fallback")
                .isBetween(118L, 122L);
    }
}
