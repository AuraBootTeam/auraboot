package com.auraboot.framework.conversation;

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

    /** Package-private accessor so executeAuraBotTurn can wire ChatSseContext.setEmitter compat. */
    SseEmitter getEmitter() {
        return emitter;
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
    public void onConfirmRequired(String toolId, String toolName, String description, Map<String, Object> input) {
        sendJsonString("confirm_required", Map.of(
                "toolId", toolId,
                "toolName", toolName,
                "description", description != null ? description : "",
                "input", input != null ? input : Map.of()));
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
