package com.auraboot.framework.agent.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
     */
    private void cancelRun(String runPid) {
        int updated = jdbcTemplate.update(
                "UPDATE ab_agent_run SET run_status = 'cancelled', " +
                        "    completed_at = NOW(), updated_at = NOW(), " +
                        "    error_message = COALESCE(error_message || E'\\n','') || 'cancelled by user interrupt' " +
                        "WHERE pid = ? AND run_status = 'running'", runPid);
        if (updated != 1) {
            log.debug("cancelRun: run {} no longer running (race with completion)", runPid);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
