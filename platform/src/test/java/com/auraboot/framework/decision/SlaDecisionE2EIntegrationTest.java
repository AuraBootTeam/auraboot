package com.auraboot.framework.decision;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.bpm.entity.BpmNotifyRecord;
import com.auraboot.framework.bpm.entity.SlaRecordEntity;
import com.auraboot.framework.bpm.mapper.BpmNotifyRecordMapper;
import com.auraboot.framework.bpm.mapper.SlaRecordMapper;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.bpm.service.SlaSchedulerService;
import com.auraboot.framework.decision.dto.DrtDefinitionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionCreateRequest;
import com.auraboot.framework.decision.dto.DrtVersionDTO;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.model.DecisionValidateResult;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.DecisionVersionPolicy;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleValueSource;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M5 consumer integration end-to-end over the real stack: an SLA config with deadlineMode=RULE whose
 * deadlineValue references a DecisionRuntime decision. Publishes the real task_assigned BpmEvent that
 * SlaActivationListener consumes (synchronous @EventListener) → computeDeadline(RULE) →
 * resolveRuleDeadlineMinutes evaluates the published decision (returning deadlineMinutes=120) →
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

    private static final String SLA_RULE_BINDING_DECISION_TABLE = """
            { "hitPolicy":"FIRST",
              "inputs":[
                {"id":"targetType","label":"Target Type","expr":{"type":"path","scope":"record","path":"data.targetType","dataType":"string"}}],
              "outputs":[
                {"id":"deadlineMinutes","label":"Deadline Minutes","dataType":"integer"},
                {"id":"calendar","label":"Calendar","dataType":"string"},
                {"id":"escalationLevel","label":"Escalation Level","dataType":"string"}],
              "rules":[
                {"ruleId":"node-sla","priority":10,
                 "when":{"targetType":{"operator":"EQ","value":"NODE"}},
                 "then":{"deadlineMinutes":120,"calendar":"standard","escalationLevel":"P1"}}],
              "defaultOutput":{"deadlineMinutes":240,"calendar":"standard","escalationLevel":"P2"} }
            """;

    @Autowired private DrtDefinitionService drtDefinitionService;
    @Autowired private DecisionVersionService drtVersionService;
    @Autowired private SlaConfigService slaConfigService;
    @Autowired private SlaSchedulerService slaSchedulerService;
    @Autowired private SlaRecordMapper slaRecordMapper;
    @Autowired private BpmNotifyRecordMapper notifyRecordMapper;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private JdbcTemplate jdbcTemplate;

    private String instanceId;
    private String slaConfigPid;
    private Long escalationRecipientId;

    @AfterEach
    public void cleanup() {
        try {
            if (instanceId != null) {
                jdbcTemplate.update("DELETE FROM ab_sla_record WHERE process_instance_id=?", instanceId);
            }
            if (slaConfigPid != null) {
                jdbcTemplate.update("DELETE FROM ab_sla_config WHERE pid=?", slaConfigPid);
            }
            if (escalationRecipientId != null) {
                jdbcTemplate.update("DELETE FROM ab_bpm_notify_record WHERE recipient_user_id=?", escalationRecipientId);
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

    private void publishRuleBindingDeadlineDecision(String code) throws Exception {
        DrtDefinitionCreateRequest def = new DrtDefinitionCreateRequest();
        def.setDecisionCode(code);
        def.setDecisionName("SLA ruleBinding deadline decision");
        def.setScopeType("SLA");
        def.setOwnerModule("decision");
        drtDefinitionService.create(def);

        DrtVersionCreateRequest ver = new DrtVersionCreateRequest();
        ver.setKind("DECISION_TABLE");
        ver.setRuntimeAdapter("PLATFORM_DECISION_TABLE");
        ver.setContentJson(com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(SLA_RULE_BINDING_DECISION_TABLE));
        DrtVersionDTO draft = drtVersionService.createDraft(code, ver);
        DecisionValidateResult validation = drtVersionService.validate(draft.getPid());
        assertThat(validation.valid()).isTrue();
        assertThat(validation.fieldRefs()).contains("record.data.targetType");
        drtVersionService.publish(draft.getPid());
    }

    @Test
    void slaActivation_withRuleMode_setsDeadlineFromDecision() throws Exception {
        Long tid = getTestTenant().getId();
        String activityId = "node_review_" + System.nanoTime();
        instanceId = "PI-M5-" + System.nanoTime();
        String decisionCode = "drt_m5_dec_" + System.nanoTime();

        publishRuleBindingDeadlineDecision(decisionCode);

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

    @Test
    void slaActivation_withRuleBinding_setsDeadlineFromDecision() throws Exception {
        Long tid = getTestTenant().getId();
        String activityId = "node_rule_binding_" + System.nanoTime();
        instanceId = "PI-SLA-RULE-BINDING-" + System.nanoTime();
        String decisionCode = "drt_sla_rule_binding_" + System.nanoTime();

        publishRuleBindingDeadlineDecision(decisionCode);

        RuleConsumerBinding ruleBinding = new RuleConsumerBinding(
                "SLA",
                "sla-rule-binding-it",
                activityId,
                RuleBindingKind.DECISION_REF,
                null,
                new DecisionBinding(
                        decisionCode,
                        DecisionVersionPolicy.LATEST_PUBLISHED,
                        null,
                        null,
                        null,
                        List.of(
                                new DecisionBinding.InputMapping(
                                        "targetType",
                                        RuleValueSource.field(Scope.RECORD, "data.targetType")),
                                new DecisionBinding.InputMapping(
                                        "targetKey",
                                        RuleValueSource.field(Scope.RECORD, "data.targetKey"))),
                        List.of(),
                        DecisionBinding.FallbackPolicy.failClosed(),
                        200,
                        DecisionBinding.TraceMode.ALWAYS,
                        true,
                        null,
                        null),
                true);

        var cfg = slaConfigService.create(new SlaConfigService.CreateSlaConfigRequest(
                "Rule Binding SLA " + System.nanoTime(), "NODE", activityId, null,
                "FIXED", "PT24H", null, null, ruleBinding, null, null, null, null));
        slaConfigPid = cfg.getPid();

        BpmEvent event = BpmEvent.of(tid, "task_assigned", "task",
                "pk-sla-rule-binding", instanceId, activityId,
                Map.of(
                        "taskInstanceId", "TASK-SLA-RULE-BINDING-1",
                        "activityId", activityId,
                        "processInstanceId", instanceId));
        eventPublisher.publishEvent(event);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT start_time, deadline_time FROM ab_sla_record WHERE tenant_id=? AND process_instance_id=? AND node_id=?",
                tid, instanceId, activityId);
        Timestamp start = (Timestamp) row.get("start_time");
        Timestamp deadline = (Timestamp) row.get("deadline_time");
        long minutes = (deadline.getTime() - start.getTime()) / 60000L;
        assertThat(minutes)
                .as("SLA ruleBinding decision deadline minutes (120), not the fixed PT24H fallback")
                .isBetween(118L, 122L);
    }

    @Test
    void slaActivation_withRuleBinding_thenSchedulerMarksOverdueAndEscalates() throws Exception {
        Long tid = getTestTenant().getId();
        String activityId = "node_rule_binding_escalation_" + System.nanoTime();
        instanceId = "PI-SLA-RULE-BINDING-ESC-" + System.nanoTime();
        String decisionCode = "drt_sla_rule_binding_esc_" + System.nanoTime();
        escalationRecipientId = 980000000L + Math.floorMod(System.nanoTime(), 1000000L);

        publishRuleBindingDeadlineDecision(decisionCode);

        RuleConsumerBinding ruleBinding = new RuleConsumerBinding(
                "SLA",
                "sla-rule-binding-escalation-it",
                activityId,
                RuleBindingKind.DECISION_REF,
                null,
                new DecisionBinding(
                        decisionCode,
                        DecisionVersionPolicy.LATEST_PUBLISHED,
                        null,
                        null,
                        null,
                        List.of(
                                new DecisionBinding.InputMapping(
                                        "targetType",
                                        RuleValueSource.field(Scope.RECORD, "data.targetType")),
                                new DecisionBinding.InputMapping(
                                        "targetKey",
                                        RuleValueSource.field(Scope.RECORD, "data.targetKey"))),
                        List.of(),
                        DecisionBinding.FallbackPolicy.failClosed(),
                        200,
                        DecisionBinding.TraceMode.ALWAYS,
                        true,
                        null,
                        null),
                true);

        var cfg = slaConfigService.create(new SlaConfigService.CreateSlaConfigRequest(
                "Rule Binding SLA Escalation " + System.nanoTime(),
                "NODE",
                activityId,
                null,
                "FIXED",
                "PT24H",
                null,
                List.of(Map.of(
                        "threshold", "50%",
                        "action", "escalate",
                        "recipients", "userId:" + escalationRecipientId)),
                ruleBinding,
                null,
                null,
                null,
                null));
        slaConfigPid = cfg.getPid();

        String taskId = "TASK-SLA-RULE-BINDING-ESC-1";
        eventPublisher.publishEvent(BpmEvent.of(tid, "task_assigned", "task",
                "pk-sla-rule-binding-escalation", instanceId, activityId,
                Map.of(
                        "taskInstanceId", taskId,
                        "activityId", activityId,
                        "processInstanceId", instanceId)));

        SlaRecordEntity created = slaRecordMapper.findByProcessInstance(instanceId, tid).stream()
                .filter(record -> activityId.equals(record.getNodeId()))
                .findFirst()
                .orElseThrow();
        assertThat(created.getTaskId()).isEqualTo(taskId);

        Instant now = Instant.now();
        jdbcTemplate.update("""
                        UPDATE ab_sla_record
                        SET start_time=?, deadline_time=?, updated_at=?
                        WHERE pid=?
                        """,
                Timestamp.from(now.minusSeconds(120)),
                Timestamp.from(now.minusSeconds(30)),
                Timestamp.from(now),
                created.getPid());

        slaSchedulerService.scanSlaRecords();
        MetaContext.setContext(tid, getTestUser().getId(), getTestUser().getPid(), getTestUser().getUserName());

        SlaRecordEntity overdue = slaRecordMapper.findByPid(created.getPid(), tid);
        assertThat(overdue.getStatus()).isEqualTo("overdue");
        assertThat(overdue.getCurrentWarningLevel()).isEqualTo(1);
        assertThat(overdue.getWarningHistory()).hasSize(1);
        assertThat(overdue.getWarningHistory().get(0)).containsEntry("action", "escalate");

        List<BpmNotifyRecord> notifications = notifyRecordMapper.findByRecipient(tid, escalationRecipientId, "urge");
        assertThat(notifications).isNotEmpty();
        assertThat(notifications.get(0).getTaskId()).isEqualTo(taskId);
        assertThat(notifications.get(0).getProcessInstanceId()).isEqualTo(instanceId);
        assertThat(notifications.get(0).getContent()).contains("SLA ESCALATION");
    }
}
