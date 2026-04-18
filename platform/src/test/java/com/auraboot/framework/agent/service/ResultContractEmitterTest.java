package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for ResultContractEmitter. Uses a capturing SseEmitter subclass
 * to verify the emitted event name + payload shape for each tool type and
 * edge case (success/failure/empty result/BIF-derived actionability).
 */
@DisplayName("ResultContractEmitter — SSE event shape")
class ResultContractEmitterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ResultContractEmitter emitter;
    private CapturingEmitter sse;

    @BeforeEach
    void setup() {
        emitter = new ResultContractEmitter(mapper);
        sse = new CapturingEmitter();
        ChatSseContext.setEmitter(sse);
    }

    @AfterEach
    void clear() {
        ChatSseContext.clear();
        BifContext.clear();
    }

    private AgentToolDefinition queryTool(String name) {
        AgentToolDefinition t = new AgentToolDefinition();
        t.setName(name);
        t.setToolType("dsl_query");
        return t;
    }

    private AgentToolDefinition commandTool(String name) {
        AgentToolDefinition t = new AgentToolDefinition();
        t.setName(name);
        t.setToolType("dsl_command");
        return t;
    }

    @Test
    @DisplayName("dsl_query success emits result_contract with renderHint=table + rows")
    void query_success_emits_table() throws Exception {
        String json = mapper.writeValueAsString(Map.of(
                "total", 2,
                "returned", 2,
                "records", List.of(
                        Map.of("pid", "01A", "name", "Acme"),
                        Map.of("pid", "01B", "name", "Globex"))));

        emitter.emitQueryResult("nq_customer_list", queryTool("nq_customer_list"), json, 142, true);

        assertThat(sse.events).hasSize(1);
        CapturedEvent ev = sse.events.get(0);
        assertThat(ev.name).isEqualTo("result_contract");
        Map<String, Object> data = mapper.readValue(ev.data, Map.class);
        assertThat(data.get("status")).isEqualTo("success");
        assertThat(data.get("skillCode")).isEqualTo("nq_customer_list");
        assertThat(data.get("renderHint")).isEqualTo("table");
        assertThat(((Number) data.get("durationMs")).longValue()).isEqualTo(142);
        List<?> table = (List<?>) data.get("table");
        assertThat(table).hasSize(2);
        assertThat(((Map<?, ?>) table.get(0)).get("name")).isEqualTo("Acme");
    }

    @Test
    @DisplayName("dsl_query empty result emits renderHint=summary with '0 results'")
    void query_empty_emits_summary() throws Exception {
        String json = mapper.writeValueAsString(Map.of("total", 0, "returned", 0, "records", List.of()));

        emitter.emitQueryResult("nq_lead_list", queryTool("nq_lead_list"), json, 30, true);

        Map<String, Object> data = mapper.readValue(sse.events.get(0).data, Map.class);
        assertThat(data.get("renderHint")).isEqualTo("summary");
        assertThat(data.get("textSummary")).isEqualTo("0 results");
    }

    @Test
    @DisplayName("dsl_query failure emits status=failed + renderHint=summary")
    void query_failure_emits_failed() throws Exception {
        emitter.emitQueryResult("nq_fail", queryTool("nq_fail"), "Error executing query", 10, false);

        Map<String, Object> data = mapper.readValue(sse.events.get(0).data, Map.class);
        assertThat(data.get("status")).isEqualTo("failed");
        assertThat(data.get("renderHint")).isEqualTo("summary");
    }

    @Test
    @DisplayName("dsl_command success with data emits renderHint=card")
    void command_success_card() throws Exception {
        String json = mapper.writeValueAsString(Map.of(
                "success", true,
                "data", Map.of("pid", "01REC", "crm_lead_company", "TestCo"),
                "message", "Lead created"));

        emitter.emitCommandResult("cmd_create_lead", commandTool("cmd_create_lead"), json, 215, true, null);

        Map<String, Object> data = mapper.readValue(sse.events.get(0).data, Map.class);
        assertThat(data.get("status")).isEqualTo("success");
        assertThat(data.get("renderHint")).isEqualTo("card");
        assertThat(data.get("textSummary")).isEqualTo("Lead created");
        assertThat(((Map<?, ?>) data.get("data")).get("crm_lead_company")).isEqualTo("TestCo");
    }

    @Test
    @DisplayName("dsl_command failure emits errorMessage as textSummary")
    void command_failure_with_error_message() throws Exception {
        emitter.emitCommandResult("cmd_bad", commandTool("cmd_bad"), null, 5, false,
                "Validation: field required");

        Map<String, Object> data = mapper.readValue(sse.events.get(0).data, Map.class);
        assertThat(data.get("status")).isEqualTo("failed");
        assertThat(data.get("renderHint")).isEqualTo("summary");
        assertThat(data.get("textSummary")).isEqualTo("Validation: field required");
    }

    @Test
    @DisplayName("no emitter in context → silent no-op (never throws)")
    void no_emitter_is_silent() {
        ChatSseContext.clear();
        // Should not throw.
        emitter.emitQueryResult("x", queryTool("x"), "{}", 1, true);
        emitter.emitCommandResult("y", commandTool("y"), null, 1, true, null);
        assertThat(sse.events).isEmpty();
    }

    @Test
    @DisplayName("actionability derived from BifContext when BIF is set")
    void actionability_from_bif() throws Exception {
        BifContext.setCurrentBif(BusinessIntentFrame.builder()
                .intent("delete").object("crm_lead").riskLevel("L3")
                .actionability("execute")
                .confidence(ConfidenceScore.of(0.9, 0.9))
                .build());

        emitter.emitCommandResult("cmd_x", commandTool("cmd_x"),
                mapper.writeValueAsString(Map.of("success", true, "data", Map.of(), "message", "ok")),
                1, true, null);

        Map<String, Object> data = mapper.readValue(sse.events.get(0).data, Map.class);
        assertThat(data.get("actionability")).isEqualTo("execute");
    }

    @Test
    @DisplayName("no BIF in context → actionability defaults to read_only")
    void actionability_default_read_only() throws Exception {
        BifContext.clear();
        emitter.emitQueryResult("nq_x", queryTool("nq_x"),
                mapper.writeValueAsString(Map.of("total", 0, "returned", 0, "records", List.of())),
                1, true);

        Map<String, Object> data = mapper.readValue(sse.events.get(0).data, Map.class);
        assertThat(data.get("actionability")).isEqualTo("read_only");
    }

    // ============================================================================
    // Capturing test double
    // ============================================================================

    private static class CapturedEvent {
        final String name;
        final String data;
        CapturedEvent(String name, String data) { this.name = name; this.data = data; }
    }

    private static class CapturingEmitter extends SseEmitter {
        final List<CapturedEvent> events = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            // SseEventBuilder.build() returns an alternating sequence of
            // DataWithMediaType entries: framing strings ("event:name\n",
            // "data:", "\n\n") interleaved with the raw payload object. We
            // walk them and track the most recent `event:` name and the raw
            // payload (String for our JSON emissions).
            String name = "message";
            Object payload = null;
            for (var entry : builder.build()) {
                Object data = entry.getData();
                if (data instanceof String s) {
                    int evIdx = s.indexOf("event:");
                    if (evIdx >= 0) {
                        String tail = s.substring(evIdx + 6);
                        int nl = tail.indexOf('\n');
                        name = (nl >= 0 ? tail.substring(0, nl) : tail).trim();
                    } else if (!s.startsWith("data:") && !s.isBlank()
                            && !s.equals("\n") && !s.equals("\n\n") && !s.equals(":")) {
                        payload = s;
                    }
                } else if (data != null) {
                    payload = data;
                }
            }
            events.add(new CapturedEvent(name, payload == null ? "" : payload.toString()));
        }
    }
}
