package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.service.AgentObservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turn-completion observation seam (execution-architecture review 2026-07-17
 * G1, workpackage A).
 *
 * <p>Before this listener, {@code ab_agent_observation} rows were only written
 * by the durable engine — every sync-chat turn was invisible to the CAP-02
 * eval loop, so eval sampled a biased (durable-only) distribution. This
 * listener writes one observation row for <em>every terminal turn</em> that
 * crosses the chokepoint, via the same async
 * {@link AgentObservationService#publish} path the durable side uses.
 *
 * <p>Deliberate contrasts with {@link TurnCompletionMemoryListener} (which is
 * a noise filter for the memory pool, not a telemetry seam):
 * <ul>
 *   <li><b>No triage-bucket filter.</b> LIGHT_CHAT turns are recorded too —
 *       triage Rule 4b routes real write actions ({@code 创建/编辑…}) into
 *       LIGHT_CHAT (review G3), so a bucket filter here would re-create
 *       exactly the blind spot this seam closes.</li>
 *   <li><b>No outcome filter.</b> Failed and Interrupted turns are recorded —
 *       an eval loop that only sees Success rows measures a survivor
 *       distribution.</li>
 *   <li><b>No user-scope requirement.</b> System/webhook turns are recorded;
 *       only a missing tenant scope skips (observation rows are
 *       tenant-scoped).</li>
 * </ul>
 *
 * <p>{@link TurnSuspendedEvent} (PendingConfirmation) is not terminal — the
 * turn finalizes later through the resume path and is recorded then.
 *
 * <p>Defensive: any exception is logged at warn and swallowed; {@link Async}
 * keeps the write off the chokepoint's event chain, mirroring
 * {@link TurnCompletionMemoryListener}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TurnCompletionObservationListener {

    static final String EVENT_COMPLETED = "turn.completed";
    static final String EVENT_FAILED = "turn.failed";
    static final String EVENT_INTERRUPTED = "turn.interrupted";

    /** Fallback agent id for the default AuraBot path where agentCode is null. */
    static final String DEFAULT_AGENT_ID = "aurabot";

    /** Error text is diagnostic breadcrumb, not archival storage. */
    private static final int MAX_ERROR_CHARS = 300;

    private final AgentObservationService observationService;

    @Async
    @EventListener
    public void onTurnCompleted(TurnCompletedEvent event) {
        try {
            handle(event);
        } catch (Exception e) {
            // Telemetry must never break the event chain; the outcome already
            // reached the caller before listeners fire.
            log.warn("TurnCompletionObservationListener swallowed exception: {}", e.getMessage(), e);
        }
    }

    private void handle(TurnCompletedEvent event) {
        if (event == null || event.ctx() == null || event.outcome() == null) {
            return;
        }
        TurnContext ctx = event.ctx();
        Long tenantId = ctx.tenantId();
        if (tenantId == null || tenantId == 0L) {
            return;
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        String eventType;
        switch (event.outcome()) {
            case TurnOutcome.Success s -> {
                eventType = EVENT_COMPLETED;
                detail.put("responseChars", s.finalResponse() != null ? s.finalResponse().length() : 0);
            }
            case TurnOutcome.Failed f -> {
                eventType = EVENT_FAILED;
                detail.put("error", truncate(f.errorMessage()));
            }
            case TurnOutcome.Interrupted i -> {
                eventType = EVENT_INTERRUPTED;
                detail.put("interruptReason", i.reason());
            }
            case TurnOutcome.PendingConfirmation pc -> {
                // Not terminal; emitted as TurnSuspendedEvent by finalizeTurn.
                // Defensive branch in case a future emitter widens the event.
                return;
            }
            default -> {
                return;
            }
        }

        detail.put("turnId", ctx.turnId());
        detail.put("channel", ctx.channel());
        detail.put("profileId", ctx.profileId());
        detail.put("triageBucket", ctx.triageBucket() != null ? ctx.triageBucket().name() : null);
        TurnRoute route = event.route();
        if (route != null) {
            detail.put("initialMode", route.initialMode());
            detail.put("decisionReason", route.decisionReason());
            detail.put("policySignals", route.policySignals());
        }
        detail.put("userId", ctx.userId());
        detail.put("conversationId", ctx.conversationId());
        detail.put("traceId", ctx.traceId());
        if (ctx.beginAt() != null) {
            detail.put("latencyMs", Duration.between(ctx.beginAt(), Instant.now()).toMillis());
        }

        String agentId = ctx.agentCode() != null && !ctx.agentCode().isBlank()
                ? ctx.agentCode()
                : DEFAULT_AGENT_ID;
        observationService.publish(tenantId, eventType, agentId, null, ctx.turnId(), detail);

        log.debug("Turn observation published: type={} turn={} bucket={} mode={}",
                eventType, ctx.turnId(), ctx.triageBucket(),
                route != null ? route.initialMode() : null);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_CHARS
                ? message.substring(0, MAX_ERROR_CHARS) + "..."
                : message;
    }
}
