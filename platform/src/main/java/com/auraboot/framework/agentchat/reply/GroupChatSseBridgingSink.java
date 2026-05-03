package com.auraboot.framework.agentchat.reply;

import com.auraboot.framework.agentchat.sse.SseEmitterManager;
import com.auraboot.framework.agentchat.sse.SseEventType;
import com.auraboot.framework.conversation.ResponseSink;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Bridges {@link ResponseSink} (the chokepoint's transport-agnostic output
 * surface) to {@link SseEmitterManager} (the legacy HTTP SSE transport that
 * enterprise {@code ent-im-chat} subscribes to via {@code GET /api/im/stream}).
 *
 * <p>DC.3c: AgentReplyTask now routes its LLM call through
 * {@code ConversationTurnService.runTurn}; the chokepoint expects a
 * {@code ResponseSink} but enterprise still consumes SSE events. This bridge
 * keeps the dual-transport status from Phase D.4 intact while letting the
 * chokepoint claim become real for group-chat.
 *
 * <p>Mapping:
 * <ul>
 *   <li>{@code onTextChunk(text)} / {@code onDone(text, _)} →
 *       {@code SseEventType.STREAM_CHUNK} carrying the text. The chokepoint
 *       calls {@code onTextChunk} once with the final text in
 *       {@code AgentChatPortImpl.streamFinalResponse}, so STREAM_CHUNK fires
 *       once per turn matching the legacy AgentReplyTask SSE shape.</li>
 *   <li>{@code onDone(_, _)} → {@code STREAM_END}. Closes the per-turn SSE
 *       narrative.</li>
 *   <li>{@code onError(msg, _)} → {@code STREAM_END} with {@code error} field
 *       (matches legacy AgentReplyTask error path).</li>
 *   <li>{@code onToolStart} / {@code onToolResult} / {@code onConfirmRequired}
 *       — group chat does not surface per-tool events; no-op.</li>
 *   <li>{@code onResultContract} — group chat does not yet render
 *       result_contract cards on the SSE side; no-op.</li>
 * </ul>
 *
 * <p>DC.4 will retire {@code SseEmitterManager} entirely in a coordinated
 * cross-repo migration; this bridge is the temporary adapter that makes
 * DC.3c's chokepoint route work without forcing the enterprise frontend
 * change in the same PR.
 */
final class GroupChatSseBridgingSink implements ResponseSink {

    private final SseEmitterManager sseEmitterManager;
    private final Set<Long> humanMemberIds;
    private final Long conversationId;
    private final Long agentId;
    private final String agentName;
    private boolean doneEmitted = false;

    GroupChatSseBridgingSink(SseEmitterManager sseEmitterManager, Set<Long> humanMemberIds,
                              Long conversationId, Long agentId, String agentName) {
        this.sseEmitterManager = sseEmitterManager;
        this.humanMemberIds = humanMemberIds;
        this.conversationId = conversationId;
        this.agentId = agentId;
        this.agentName = agentName != null ? agentName : "AI";
    }

    @Override
    public void onTextChunk(String text) {
        if (text == null || text.isEmpty()) return;
        sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_CHUNK, Map.of(
                "conversationId", conversationId,
                "agentId", agentId,
                "agentName", agentName,
                "content", text));
    }

    @Override
    public void onToolStart(String toolId, String toolName, Map<String, Object> input) {
        // Group chat does not stream per-tool start events on the legacy SSE
        // surface; no-op preserves the parity with pre-DC.3c AgentReplyTask
        // behavior (which only emitted TYPING / STREAM_CHUNK / STREAM_END).
    }

    @Override
    public void onToolResult(String toolId, Map<String, Object> result, boolean success) {
        // No-op on the legacy SSE surface (see onToolStart rationale).
    }

    @Override
    public void onConfirmRequired(String toolId, String toolName, String description,
                                    Map<String, Object> input, String pendingTurnId) {
        // Group chat doesn't currently use the chokepoint's confirm-required
        // suspension flow; no-op. If a future named-agent registers a
        // confirm-required tool the user-visible behavior would silently miss
        // the prompt — wire onResultContract / onConfirmRequired here when
        // group-chat starts using confirmation tools.
    }

    @Override
    public void onError(String message, String traceId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversationId);
        data.put("agentId", agentId);
        data.put("agentName", agentName);
        data.put("error", message != null ? message : "Unknown error");
        sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, data);
        doneEmitted = true;
    }

    @Override
    public void onDone(String finalResponse, String traceId) {
        // streamFinalResponse calls onTextChunk(text) THEN onDone(text). We've
        // already streamed the text via onTextChunk; here we just close the
        // SSE narrative with STREAM_END. Avoid double-emitting if onError
        // already fired.
        if (doneEmitted) return;
        sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                "conversationId", conversationId,
                "agentId", agentId,
                "agentName", agentName));
        doneEmitted = true;
    }
}
