package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.dto.ResultContract;
import com.auraboot.framework.conversation.sink.StreamErrorClassifier;
import com.auraboot.framework.conversation.turn.TurnHandle;
import com.auraboot.framework.conversation.turn.TurnRegistry;
import com.auraboot.framework.im.dto.WsFrame;
import com.auraboot.framework.im.model.ImConstants;
import com.auraboot.framework.im.pubsub.ImMessageBroadcaster;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Broadcasts AI turn lifecycle events (ai_turn_started / stream_chunk / stream_end /
 * ai_turn_completed / ai_turn_failed) to all human members of a group conversation as
 * the LLM streams. Per-turn instance (constructed in AgentReplyTask).
 *
 * <p>G1 design notes: replaces the prior TYPING_INDICATOR-based proxy stream with
 * dedicated frame types (iOS group chat does not consume TYPING_INDICATOR — verified
 * via grep, 2026-05-29 spike). Per-user-typing in ImWebSocketHandler is unaffected.
 *
 * <p>Chunk batching: 50ms time window OR 30-char volume — whichever arrives first.
 *
 * <p>Cancel: cancellation is signaled via a flag flipped by {@link TurnRegistry#markCancelled};
 * {@link #onTextChunk} checks the flag and throws {@link TurnCancelledException} so the
 * upstream LLM call stack unwinds. AgentReplyTask catches this and does not record it
 * as an error. {@code ai_turn_cancelled} is broadcast directly by ConversationTurnController
 * (not by this sink) so users get immediate UI feedback.
 *
 * <p>Tool/confirm/result-contract frames: retained from Phase D.1 — onToolStart emits
 * a TYPING_INDICATOR with phase annotation; onConfirmRequired and onResultContract emit
 * MESSAGE card frames. These are compatible with the new frame scheme.
 *
 * <p>Persistence is orthogonal: this sink does NOT write {@code ab_im_message} rows.
 * The chokepoint's {@code Persistence.persistOutbound} in {@code finalizeTurn} writes
 * the row through {@code ImMessageService.sendAgentMessage}; the broadcaster's role is
 * purely transport.
 */
public class BroadcastResponseSink implements ResponseSink {

    private static final int CHUNK_VOLUME_THRESHOLD = 30;
    private static final long CHUNK_TIME_THRESHOLD_MS = 50L;

    private final ImMessageBroadcaster broadcaster;
    private final List<Long> targetUserIds;
    private final Long conversationId;
    private final String turnId;
    private final Long agentId;
    private final String agentName;
    private final Long initiatorUserId;
    private final Long replyToMessageId;
    private final TurnRegistry registry;

    // IMPL-10 — cumulativeBuf/chunkBuf are intentionally UN-synchronized. The contract is that a
    // single turn's provider stream is consumed on one thread, so onTextChunk (the only writer) is
    // never called concurrently. If a future change ever appends/flushes these from another thread
    // it silently corrupts the StringBuilder; the dev-only assert in onTextChunk (assertSingleWriter)
    // makes that contract violation fail fast under -ea instead of producing garbled output.
    private final StringBuilder cumulativeBuf = new StringBuilder();
    private final StringBuilder chunkBuf = new StringBuilder();
    private long chunkLastFlush = System.currentTimeMillis();
    /** First thread to append; used only by the dev-only single-writer assert (see assertSingleWriter). */
    private Thread writerThread;

    private AtomicBoolean cancelled;  // captured from TurnHandle in onTurnBegin

    public BroadcastResponseSink(ImMessageBroadcaster broadcaster, List<Long> targetUserIds,
                                  Long conversationId, String turnId, Long agentId, String agentName,
                                  Long initiatorUserId, Long replyToMessageId, TurnRegistry registry) {
        this.broadcaster = broadcaster;
        this.targetUserIds = targetUserIds;
        this.conversationId = conversationId;
        this.turnId = turnId;
        this.agentId = agentId;
        this.agentName = agentName;
        this.initiatorUserId = initiatorUserId;
        this.replyToMessageId = replyToMessageId;
        this.registry = registry;
    }

    @Override
    public void onTurnBegin(String turnIdParam, Long agentIdParam, Long conversationIdParam,
                             Long replyToMessageIdParam, Long initiatorUserIdParam) {
        TurnHandle handle = registry.register(turnId, conversationId, agentId, agentName,
                                               initiatorUserId, replyToMessageId);
        this.cancelled = handle.getCancelled();

        Map<String, Object> data = new HashMap<>();
        data.put("turnId", turnId);
        data.put("agentId", agentId);
        data.put("agentName", agentName);
        data.put("conversationId", conversationId);
        data.put("replyToMessageId", replyToMessageId);
        data.put("initiatorUserId", initiatorUserId);
        data.put("startedAt", handle.getStartedAt().toString());

        broadcaster.publish(targetUserIds, WsFrame.builder()
                .type(ImConstants.WS_AI_TURN_STARTED)
                .data(data)
                .build());
    }

    @Override
    public void onTextChunk(String text) {
        if (cancelled != null && cancelled.get()) {
            throw new TurnCancelledException(turnId);
        }
        if (text == null || text.isEmpty()) return;
        assert assertSingleWriter() : "BroadcastResponseSink buffers appended from >1 thread ("
                + writerThread + " vs " + Thread.currentThread() + "); the un-synchronized "
                + "StringBuilders assume single-threaded stream consumption (IMPL-10).";
        chunkBuf.append(text);
        cumulativeBuf.append(text);

        long now = System.currentTimeMillis();
        if (chunkBuf.length() >= CHUNK_VOLUME_THRESHOLD || now - chunkLastFlush >= CHUNK_TIME_THRESHOLD_MS) {
            flushChunkBuf(now);
        }
    }

    /**
     * Dev-only single-writer check: returns true on the first append (capturing the thread) and on
     * same-thread appends; false only when a DIFFERENT thread writes, which the {@code assert} in
     * onTextChunk turns into an AssertionError under {@code -ea}. Never throws in production
     * (assertions disabled) — zero prod cost.
     */
    private boolean assertSingleWriter() {
        Thread current = Thread.currentThread();
        if (writerThread == null) {
            writerThread = current;
            return true;
        }
        return writerThread == current;
    }

    private void flushChunkBuf(long now) {
        if (chunkBuf.length() == 0) return;
        String delta = chunkBuf.toString();

        Map<String, Object> data = new HashMap<>();
        data.put("turnId", turnId);
        data.put("agentId", agentId);
        data.put("conversationId", conversationId);
        data.put("delta", delta);
        data.put("cumulative", cumulativeBuf.toString());

        broadcaster.publish(targetUserIds, WsFrame.builder()
                .type(ImConstants.WS_STREAM_CHUNK)
                .data(data)
                .build());

        // also append to registry handle's cumulative for offline resume
        registry.get(turnId).ifPresent(h -> h.appendCumulative(delta));

        chunkBuf.setLength(0);
        chunkLastFlush = now;
    }

    @Override
    public void onDone(String finalResponse, String traceId) {
        if (chunkBuf.length() > 0) {
            flushChunkBuf(System.currentTimeMillis());
        }

        Map<String, Object> endData = new HashMap<>();
        endData.put("turnId", turnId);
        endData.put("agentId", agentId);
        endData.put("conversationId", conversationId);
        endData.put("finalMessageId", null);
        endData.put("totalTokens", null);

        broadcaster.publish(targetUserIds, WsFrame.builder()
                .type(ImConstants.WS_STREAM_END)
                .data(endData)
                .build());

        Map<String, Object> completeData = new HashMap<>();
        completeData.put("turnId", turnId);
        completeData.put("conversationId", conversationId);
        completeData.put("agentId", agentId);

        broadcaster.publish(targetUserIds, WsFrame.builder()
                .type(ImConstants.WS_AI_TURN_COMPLETED)
                .data(completeData)
                .build());

        registry.markCompleted(turnId);
    }

    @Override
    public void onError(String message, String traceId) {
        String errorCode = StreamErrorClassifier.classify(message, traceId);

        Map<String, Object> data = new HashMap<>();
        data.put("turnId", turnId);
        data.put("agentId", agentId);
        data.put("conversationId", conversationId);
        data.put("errorCode", errorCode);
        data.put("errorMessage", message);

        broadcaster.publish(targetUserIds, WsFrame.builder()
                .type(ImConstants.WS_AI_TURN_FAILED)
                .data(data)
                .build());

        registry.markFailed(turnId);
    }

    @Override
    public void onToolStart(String toolId, String toolName, Map<String, Object> input) {
        // Keep typing-phase annotation for tool execution phases — compatible with G1 frame scheme.
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
        // No-op at the IM transport — structured tool result is rendered by the
        // result_contract pipeline (onResultContract) when the agent emits one.
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
        return true;
    }

    // onTurnCancelled inherits the default no-op from ResponseSink (G1 design;
    // Controller broadcasts ai_turn_cancelled directly).
}
