package com.auraboot.framework.eventpolicy;

import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.service.EventPolicyDefinitionService;
import com.auraboot.framework.eventpolicy.service.EventPolicyVersionService;
import com.auraboot.framework.eventpolicy.service.OutboxService;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EventPolicy outbox over the real stack (docs/2.md §9): enqueue a PENDING event in a transaction,
 * then process it — which runs the bound policy's run-and-execute. Asserts the full chain via real
 * Postgres rows: outbox PENDING → PROCESSED, and an {@code ab_drt_policy_exec_log} row written for
 * the matched rule's action (proving outbox → runAndExecute → resolve → executor). Also verifies
 * enqueue idempotency on (tenant, eventId). The recipient and original actor are actual active
 * members, so this happy path also verifies successful durable notification delivery.
 */
// The worker claims committed events in its own transaction, as it does after a real request.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxIntegrationTest extends BaseIntegrationTest {

    @Autowired private EventPolicyDefinitionService definitionService;
    @Autowired private EventPolicyVersionService versionService;
    @Autowired private OutboxService outboxService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    private void publishNotifyPolicy(String code, String targetKey) throws Exception {
        definitionService.create(code, "Outbox IT", "FORM_SUBMITTED", "FORM", targetKey);
        JsonNode rules = mapper.readTree("""
            [{"ruleCode":"R-N","ruleName":"n","priority":100,"enabled":true,
              "condition":{"type":"compare",
                 "left":{"type":"path","scope":"record","path":"data.priority","dataType":"enum"},
                 "operator":"EQ","right":{"type":"literal","value":"HIGH","dataType":"enum"}},
              "actions":[{"type":"NOTIFY","target":"USER:%s","order":10,
                 "payload":{"title":"Outbox test","content":"Delivered by the real outbox policy"},
                 "idempotencyKeyTemplate":"${record.entityCode}:${record.recordPid}:${rule.ruleCode}:NOTIFY"}]}]
            """.formatted(getTestUser().getPid()));
        var draft = versionService.createDraft(code, PolicyPhase.AFTER_COMMIT, MatchMode.COLLECT_ALL,
                ExecutionMode.ORDERED, FailureStrategy.CONTINUE_ON_ERROR, ConflictStrategy.REJECT_ON_CONFLICT,
                DedupStrategy.BY_IDEMPOTENCY_KEY, rules);
        versionService.validate(draft.getPid());
        versionService.publish(draft.getPid());
    }

    private JsonNode ctx(String targetKey) throws Exception {
        return mapper.valueToTree(Map.of(
                "record", Map.of("entityCode", targetKey, "recordPid", "CMP-OB-1",
                        "data", Map.of("priority", "HIGH")),
                "actor", Map.of("userId", getTestUser().getId(), "userPid", getTestUser().getPid())));
    }

    @Test
    void enqueueThenProcess_runsPolicyChain_andMarksProcessed() throws Exception {
        String targetKey = "it_outbox_form_" + System.nanoTime();
        publishNotifyPolicy("it_outbox_" + System.nanoTime(), targetKey);
        Long tid = getTestTenant().getId();
        String eventId = "evt-ob-" + System.nanoTime();

        outboxService.enqueue(eventId, "FORM_SUBMITTED", "FORM", targetKey, ctx(targetKey));
        assertThat(jdbcTemplate.queryForObject(
                "select status from ab_drt_outbox where tenant_id=? and event_id=?", String.class, tid, eventId))
                .isEqualTo("PENDING");

        int processed = outboxService.processPending(10);
        assertThat(processed).isGreaterThanOrEqualTo(1);

        // outbox row marked PROCESSED
        assertThat(jdbcTemplate.queryForObject(
                "select status from ab_drt_outbox where tenant_id=? and event_id=?", String.class, tid, eventId))
                .isEqualTo("PROCESSED");

        // the bound policy matched (priority=HIGH) and its NOTIFY action went through the executor:
        // an exec-log row exists for the rule's resolved idempotency key (proves outbox->runAndExecute->executor)
        Integer execRows = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_policy_exec_log where tenant_id=? and idempotency_key=?",
                Integer.class, tid, targetKey + ":CMP-OB-1:R-N:NOTIFY");
        assertThat(execRows).isEqualTo(1);
        String key = targetKey + ":CMP-OB-1:R-N:NOTIFY";
        assertThat(jdbcTemplate.queryForObject(
                "select status from ab_drt_policy_exec_log where tenant_id=? and idempotency_key=?",
                String.class, tid, key)).isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "select result_payload->'targetUserIds'->>0 from ab_drt_policy_exec_log where tenant_id=? and idempotency_key=?",
                String.class, tid, key)).isEqualTo(String.valueOf(getTestUser().getId()));
    }

    @Test
    void enqueueIsIdempotentOnEventId() throws Exception {
        Long tid = getTestTenant().getId();
        String eventId = "evt-ob-dup-" + System.nanoTime();
        String targetKey = "it_outbox_dup_form_" + System.nanoTime();
        outboxService.enqueue(eventId, "FORM_SUBMITTED", "FORM", targetKey, ctx(targetKey));
        outboxService.enqueue(eventId, "FORM_SUBMITTED", "FORM", targetKey, ctx(targetKey));
        Integer rows = jdbcTemplate.queryForObject(
                "select count(*) from ab_drt_outbox where tenant_id=? and event_id=?", Integer.class, tid, eventId);
        assertThat(rows).isEqualTo(1);
    }
}
