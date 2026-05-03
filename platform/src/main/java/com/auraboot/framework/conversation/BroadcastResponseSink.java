package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.pubsub.ImMessageBroadcaster;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase D.1 (Q-D.2=α) WebSocket-broadcast adapter for {@link ResponseSink}. Used
 * by IM-event-driven entry points (group-chat @mention #7, WebSocket @AI
 * panel #8 per design v3.3 §3.5) where a sync HTTP response stream does not
 * exist — output goes to all online conversation members via
 * {@link ImMessageBroadcaster}.
 *
 * <p>Semantic differences from {@link SseResponseSink}:
 * <ul>
 *   <li><b>Streaming vs terminal-only</b> — IM clients render full messages
 *       (no chunked typing animation in the row). The sink buffers
 *       {@link #onTextChunk} into an internal builder; on {@link #onDone}
 *       it publishes a single {@code MESSAGE} frame with the full text.
 *       {@code onTextChunk} additionally fires a {@code TYPING_INDICATOR}
 *       frame so the conversation members see "AI is typing…" while the
 *       LLM streams.</li>
 *   <li><b>No transport completion</b> — there is no {@code emitter.complete()}
 *       analogue; WebSocket sessions are long-lived and not tied to one turn.</li>
 *   <li><b>Persistence is orthogonal</b> — this sink does NOT write
 *       {@code ab_im_message} rows. The chokepoint's {@code Persistence.persistOutbound}
 *       in {@code finalizeTurn} writes the row through {@code ImMessageService.sendAgentMessage};
 *       the broadcaster's role is purely transport (push WS frame to listeners).
 *       This separation keeps "every turn produces exactly one outbound row"
 *       invariant identical across SSE and IM channels.</li>
 *   <li><b>onResultContract</b> — turned into a {@code MESSAGE} frame with
 *       {@code messageType="card"} so the IM card renderer picks it up. Same
 *       payload shape as the SSE {@code result_contract} event.</li>
 *   <li><b>onConfirmRequired</b> — published as a {@code MESSAGE} frame with
 *       a card payload carrying the resumption token. Frontend echoes it back
 *       via {@code POST /execute} (same contract as the SSE flow; the resume
 *       dispatcher in {@code ConversationTurnServiceImpl} handles both).</li>
 * </ul>
 *
 * <p>The list of {@code targetUserIds} (conversation members to deliver to)
 * is passed at construction time — the IM event handler resolves it from the
 * conversation membership before invoking {@code runTurn}.
 *
 * <p>Threading: {@link #onTextChunk} accumulates text in a
 * {@link StringBuilder}; the chokepoint serializes calls within one turn
 * (chat impl is single-threaded per turn), so no synchronization is needed.
 * {@link #isClientConnected} returns true unconditionally — WebSocket
 * delivery is fire-and-forget; partially-disconnected clients are handled
 * by {@link ImMessageBroadcaster} (LocalBroadcaster drops, RedisBroadcaster
 * Pub/Sub) without surfacing back to this sink.
 *
 * <p><b>DC.4 (2026-05-03) transport unification:</b> the parallel HTTP-SSE
 * transport ({@code SseEmitterManager} + {@code ImSseController} +
 * {@code GroupChatSseBridgingSink}) was deleted; this sink is now the sole
 * push surface for chokepoint output. Enterprise {@code ent-im-chat}
 * consumes the same {@code WS /api/im/ws} frames as the OSS web-admin IM
 * panel — see the matching DC.4 frontend commit replacing
 * {@code imSseClient.ts} with {@code imWsClient.ts}.
 */
public class BroadcastResponseSink implements ResponseSink {

    private final ImMessageBroadcaster broadcaster;
    private final List<Long> targetUserIds;
    private final Long conversationId;
    private final StringBuilder textBuffer = new StringBuilder();

    public BroadcastResponseSink(ImMessageBroadcaster broadcaster,
                                  List<Long> targetUserIds,
                                  Long conversationId) {
        this.broadcaster = broadcaster;
        this.targetUserIds = targetUserIds;
        this.conversationId = conversationId;
    }

    /** Test-only accessor — exposes the buffered LLM output so tests can assert
     *  that {@link #onTextChunk} actually accumulated content before
     *  {@link #onDone} flushes. */
    String bufferedText() {
        return textBuffer.toString();
    }

    @Override
    public void onTextChunk(String text) {
        if (text == null || text.isEmpty()) return;
        textBuffer.append(text);
        // Light-weight typing indicator so members see "AI typing…" while the
        // LLM streams. The IM frontend is expected to debounce these — we just
        // forward what the chokepoint emits.
        broadcaster.publish(targetUserIds, WsFrame.builder()
                .type("TYPING_INDICATOR")
                .data(Map.of(
                        "conversationId", conversationId,
                        "state", "typing"))
                .build());
    }

    @Override
    public void onDone(String fullContent, String traceId) {
        // Phase D.2 contract refinement: the sink does NOT emit a MESSAGE frame
        // on onDone. The persisted ab_im_message row is written later by
        // {@code Persistence.persistOutbound} in the chokepoint's finalizeTurn,
        // and only THEN does the caller broadcast a MESSAGE frame carrying the
        // proper {messageId, seq, senderId, ...} metadata (see
        // {@code ImAiService.generateResponse}). Emitting a content-only
        // MESSAGE frame here without {messageId, seq} would force IM clients to
        // either render duplicate rows or invent fallback ids — both worse than
        // a brief "AI thinking…" → "AI typed:" transition.
        //
        // We DO publish a TYPING_INDICATOR(state=stopped) so the typing dots
        // disappear promptly even before persistOutbound fires, and we keep
        // the buffered text reachable via {@link #bufferedText} for tests /
        // diagnostics that want to assert what the LLM produced.
        broadcaster.publish(targetUserIds, WsFrame.builder()
                .type("TYPING_INDICATOR")
                .data(Map.of(
                        "conversationId", conversationId,
                        "state", "stopped"))
                .build());
    }

    @Override
    public void onError(String message, String traceId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversationId);
        data.put("error", message != null ? message : "Unknown error");
        if (traceId != null) data.put("traceId", traceId);
        broadcaster.publish(targetUserIds, WsFrame.builder()
                .type("ERROR")
                .data(data)
                .build());
    }

    @Override
    public void onToolStart(String toolId, String toolName, Map<String, Object> input) {
        // IM channels do not show per-tool spinners (the conversation member
        // list does not benefit from tool-level granularity). We keep the
        // typing indicator alive to signal "still working" but do not push a
        // dedicated frame.
        broadcaster.publish(targetUserIds, WsFrame.builder()
                .type("TYPING_INDICATOR")
                .data(Map.of(
                        "conversationId", conversationId,
                        "state", "typing",
                        "phase", "tool:" + (toolName != null ? toolName : "unknown")))
                .build());
    }

    @Override
    public void onToolResult(String toolId, Map<String, Object> result, boolean success) {
        // No-op at the IM transport — the structured tool result is rendered
        // by the result_contract pipeline (onResultContract) when the agent
        // emits one, or absorbed into the final answer for plain tool_use
        // rounds. Surfacing every tool_result as a MESSAGE frame would flood
        // the conversation.
    }

    @Override
    public void onConfirmRequired(String toolId, String toolName, String description,
                                    Map<String, Object> input, String pendingTurnId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("messageType", "card");
        payload.put("cardType", "confirm_required");
        payload.put("toolId", toolId);
        payload.put("toolName", toolName);
        payload.put("description", description != null ? description : "");
        payload.put("input", input != null ? input : Map.of());
        if (pendingTurnId != null) {
            payload.put("pendingTurnId", pendingTurnId);
        }
        broadcaster.publish(targetUserIds, WsFrame.builder()
                .type("MESSAGE")
                .data(payload)
                .build());
    }

    @Override
    public void onResultContract(ResultContract contract) {
        if (contract == null) return;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("messageType", "card");
        payload.put("cardType", "result_contract");
        payload.put("contract", contract);
        broadcaster.publish(targetUserIds, WsFrame.builder()
                .type("MESSAGE")
                .data(payload)
                .build());
    }

    @Override
    public boolean isClientConnected() {
        // WebSocket delivery is fire-and-forget through the broadcaster;
        // there is no per-turn liveness to report up. The LocalBroadcaster /
        // RedisBroadcaster impl handles drop semantics internally.
        return true;
    }
}
