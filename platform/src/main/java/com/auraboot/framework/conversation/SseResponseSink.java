package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.dto.ResultContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * Adapter from {@link ResponseSink} to Spring {@link SseEmitter}. Mirrors the exact
 * byte shape of the existing {@code AuraBotChatService} send* helpers
 * (line 1389-1466 in OSS). This adapter exists so swapping the streaming source
 * from {@code chatService.streamChat} to {@code turnService.runTurn} produces
 * an identical SSE event stream.
 *
 * <p>Byte-level rules verified against source:
 * <ul>
 *   <li>{@code chunk} uses raw Map data
 *   <li>{@code done} / {@code error} use raw Map AND call {@code emitter.complete()}
 *   <li>{@code tool_start} / {@code tool_result} / {@code confirm_required} use
 *       {@code objectMapper.writeValueAsString(...)} (JSON-string wrapping)
 *   <li>{@code traceId} is conditionally included only when non-null
 *       ({@code Map.of(..., "traceId", null)} would NPE)
 *   <li>IO exceptions are swallowed (mirrors send* helpers' debug-log-only behavior)
 * </ul>
 *
 * <p>Phase A pre-refactor SSE baseline (recorded as
 * {@code docs/plans/2026-04/sse-baseline-2026-04-26.sha256}) is the contract this
 * adapter must preserve exactly.
 */
public class SseResponseSink implements ResponseSink {

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;

    public SseResponseSink(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onTextChunk(String text) {
        sendRaw("chunk", Map.of("content", text));
    }

    @Override
    public void onDone(String fullContent, String traceId) {
        Map<String, Object> data = new HashMap<>();
        data.put("content", fullContent);
        if (traceId != null) data.put("traceId", traceId);
        sendRaw("done", data);
        completeQuietly();
    }

    @Override
    public void onError(String message, String traceId) {
        Map<String, Object> data = new HashMap<>();
        data.put("error", message != null ? message : "Unknown error");
        if (traceId != null) data.put("traceId", traceId);
        sendRaw("error", data);
        completeQuietly();
    }

    @Override
    public void onToolStart(String toolId, String toolName, Map<String, Object> input) {
        sendJsonString("tool_start", Map.of(
                "toolId", toolId,
                "toolName", toolName,
                "input", input != null ? input : Map.of()));
    }

    @Override
    public void onToolResult(String toolId, Map<String, Object> result, boolean success) {
        sendJsonString("tool_result", Map.of(
                "toolId", toolId,
                "result", result != null ? result : Map.of(),
                "success", success));
    }

    @Override
    public void onThinking(String content, int tokens, String signature) {
        // P0-2: Anthropic Extended Thinking — frontend ThinkingBlock listens
        // for the {@code thinking} SSE event with payload
        // {@code {content, tokens, signature?}}. Wrapped in JSON-string like
        // tool_start / tool_result so the SSEEvent shape stays uniform.
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("content", content != null ? content : "");
        payload.put("tokens", tokens);
        if (signature != null) {
            payload.put("signature", signature);
        }
        sendJsonString("thinking", payload);
    }

    @Override
    public void onResultContract(ResultContract contract) {
        // Byte-for-byte parity with the legacy ResultContractEmitter.send():
        //   emitter.send(SseEmitter.event().name("result_contract")
        //           .data(objectMapper.writeValueAsString(contract)));
        // The contract is serialised directly (not wrapped in a Map) so the
        // sse-baseline-2026-04-26 stream stays identical.
        try {
            emitter.send(SseEmitter.event()
                    .name("result_contract")
                    .data(objectMapper.writeValueAsString(contract)));
        } catch (Exception e) {
            // mirror existing send* helper: swallow disconnects / IO errors
        }
    }

    @Override
    public void onConfirmRequired(String toolId, String toolName, String description,
                                    Map<String, Object> input, String pendingTurnId) {
        // pendingTurnId is conditionally included only when non-null (Map.of
        // disallows null values; matches SseResponseSink's traceId pattern).
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("toolId", toolId);
        payload.put("toolName", toolName);
        payload.put("description", description != null ? description : "");
        payload.put("input", input != null ? input : Map.of());
        if (pendingTurnId != null) {
            payload.put("pendingTurnId", pendingTurnId);
        }
        sendJsonString("confirm_required", payload);
    }

    private void sendRaw(String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (Exception e) {
            // mirror existing send* helper: swallow disconnects / IO errors
        }
    }

    private void sendJsonString(String name, Map<String, Object> data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(objectMapper.writeValueAsString(data)));
        } catch (Exception e) {
            // mirror existing sendEvent helper
        }
    }

    private void completeQuietly() {
        try {
            emitter.complete();
        } catch (Exception ignore) {
        }
    }
}
