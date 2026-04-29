package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.service.ActiveMemoryService;
import com.auraboot.framework.agent.service.AgentMemoryService;
import com.auraboot.framework.agent.triage.TriageBucket;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Phase C.2 Memory L1 writeback listener.
 *
 * <p>Subscribes to {@link TurnCompletedEvent} that {@link SpringEventEmitter}
 * publishes from {@link ConversationTurnServiceImpl#finalizeTurn}. For
 * useful-content turns (Success outcome, non-blank finalResponse, bucket !=
 * LIGHT_CHAT) it persists a user-scoped row into {@code ab_agent_memory} so
 * {@link com.auraboot.framework.agent.service.GroundingService}'s pre-recall
 * pipeline can surface it on subsequent turns — closing the loop from the
 * design v3.3 §3.2 row "Memory L1 writeback".
 *
 * <p>Filtering rules (each is "skip silently"):
 * <ul>
 *     <li>Outcome != Success — Failed / Interrupted / PendingConfirmation
 *         all carry less reliable signal; persistence on those would pollute
 *         the user's memory pool with half-finished or denied turns.</li>
 *     <li>finalResponse blank — nothing to remember.</li>
 *     <li>{@code triageBucket == LIGHT_CHAT} — trivial chat ("hi", "thanks")
 *         has no platform value; per design §3.6 the LIGHT_CHAT bucket is
 *         explicitly defined as "no platform semantics". Cuts ~80% of noise.</li>
 *     <li>userId or tenantId null — system / cron callers; memory writeback
 *         needs a user scope.</li>
 * </ul>
 *
 * <p>Importance heuristic (drives memory recall ranking):
 * <ul>
 *     <li>{@link TriageBucket#ACP_RUN}            -&gt; 5 (full action chain ran)</li>
 *     <li>{@link TriageBucket#CONTEXTUAL_ANSWER}  -&gt; 3 (page-aware explanation)</li>
 *     <li>null (triage SPI absent / pre-C.1 path) -&gt; 2 (assume some signal)</li>
 * </ul>
 *
 * <p>Defensive: any exception from the memory service is logged at warn and
 * swallowed. Spring's default {@code @EventListener} executes synchronously on
 * the publishing thread (here the controller's async worker), so a slow or
 * throwing memory write would otherwise leak back into the chokepoint and
 * affect SSE termination. We additionally annotate {@link Async} so the write
 * happens off-thread when an executor is configured — the chokepoint event
 * chain returns immediately regardless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TurnCompletionMemoryListener {

    /** Hard cap to keep individual memory rows bounded; the chat impl can
     *  emit very long final responses but L1 rows are recall material, not
     *  archival storage. Same intent as {@code AgentMemoryService} mention
     *  truncation conventions. */
    private static final int MAX_CONTENT_CHARS = 800;

    /** Stable category value so memory queries can isolate turn-summary rows
     *  from other memory categories (preferences, decisions, etc.). */
    private static final String MEMORY_CATEGORY = "conversation_turn";

    /** Memory type stored on {@code ab_agent_memory.memory_type}. Matches the
     *  convention of other turn-derived rows (free text; queries filter by
     *  category, not type). */
    private static final String MEMORY_TYPE = "turn_summary";

    private final AgentMemoryService memoryService;

    @Async
    @EventListener
    public void onTurnCompleted(TurnCompletedEvent event) {
        try {
            handle(event);
        } catch (Exception e) {
            // Never let the memory write break the event chain. The chokepoint
            // already returned the outcome to the caller before the listener
            // fired — bubbling here would orphan an audit/event signal but
            // wouldn't help the user.
            log.warn("TurnCompletionMemoryListener swallowed exception: {}", e.getMessage(), e);
        }
    }

    private void handle(TurnCompletedEvent event) {
        if (event == null || event.ctx() == null || event.outcome() == null) {
            return;
        }
        TurnContext ctx = event.ctx();
        TurnOutcome outcome = event.outcome();

        // 1. Only Success carries reliable agent-derived memory content.
        if (!(outcome instanceof TurnOutcome.Success success)) {
            return;
        }
        String finalResponse = success.finalResponse();
        if (finalResponse == null || finalResponse.isBlank()) {
            return;
        }

        // 2. Skip LIGHT_CHAT — no platform value, just preserves "hi"/"thanks" noise.
        TriageBucket bucket = ctx.triageBucket();
        if (bucket == TriageBucket.LIGHT_CHAT) {
            return;
        }

        // 3. Memory rows are user-scoped; system/cron turns have no user owner.
        Long tenantId = ctx.tenantId();
        Long userId = ctx.userId();
        if (tenantId == null || tenantId == 0L || userId == null || userId == 0L) {
            return;
        }

        String agentCode = ActiveMemoryService.DEFAULT_AGENT;
        int importance = importanceFor(bucket);
        String content = finalResponse.length() > MAX_CONTENT_CHARS
                ? finalResponse.substring(0, MAX_CONTENT_CHARS) + "..."
                : finalResponse;
        String title = deriveTitle(content);

        memoryService.createScopedMemory(
                tenantId,
                agentCode,
                MEMORY_TYPE,
                MEMORY_CATEGORY,
                title,
                content,
                importance,
                false,                                 // shareable: stay private to user by default
                "user",
                userId.toString());

        log.debug("L1 memory written for turn {} (tenant={} user={} bucket={} importance={})",
                ctx.turnId(), tenantId, userId, bucket, importance);
    }

    /**
     * Importance ranking maps bucket to recall priority. Higher importance =
     * earlier in {@code AgentMemoryService.loadByImportance} results.
     */
    private static int importanceFor(TriageBucket bucket) {
        if (bucket == null) {
            return 2;
        }
        return switch (bucket) {
            case ACP_RUN -> 5;
            case CONTEXTUAL_ANSWER -> 3;
            case LIGHT_CHAT -> 1; // unreachable — filtered above
        };
    }

    /** First non-blank line of the response, capped at 60 chars; falls back
     *  to a neutral title if the response starts with whitespace only. */
    private static String deriveTitle(String content) {
        if (content == null || content.isBlank()) {
            return "Turn summary";
        }
        String firstLine = content.lines()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .findFirst()
                .orElse("Turn summary");
        return firstLine.length() > 60 ? firstLine.substring(0, 60) + "..." : firstLine;
    }
}
