package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.dto.ResultContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SseResponseSink}. Locks the byte-format contract that
 * the locked baseline at {@code docs/plans/2026-04/sse-baseline-2026-04-26.sha256}
 * captured: every event the chat path can emit must produce the same Spring
 * {@link SseEmitter.SseEventBuilder#build()} output as the legacy
 * {@code AuraBotChatService} send* helpers (Phase A pre-refactor).
 *
 * <p>The tests mock {@link SseEmitter} and capture the event builders Spring
 * receives, then invoke {@code build()} on each captured builder to introspect
 * the produced byte chunks. This is a structural assertion: the data payload
 * we hand to Spring is exactly what the original helpers produced.
 *
 * <p>Cases:
 * <ol>
 *     <li>{@code onTextChunk} -> name=chunk, payload is a raw Map {content: text}</li>
 *     <li>{@code onDone} (no traceId) -> name=done, payload Map {content}, complete() called</li>
 *     <li>{@code onDone} (with traceId) -> Map {content, traceId}</li>
 *     <li>{@code onError} (no traceId) -> name=error, payload Map {error}, complete() called</li>
 *     <li>{@code onError} (with traceId) -> Map {error, traceId}</li>
 *     <li>{@code onError} (null message) -> error=Unknown error fallback</li>
 *     <li>{@code onToolStart} -> name=tool_start, payload is JSON-string of {toolId, toolName, input}</li>
 *     <li>{@code onToolResult} -> name=tool_result, payload JSON-string of {toolId, result, success}</li>
 *     <li>{@code onConfirmRequired} -> name=confirm_required, payload JSON-string of
 *         {toolId, toolName, description, input}; null description -> empty string fallback</li>
 *     <li>IOException from {@code emitter.send} is swallowed (mirrors send* helpers)</li>
 * </ol>
 */
@DisplayName("SseResponseSink — byte alignment with locked SSE baseline")
class SseResponseSinkTest {

    private SseEmitter emitter;
    private ObjectMapper objectMapper;
    private SseResponseSink sink;

    @BeforeEach
    void setUp() {
        emitter = mock(SseEmitter.class);
        objectMapper = new ObjectMapper();
        sink = new SseResponseSink(emitter, objectMapper);
    }

    /** Capture the single SseEventBuilder Spring receives via {@code emitter.send}. */
    private SseEmitter.SseEventBuilder captureBuilder() throws IOException {
        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter).send(captor.capture());
        return captor.getValue();
    }

    /** Pull the actual {@code data} payload out of a built event. Spring's
     *  {@code SseEventBuilder.build()} returns an ordered set of
     *  {@code DataWithMediaType} entries. For a typical event:
     *  <ol>
     *      <li>{@code "event:NAME\ndata:"}  (combined header + leading "data:")</li>
     *      <li>the payload object Spring will serialize (Map or String)</li>
     *      <li>{@code "\n\n"} (terminator)</li>
     *  </ol>
     *  We keep the entry whose data is neither a header line nor the terminator. */
    private Object firstDataPayload(SseEmitter.SseEventBuilder b) {
        for (ResponseBodyEmitter.DataWithMediaType d : b.build()) {
            Object data = d.getData();
            if (data instanceof CharSequence cs) {
                String s = cs.toString();
                if (s.startsWith("event:") || s.startsWith("data:") || s.isBlank()) {
                    continue;
                }
            }
            return data;
        }
        throw new AssertionError("no payload chunk in built event: " + b.build());
    }

    /** Extract the event name from the leading {@code "event:NAME\ndata:"} entry. */
    private String eventName(SseEmitter.SseEventBuilder b) {
        for (ResponseBodyEmitter.DataWithMediaType d : b.build()) {
            Object data = d.getData();
            if (data instanceof CharSequence cs && cs.toString().startsWith("event:")) {
                String s = cs.toString();
                // Format: "event:NAME\ndata:"  (Spring concatenates the data: prefix)
                int nl = s.indexOf('\n');
                String namePart = (nl > 0) ? s.substring("event:".length(), nl) : s.substring("event:".length());
                return namePart.trim();
            }
        }
        throw new AssertionError("no event: prefix in built event");
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("1) onTextChunk -> name=chunk, raw Map {content:text}")
    @SuppressWarnings("unchecked")
    void onTextChunk_rawMapPayload() throws IOException {
        sink.onTextChunk("hello world");

        SseEmitter.SseEventBuilder b = captureBuilder();
        assertThat(eventName(b)).isEqualTo("chunk");
        assertThat(firstDataPayload(b))
                .isInstanceOf(Map.class)
                .satisfies(d -> {
                    Map<String, Object> m = (Map<String, Object>) d;
                    assertThat(m).containsExactly(Map.entry("content", "hello world"));
                });
        verify(emitter, never()).complete();
    }

    @Test
    @DisplayName("2) onDone w/o traceId -> name=done, Map {content}, complete()")
    @SuppressWarnings("unchecked")
    void onDone_noTraceId_completesEmitter() throws IOException {
        sink.onDone("the final answer", null);

        SseEmitter.SseEventBuilder b = captureBuilder();
        assertThat(eventName(b)).isEqualTo("done");
        Map<String, Object> data = (Map<String, Object>) firstDataPayload(b);
        assertThat(data).containsExactly(Map.entry("content", "the final answer"));
        assertThat(data).doesNotContainKey("traceId");
        verify(emitter, times(1)).complete();
    }

    @Test
    @DisplayName("3) onDone with traceId -> Map {content, traceId}, complete()")
    @SuppressWarnings("unchecked")
    void onDone_withTraceId_includesTraceIdField() throws IOException {
        sink.onDone("answer", "trace-abc-123");

        SseEmitter.SseEventBuilder b = captureBuilder();
        assertThat(eventName(b)).isEqualTo("done");
        Map<String, Object> data = (Map<String, Object>) firstDataPayload(b);
        assertThat(data)
                .containsEntry("content", "answer")
                .containsEntry("traceId", "trace-abc-123");
        verify(emitter, times(1)).complete();
    }

    @Test
    @DisplayName("4) onError w/o traceId -> name=error, Map {error}, complete()")
    @SuppressWarnings("unchecked")
    void onError_noTraceId_completesEmitter() throws IOException {
        sink.onError("LLM unavailable", null);

        SseEmitter.SseEventBuilder b = captureBuilder();
        assertThat(eventName(b)).isEqualTo("error");
        Map<String, Object> data = (Map<String, Object>) firstDataPayload(b);
        assertThat(data).containsExactly(Map.entry("error", "LLM unavailable"));
        verify(emitter, times(1)).complete();
    }

    @Test
    @DisplayName("5) onError with traceId -> Map {error, traceId}")
    @SuppressWarnings("unchecked")
    void onError_withTraceId_includesTraceIdField() throws IOException {
        sink.onError("rate limited", "trace-xyz");

        SseEmitter.SseEventBuilder b = captureBuilder();
        assertThat(eventName(b)).isEqualTo("error");
        Map<String, Object> data = (Map<String, Object>) firstDataPayload(b);
        assertThat(data)
                .containsEntry("error", "rate limited")
                .containsEntry("traceId", "trace-xyz");
    }

    @Test
    @DisplayName("6) onError with null message -> error=Unknown error fallback")
    @SuppressWarnings("unchecked")
    void onError_nullMessage_fallsBackToUnknown() throws IOException {
        sink.onError(null, null);

        SseEmitter.SseEventBuilder b = captureBuilder();
        Map<String, Object> data = (Map<String, Object>) firstDataPayload(b);
        assertThat(data).containsEntry("error", "Unknown error");
    }

    @Test
    @DisplayName("7) onToolStart -> name=tool_start, JSON-string {toolId, toolName, input}")
    void onToolStart_jsonStringPayload() throws Exception {
        sink.onToolStart("tool-1", "platform_list_models", Map.of("keyword", "sales"));

        SseEmitter.SseEventBuilder b = captureBuilder();
        assertThat(eventName(b)).isEqualTo("tool_start");
        Object payload = firstDataPayload(b);
        assertThat(payload).isInstanceOf(String.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue((String) payload, Map.class);
        assertThat(parsed)
                .containsEntry("toolId", "tool-1")
                .containsEntry("toolName", "platform_list_models");
        assertThat(parsed.get("input")).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) parsed.get("input")).containsEntry("keyword", "sales");
        verify(emitter, never()).complete();
    }

    @Test
    @DisplayName("8) onToolResult -> name=tool_result, JSON-string {toolId, result, success}")
    void onToolResult_jsonStringPayload() throws Exception {
        sink.onToolResult("tool-1", Map.of("rows", 5), true);

        SseEmitter.SseEventBuilder b = captureBuilder();
        assertThat(eventName(b)).isEqualTo("tool_result");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue((String) firstDataPayload(b), Map.class);
        assertThat(parsed)
                .containsEntry("toolId", "tool-1")
                .containsEntry("success", true);
        assertThat((Map<String, Object>) parsed.get("result")).containsEntry("rows", 5);
    }

    @Test
    @DisplayName("9) onConfirmRequired -> JSON-string {toolId, toolName, description, input, pendingTurnId}; null desc -> ''")
    void onConfirmRequired_nullDescriptionFallsBackToEmpty() throws Exception {
        sink.onConfirmRequired("tool-2", "cmd_delete_record", null, Map.of("id", 99), "01HW3KTURN");

        SseEmitter.SseEventBuilder b = captureBuilder();
        assertThat(eventName(b)).isEqualTo("confirm_required");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue((String) firstDataPayload(b), Map.class);
        assertThat(parsed)
                .containsEntry("toolId", "tool-2")
                .containsEntry("toolName", "cmd_delete_record")
                .containsEntry("description", "")
                .containsEntry("pendingTurnId", "01HW3KTURN");
        assertThat((Map<String, Object>) parsed.get("input")).containsEntry("id", 99);
        verify(emitter, never()).complete();
    }

    @Test
    @DisplayName("9b) onConfirmRequired w/ null pendingTurnId -> field omitted from payload")
    void onConfirmRequired_nullPendingTurnId_fieldOmitted() throws Exception {
        sink.onConfirmRequired("tool-3", "cmd_x", "do x", Map.of(), null);

        SseEmitter.SseEventBuilder b = captureBuilder();
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue((String) firstDataPayload(b), Map.class);
        assertThat(parsed).doesNotContainKey("pendingTurnId");
    }

    @Test
    @DisplayName("10) emitter.send IOException is swallowed (mirrors send* helper behaviour)")
    void emitterIoException_swallowed() throws IOException {
        doThrow(new IOException("client disconnected")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

        // None of these should throw — they must mirror the send* helpers' debug-log-only behavior.
        sink.onTextChunk("anything");
        sink.onDone("done", null);
        sink.onError("err", null);
        sink.onToolStart("t1", "n", Map.of());
        sink.onToolResult("t1", Map.of(), false);
        sink.onConfirmRequired("t1", "n", "d", Map.of(), "turn-1");
        sink.onResultContract(ResultContract.builder().skillCode("x").status("success")
                .renderHint("summary").outputType("text").build());

        // We expect 7 send attempts (one per method) — all swallowed.
        verify(emitter, times(7)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    @DisplayName("11) onResultContract -> name=result_contract, JSON-string of the ResultContract object")
    void onResultContract_jsonStringPayload() throws Exception {
        // Phase C.3b: byte-for-byte parity with the legacy ResultContractEmitter.send():
        //   emitter.send(SseEmitter.event().name("result_contract")
        //           .data(objectMapper.writeValueAsString(contract)));
        // The contract must be serialised directly (NOT wrapped in a Map) so the
        // sse-baseline-2026-04-26 stream stays identical when ResultContractEmitter
        // pushes through the sink rather than the prior ChatSseContext direct path.
        ResultContract contract = ResultContract.builder()
                .skillCode("nq_customer_list")
                .durationMs(142L)
                .status("success")
                .actionability("read_only")
                .outputType("structured_result")
                .renderHint("table")
                .table(List.of(Map.of("pid", "01A", "name", "Acme")))
                .textSummary("1 total, 1 shown")
                .build();

        sink.onResultContract(contract);

        SseEmitter.SseEventBuilder b = captureBuilder();
        assertThat(eventName(b)).isEqualTo("result_contract");
        // Direct ResultContract JSON — not a Map wrapper. Lombok @Data + Jackson
        // produce the canonical JSON we asserted on under the legacy path.
        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = objectMapper.readValue((String) firstDataPayload(b), Map.class);
        assertThat(parsed)
                .containsEntry("skillCode", "nq_customer_list")
                .containsEntry("status", "success")
                .containsEntry("renderHint", "table")
                .containsEntry("textSummary", "1 total, 1 shown");
        assertThat(((Number) parsed.get("durationMs")).longValue()).isEqualTo(142L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> table = (List<Map<String, Object>>) parsed.get("table");
        assertThat(table).hasSize(1);
        assertThat(table.get(0)).containsEntry("name", "Acme");
        verify(emitter, never()).complete();
    }
}
