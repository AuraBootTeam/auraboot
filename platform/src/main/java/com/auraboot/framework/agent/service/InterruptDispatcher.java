package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.memory.SessionEndedEvent;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Apply an {@link InterruptClassifier} decision to agent state:
 *
 *   replace_intent   → mark active run CANCELLED + write to interrupt_log.
 *                      The gateway is expected to then start a new run
 *                      carrying an "original run summary" as context.
 *   append_context   → log only. The new message goes into the session's
 *                      pending queue (gateway/session state responsibility);
 *                      the active run's Stage 7 loop will pick it up at
 *                      the next LLM checkpoint.
 *   insert_subtask   → log + mark "subtask_enqueued". Actual subagent
 *                      spawn is §6.10 delegate_task — not wired here;
 *                      this is the authorisation primitive.
 *
 * Always writes to {@code ab_agent_interrupt_log} regardless of policy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterruptDispatcher {

    private final JdbcTemplate jdbcTemplate;
    private final ApplicationEventPublisher eventPublisher;
    private final RunLifecycleService runLifecycleService;

    @Data
    @Builder
    public static class DispatchResult {
        private String interruptLogPid;
        private String subPolicy;
        private String actionTaken;     // cancelled_run | context_injected | subtask_enqueued | noop
        private String activeRunId;     // when replace_intent cancelled a run
    }

    /**
     * @param activeRunId the run id the gateway knows is currently active
     *                    (may be null if no active run — in which case
     *                    replace_intent is a noop).
     */
    public DispatchResult dispatch(Long tenantId, String sessionId,
                                    String activeRunId,
                                    String newMessage,
                                    InterruptClassifier.Classification classification) {
        String logPid = UniqueIdGenerator.generate();
        String action;

        switch (classification.getSubPolicy()) {
            case InterruptClassifier.REPLACE_INTENT -> {
                if (activeRunId != null) {
                    cancelRun(activeRunId);
                    action = "cancelled_run";
                } else {
                    action = "noop";
                }
            }
            case InterruptClassifier.INSERT_SUBTASK ->
                    action = "subtask_enqueued";
            case InterruptClassifier.APPEND_CONTEXT ->
                    action = "context_injected";
            default -> action = "noop";
        }

        jdbcTemplate.update(
                "INSERT INTO ab_agent_interrupt_log " +
                        "(pid, tenant_id, session_id, active_run_id, new_message_excerpt, " +
                        " sub_policy, classifier_tier, confidence, reason, action_taken) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                logPid, tenantId, sessionId, activeRunId,
                truncate(newMessage, 500),
                classification.getSubPolicy(),
                classification.getTier(),
                classification.getConfidence(),
                truncate(classification.getReason(), 500),
                action);

        log.info("Interrupt: session={} policy={} action={} run={}",
                sessionId, classification.getSubPolicy(), action, activeRunId);

        return DispatchResult.builder()
                .interruptLogPid(logPid)
                .subPolicy(classification.getSubPolicy())
                .actionTaken(action)
                .activeRunId(activeRunId)
                .build();
    }

    // =========================================================================

    /**
     * Flip an active run to 'cancelled'. Sets completed_at for durations +
     * records the cancellation reason in error_message (ab_agent_run doesn't
     * have a dedicated notes column; error_message is the audit field).
     * Idempotent — if the run has already completed / cancelled, no-op.
     *
     * <p>On a successful flip (updated=1) also fires
     * {@link SessionEndedEvent} with {@link SessionEndedEvent.TerminalOutcome#CANCELLED}
     * so any L1 {@code category='session'} memories this run wrote before
     * cancellation still reach the memory L1->L2 promoter. Without this, the
     * rows persist as L1 until the hourly orphan cron rescues them — which
     * grows the {@code OrphanBacklogGrowing} metric and creates alert noise.
     */
    private void cancelRun(String runPid) {
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_run SET run_status = 'cancelled', " +
                        "    completed_at = NOW(), updated_at = NOW(), " +
                        "    error_message = COALESCE(error_message || E'\\n','') || 'cancelled by user interrupt' " +
                        "WHERE pid = ? AND run_status = 'running'", runPid);
        if (updated != 1) {
            log.debug("cancelRun: run {} no longer running (race with completion)", runPid);
            return;
        }

        // Only publish SessionEndedEvent when THIS caller performed the cancel
        // (updated == 1) — a race-losing call returns above, so the winner
        // is the exclusive publisher.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT tenant_id, agent_id FROM ab_agent_run WHERE pid = ?", runPid);
        if (rows.isEmpty()) {
            // Row vanished between UPDATE and SELECT — should not happen for
            // a run we just flipped, but refuse to invent placeholder values.
            log.warn("cancelRun: run {} not found after cancel flip, skipping SessionEndedEvent", runPid);
            return;
        }
        Map<String, Object> row = rows.get(0);
        Long tenantId = row.get("tenant_id") == null ? null : ((Number) row.get("tenant_id")).longValue();
        String agentCode = (String) row.get("agent_id");
        if (tenantId == null || agentCode == null || agentCode.isBlank()) {
            log.warn("cancelRun: run {} missing tenant/agent ({}/{}), skipping SessionEndedEvent",
                    runPid, tenantId, agentCode);
            return;
        }

        boolean claimed = runLifecycleService.markSessionEndedPublished(runPid);
        if (!claimed) {
            log.debug("cancelRun: SessionEndedEvent already published for run {}", runPid);
            return;
        }
        Long uid = MetaContext.getCurrentUserId();
        String userId = uid == null ? null : uid.toString();
        eventPublisher.publishEvent(new SessionEndedEvent(
                tenantId, runPid, agentCode, userId,
                SessionEndedEvent.TerminalOutcome.CANCELLED));
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
