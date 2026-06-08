package com.auraboot.framework.decision;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.eventpolicy.service.EventPolicyVersionService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.webhook.entity.WebhookSubscription;
import com.auraboot.framework.webhook.mapper.WebhookSubscriptionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WEBHOOK action end-to-end over the real stack: a published EventPolicy with a WEBHOOK action runs
 * through the executor + production {@code WebhookActionHandler} and fans out via the real
 * {@code WebhookDispatcher} to a tenant webhook subscription, which records an
 * {@code ab_webhook_delivery_log} row (docs/2.md §7). The dispatch is @Async (eventTaskExecutor with
 * a TenantAwareTaskDecorator that propagates MetaContext), so we poll for the delivery-log row. The
 * subscription points at an unreachable URL, so the attempt is logged as "failed" — which still
 * proves the full chain handler -> dispatcher -> subscription -> delivery attempt.
 *
 * <p>@Transactional(NOT_SUPPORTED): the subscription must commit so the async dispatch (separate
 * connection) sees it; @AfterEach cleans up.
 */
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookActionE2EIntegrationTest extends BaseIntegrationTest {

    @Autowired private EventPolicyDefinitionService definitionService;
    @Autowired private EventPolicyVersionService versionService;
    @Autowired private EventPolicyRuntimeService runtimeService;
    @Autowired private WebhookSubscriptionMapper subscriptionMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();
    private String subPid;
    private Long tid;

    @AfterEach
    public void cleanup() {
        try {
            if (subPid != null) {
                jdbcTemplate.update("DELETE FROM ab_webhook_delivery_log WHERE subscription_pid=?", subPid);
                jdbcTemplate.update("DELETE FROM ab_webhook_subscription WHERE pid=?", subPid);
            }
        } catch (Exception ignore) { }
    }

    @Test
    void webhookAction_fansOutToSubscription_andLogsDeliveryAttempt() throws Exception {
        tid = getTestTenant().getId();
        String eventType = "drt.webhook.test." + System.nanoTime();
        subPid = UniqueIdGenerator.generate();

        WebhookSubscription sub = new WebhookSubscription();
        sub.setPid(subPid);
        sub.setTenantId(tid);
        sub.setName("DRT webhook e2e");
        sub.setTargetUrl("http://127.0.0.1:1/webhook-unreachable"); // refused fast -> "failed" log
        sub.setEventType(eventType);
        sub.setEnabled(true);
        subscriptionMapper.insert(sub);

        String code = "it_wh_pol_" + System.nanoTime();
        definitionService.create(code, "Webhook E2E", "FORM_SUBMITTED", "FORM", "complaint");
        JsonNode rules = mapper.readTree(("""
            [{"ruleCode":"R-WH","ruleName":"webhook high","priority":100,"enabled":true,
              "condition":{"type":"compare",
                 "left":{"type":"path","scope":"record","path":"data.priority","dataType":"enum"},
                 "operator":"EQ","right":{"type":"literal","value":"HIGH","dataType":"enum"}},
              "actions":[{"type":"WEBHOOK","target":"%s","order":10,
                 "payload":{"eventType":"%s","caseId":"CMP-WH-1"},
                 "idempotencyKeyTemplate":"${record.entityCode}:${record.recordId}:${rule.ruleCode}:WH"}]}]
            """).formatted(eventType, eventType));
        var draft = versionService.createDraft(code, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED, FailureStrategy.CONTINUE_ON_ERROR, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, rules);
        versionService.validate(draft.getPid());
        versionService.publish(draft.getPid());

        var result = runtimeService.runAndExecute("FORM_SUBMITTED", "FORM", "complaint",
                Map.of("record", Map.of("entityCode", "complaint", "recordId", "CMP-WH-1",
                        "data", Map.of("priority", "HIGH"))));
        assertThat(result.policy().status().name()).isEqualTo("MATCHED");
        assertThat(result.execution().actions().get(0).status().name()).isEqualTo("SUCCESS");

        // dispatch is @Async -> poll for the delivery-log row written by the dispatcher
        Integer count = 0;
        for (int i = 0; i < 40 && count == 0; i++) {
            count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ab_webhook_delivery_log WHERE tenant_id=? AND subscription_pid=?",
                    Integer.class, tid, subPid);
            if (count == 0) {
                Thread.sleep(300);
            }
        }
        assertThat(count).as("webhook delivery attempt logged for the subscription").isGreaterThanOrEqualTo(1);
    }
}
