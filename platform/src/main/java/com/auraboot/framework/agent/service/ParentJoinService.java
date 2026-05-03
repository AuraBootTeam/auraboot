package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.memory.SessionEndedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Bridges {@link SessionEndedEvent} (fired by every run that reaches a
 * terminal state) into {@link ChildRunCompletedEvent} (fired only for runs
 * that have a {@code parent_run_id}, so the parent run / dispatcher can
 * observe child completion without polling).
 *
 * <p>P1 multi-agent join (fire-and-forget mode): the parent does NOT block
 * waiting for the child; it just receives a notification when the child
 * terminates. Synchronous waits ("blocking join") are deferred to P2.
 *
 * <p>Listener semantics:
 * <ul>
 *   <li>SELECT {@code parent_run_id, tenant_id} from {@code ab_agent_run}
 *       for the run id in the {@code SessionEndedEvent}.</li>
 *   <li>If the row is missing or {@code parent_run_id IS NULL} (root run):
 *       short-circuit, no event published.</li>
 *   <li>Otherwise publish {@code ChildRunCompletedEvent} carrying the parent
 *       run pid + child run pid + outcome label (lowercase of the
 *       {@link SessionEndedEvent.TerminalOutcome}).</li>
 * </ul>
 *
 * <p>Red-line: no fallback / placeholder values; if a row is malformed
 * (missing tenant_id), the listener logs and skips. The bridge is
 * non-transactional — it only does a SELECT + publish.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParentJoinService {

    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;

    @EventListener
    public void onSessionEnded(SessionEndedEvent event) {
        String childRunId = event.getRunId();
        if (childRunId == null || childRunId.isBlank()) {
            log.debug("ParentJoinService: SessionEndedEvent with blank runId, skipping");
            return;
        }

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT parent_run_id, tenant_id FROM ab_agent_run WHERE pid = ?",
                childRunId);
        if (rows.isEmpty()) {
            log.debug("ParentJoinService: run {} not found, skipping (already deleted?)", childRunId);
            return;
        }
        Map<String, Object> row = rows.get(0);
        String parentRunId = (String) row.get("parent_run_id");
        if (parentRunId == null || parentRunId.isBlank()) {
            // Root run — no parent to notify.
            log.debug("ParentJoinService: run {} is a root run (parent_run_id is null), no notification", childRunId);
            return;
        }
        Long tenantId = row.get("tenant_id") == null
                ? null : ((Number) row.get("tenant_id")).longValue();
        if (tenantId == null) {
            log.warn("ParentJoinService: run {} missing tenant_id, skipping ChildRunCompletedEvent", childRunId);
            return;
        }

        String outcome = event.getOutcome() == null
                ? "unknown" : event.getOutcome().name().toLowerCase();
        eventPublisher.publishEvent(new ChildRunCompletedEvent(
                tenantId, parentRunId, childRunId, outcome));
        log.info("ParentJoinService: child={} → parent={} outcome={}",
                childRunId, parentRunId, outcome);
    }
}
