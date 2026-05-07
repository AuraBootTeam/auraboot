package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.dto.ResultContract;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * D.1 (2026-05-07) decorator around {@link ResponseSink} that captures
 * Anthropic Extended Thinking blocks while still forwarding every event to
 * the underlying transport (SSE / WS / sync-JSON). The orchestrator
 * ({@link ConversationTurnServiceImpl}) reads the captured payload at
 * {@code finalizeTurn} time and hands it to
 * {@link AuraBotTurnPersistence#persistOutbound(TurnContext, TurnOutcome,
 * TurnArtifacts)} so the prose lands on {@code ab_im_message.thinking_content}
 * + {@code thinking_signature} alongside the assistant row.
 *
 * <p>Concatenation rule: when a turn produces multiple thinking content
 * blocks (rare — Anthropic typically emits one), we join them with two
 * newlines so the persisted prose reads as one document; the signature uses
 * the LAST non-null value because Anthropic's verification token is per-block
 * and only the trailing one matters for replay continuity.
 *
 * <p>Empty turn (no {@code onThinking} ever fired) leaves both accessors
 * returning null, which {@code AuraBotTurnPersistence} maps to NULL columns
 * — see schema doc for the "no empty-string poison" red line.
 */
public class ThinkingCapturingResponseSink implements ResponseSink {

    private final ResponseSink delegate;
    private final List<String> contents = new ArrayList<>();
    private String lastSignature;

    public ThinkingCapturingResponseSink(ResponseSink delegate) {
        this.delegate = delegate;
    }

    /**
     * @return concatenated thinking prose joined by {@code "\n\n"}, or null
     *         when no thinking event fired during the turn. Empty / blank
     *         strings are also returned as null so persistence never writes
     *         empty-string poison.
     */
    public String capturedContent() {
        if (contents.isEmpty()) return null;
        String joined = String.join("\n\n", contents);
        return joined.isEmpty() ? null : joined;
    }

    /** @return signature from the last thinking block, or null when absent. */
    public String capturedSignature() {
        return lastSignature;
    }

    /** @return the wrapped sink, e.g. the SSE adapter. */
    public ResponseSink delegate() {
        return delegate;
    }

    @Override
    public void onThinking(String content, int tokens, String signature) {
        if (content != null && !content.isEmpty()) {
            contents.add(content);
        }
        if (signature != null && !signature.isEmpty()) {
            lastSignature = signature;
        }
        delegate.onThinking(content, tokens, signature);
    }

    // ------------------------------------------------------------------
    // Pure pass-through for every other event — no behaviour change.
    // ------------------------------------------------------------------

    @Override
    public void onTextChunk(String text) {
        delegate.onTextChunk(text);
    }

    @Override
    public void onToolStart(String toolId, String toolName, Map<String, Object> input) {
        delegate.onToolStart(toolId, toolName, input);
    }

    @Override
    public void onToolResult(String toolId, Map<String, Object> result, boolean success) {
        delegate.onToolResult(toolId, result, success);
    }

    @Override
    public void onConfirmRequired(String toolId, String toolName, String description,
                                    Map<String, Object> input, String pendingTurnId) {
        delegate.onConfirmRequired(toolId, toolName, description, input, pendingTurnId);
    }

    @Override
    public void onError(String message, String traceId) {
        delegate.onError(message, traceId);
    }

    @Override
    public void onDone(String finalResponse, String traceId) {
        delegate.onDone(finalResponse, traceId);
    }

    @Override
    public void onResultContract(ResultContract contract) {
        delegate.onResultContract(contract);
    }

    @Override
    public boolean isClientConnected() {
        return delegate.isClientConnected();
    }
}
