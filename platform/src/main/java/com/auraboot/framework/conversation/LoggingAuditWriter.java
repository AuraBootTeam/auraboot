package com.auraboot.framework.conversation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Phase B.3 implementation of {@link TurnSideEffects.AuditWriter}. Replaces
 * {@link TurnSideEffects.AuditWriter#NOOP} that Phase A injected so failed
 * turns leave a structured audit trail.
 *
 * <p>Implementation choice: structured WARN log via slf4j rather than a new
 * dedicated DB table. Reasons:
 * <ul>
 *     <li>Existing audit tables ({@code ab_ai_action_audit_log},
 *         {@code ab_command_audit_log}, etc.) are purpose-specific (action /
 *         command level) — turn-level failure is a different semantic and
 *         polluting them would force NOT-NULL columns that don't apply
 *         (e.g., {@code user_decision} on {@code ab_ai_action_audit_log}).</li>
 *     <li>Production log aggregation (Sentry / Loki) already captures WARN+
 *         lines with structured fields; failure rate dashboards work off the
 *         log stream rather than a custom table.</li>
 *     <li>Keeps B.3 scope tight — adding a new table + migration would expand
 *         the PR substantially without observable user value over structured
 *         logs in the current dev stage.</li>
 * </ul>
 *
 * <p>If a real audit table becomes a Phase C hard requirement (e.g., compliance
 * needs query-able historical record), swap this impl for a {@code DbAuditWriter}
 * — the {@link TurnSideEffects.AuditWriter} SPI is the seam.
 *
 * <p>The structured fields (turnId, tenantId, userId, conversationId, error,
 * cause) line up with the {@link TurnContext} record so log aggregation can
 * filter by tenant or correlate by turnId.
 */
@Slf4j
@Component
public class LoggingAuditWriter implements TurnSideEffects.AuditWriter {

    @Override
    public void writeFailure(TurnContext ctx, TurnOutcome.Failed failed) {
        if (ctx == null || failed == null) {
            return;
        }
        log.warn("turn-failure-audit turnId={} tenantId={} userId={} memberId={} "
                        + "conversationId={} agentId={} error={} causeClass={} causeMessage={}",
                ctx.turnId(),
                ctx.tenantId(),
                ctx.userId(),
                ctx.humanMemberId(),
                ctx.conversationId(),
                ctx.agentId(),
                failed.errorMessage(),
                failed.cause() != null ? failed.cause().getClass().getName() : "(none)",
                failed.cause() != null ? failed.cause().getMessage() : null);
    }
}
