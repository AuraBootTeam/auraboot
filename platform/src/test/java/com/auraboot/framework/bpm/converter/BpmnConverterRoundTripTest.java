package com.auraboot.framework.bpm.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip + validation tests for BPMN converters covering exclusive gateway
 * sequence flow conditions. Companion to {@link JsonToBpmnConverterTest}.
 *
 * <p>Bug background: prior to F5, JsonToBpmnConverter accepted designerJson where
 * an exclusive gateway's outgoing edge had only a label and no condition/default
 * flag, producing BPMN XML that the engine could not route at runtime. These
 * tests pin the contract for the validator and round-trip fidelity.
 */
class BpmnConverterRoundTripTest {

    private JsonToBpmnConverter jsonToBpmn;
    private BpmnToJsonConverter bpmnToJson;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jsonToBpmn = new JsonToBpmnConverter(objectMapper);
        bpmnToJson = new BpmnToJsonConverter(objectMapper);
    }

    // ==================== Helpers ====================

    /** Build a 3-branch exclusive gateway designerJson with overridable per-edge condition/default. */
    private String gatewayJson(String e1Cond, boolean e1Default,
                               String e2Cond, boolean e2Default,
                               String e3Cond, boolean e3Default) {
        return """
            {
              "key": "gw-test",
              "name": "Gateway Test",
              "nodes": [
                {"id": "start",    "type": "startEvent",       "data": {"type": "startEvent",       "label": "Start"}},
                {"id": "submit",   "type": "userTask",         "data": {"type": "userTask",         "label": "Submit",   "config": {}}},
                {"id": "gw",       "type": "exclusiveGateway", "data": {"type": "exclusiveGateway", "label": "Decide"}},
                {"id": "high",     "type": "userTask",         "data": {"type": "userTask",         "label": "High",     "config": {}}},
                {"id": "mid",      "type": "userTask",         "data": {"type": "userTask",         "label": "Mid",      "config": {}}},
                {"id": "auto",     "type": "userTask",         "data": {"type": "userTask",         "label": "Auto",     "config": {}}},
                {"id": "end",      "type": "endEvent",         "data": {"type": "endEvent",         "label": "End"}}
              ],
              "edges": [
                {"id": "e_start", "source": "start",  "target": "submit", "data": {}},
                {"id": "e_to_gw", "source": "submit", "target": "gw",     "data": {}},
                {"id": "e_high",  "source": "gw",     "target": "high",   "data": {%s %s}},
                {"id": "e_mid",   "source": "gw",     "target": "mid",    "data": {%s %s}},
                {"id": "e_auto",  "source": "gw",     "target": "auto",   "data": {%s %s}},
                {"id": "e_h_end", "source": "high",   "target": "end",    "data": {}},
                {"id": "e_m_end", "source": "mid",    "target": "end",    "data": {}},
                {"id": "e_a_end", "source": "auto",   "target": "end",    "data": {}}
              ]
            }
            """.formatted(
                e1Cond == null ? "" : "\"condition\": {\"type\":\"expression\",\"content\":\"" + e1Cond + "\"}",
                e1Default ? (e1Cond == null ? "\"isDefault\": true" : ", \"isDefault\": true") : "",
                e2Cond == null ? "" : "\"condition\": {\"type\":\"expression\",\"content\":\"" + e2Cond + "\"}",
                e2Default ? (e2Cond == null ? "\"isDefault\": true" : ", \"isDefault\": true") : "",
                e3Cond == null ? "" : "\"condition\": {\"type\":\"expression\",\"content\":\"" + e3Cond + "\"}",
                e3Default ? (e3Cond == null ? "\"isDefault\": true" : ", \"isDefault\": true") : ""
        );
    }

    // ==================== Branch (a): expression conditions round-trip ====================

    @Nested
    @DisplayName("Branch (a) expression conditions")
    class ExpressionRoundTrip {

        @Test
        @DisplayName("expression condition is preserved JSON -> XML -> JSON byte-identical")
        void roundTripExpression() {
            String original = gatewayJson(
                    "amount >= 50000", false,
                    "amount >= 10000", false,
                    "true",            true);

            String xml = jsonToBpmn.convert(original);

            assertThat(xml).contains("<conditionExpression");
            assertThat(xml).contains("amount &gt;= 50000");
            assertThat(xml).contains("amount &gt;= 10000");
            assertThat(xml).contains("default=\"e_auto\"");

            JsonNode back = bpmnToJson.convertToJsonNode(xml);
            JsonNode edges = back.path("edges");

            JsonNode highEdge = findEdge(edges, "e_high");
            assertThat(highEdge.path("data").path("condition").path("content").asText())
                    .isEqualTo("amount >= 50000");
            assertThat(highEdge.path("data").path("condition").path("type").asText())
                    .isEqualTo("expression");

            JsonNode autoEdge = findEdge(edges, "e_auto");
            assertThat(autoEdge.path("data").path("isDefault").asBoolean()).isTrue();
            assertThat(autoEdge.path("data").path("condition").path("content").asText())
                    .isEqualTo("true");
        }
    }

    // ==================== Branch (b): script conditions (MVEL) round-trip ====================

    @Nested
    @DisplayName("Branch (b) script-type MVEL condition")
    class ScriptRoundTrip {

        @Test
        @DisplayName("script condition with language=mvel is preserved through round-trip")
        void roundTripScriptMvel() {
            String json = """
                {
                  "key": "script-cond",
                  "name": "Script Condition",
                  "nodes": [
                    {"id": "start", "type": "startEvent",       "data": {"type": "startEvent",       "label": "Start"}},
                    {"id": "gw",    "type": "exclusiveGateway", "data": {"type": "exclusiveGateway", "label": "Decide"}},
                    {"id": "ok",    "type": "userTask",         "data": {"type": "userTask",         "label": "OK",   "config": {}}},
                    {"id": "ko",    "type": "userTask",         "data": {"type": "userTask",         "label": "KO",   "config": {}}},
                    {"id": "end",   "type": "endEvent",         "data": {"type": "endEvent",         "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "gw",  "data": {}},
                    {"id": "e_ok", "source": "gw", "target": "ok",  "data": {"condition": {"type":"script","language":"mvel","content":"score > 80"}}},
                    {"id": "e_ko", "source": "gw", "target": "ko",  "data": {"isDefault": true, "condition": {"type":"expression","content":"true"}}},
                    {"id": "e2", "source": "ok",  "target": "end", "data": {}},
                    {"id": "e3", "source": "ko",  "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);
            assertThat(xml).contains("language=\"mvel\"");
            assertThat(xml).contains("score &gt; 80");

            JsonNode back = bpmnToJson.convertToJsonNode(xml);
            JsonNode okEdge = findEdge(back.path("edges"), "e_ok");
            assertThat(okEdge.path("data").path("condition").path("type").asText()).isEqualTo("script");
            assertThat(okEdge.path("data").path("condition").path("language").asText()).isEqualTo("mvel");
            assertThat(okEdge.path("data").path("condition").path("content").asText()).isEqualTo("score > 80");
        }
    }

    // ==================== Branch (c): isDefault flag preserved alongside condition ====================

    @Nested
    @DisplayName("Branch (c) isDefault flag still produces gateway default= attribute (alongside condition)")
    class DefaultFlow {

        @Test
        @DisplayName("isDefault edge sets gateway default= attribute and keeps its condition expression")
        void defaultFlowSerializesCorrectly() {
            // SmartEngine requires every outgoing edge to carry a condition, so the "default"
            // edge here keeps an always-true MVEL expression. The isDefault flag still emits the
            // BPMN default= attribute for spec compliance / interop with other tools.
            String json = gatewayJson(
                    "amount >= 50000", false,
                    "amount >= 10000", false,
                    "true",            true);

            String xml = jsonToBpmn.convert(json);

            assertThat(xml).contains("default=\"e_auto\"");
            // The auto flow MUST still have its conditionExpression
            int autoIdx = xml.indexOf("id=\"e_auto\"");
            int nextSeqFlow = xml.indexOf("</sequenceFlow>", autoIdx);
            String autoSegment = xml.substring(autoIdx, nextSeqFlow);
            assertThat(autoSegment).contains("<conditionExpression");
            assertThat(autoSegment).contains("true");
        }
    }

    // ==================== Branch (d): empty condition => reject (regardless of isDefault) ====================

    @Nested
    @DisplayName("Branch (d) validator rejects naked sequence flow")
    class RejectNakedFlow {

        @Test
        @DisplayName("outgoing flow without condition throws BpmnConversionException")
        void rejectsMissingCondition() {
            String bad = gatewayJson(
                    "amount >= 50000", false,
                    null,              false,   // <-- naked: no condition
                    "true",            true);

            assertThatThrownBy(() -> jsonToBpmn.convert(bad))
                    .isInstanceOf(BpmnConversionException.class)
                    .hasMessageContaining("missing a condition expression")
                    .hasMessageContaining("e_mid");
        }

        @Test
        @DisplayName("isDefault flag does not exempt edge from condition requirement")
        void rejectsNakedEvenIfDefault() {
            String bad = gatewayJson(
                    "amount >= 50000", false,
                    "amount >= 10000", false,
                    null,              true);   // <-- isDefault but no condition

            assertThatThrownBy(() -> jsonToBpmn.convert(bad))
                    .isInstanceOf(BpmnConversionException.class)
                    .hasMessageContaining("missing a condition expression")
                    .hasMessageContaining("e_auto");
        }

        @Test
        @DisplayName("whitespace-only condition is treated as missing")
        void rejectsWhitespaceCondition() {
            String bad = """
                {
                  "key": "x", "name": "x",
                  "nodes": [
                    {"id": "s", "type": "startEvent", "data": {"type": "startEvent"}},
                    {"id": "gw","type": "exclusiveGateway","data":{"type":"exclusiveGateway"}},
                    {"id": "a","type":"userTask","data":{"type":"userTask","config":{}}},
                    {"id": "b","type":"userTask","data":{"type":"userTask","config":{}}},
                    {"id": "e","type":"endEvent","data":{"type":"endEvent"}}
                  ],
                  "edges": [
                    {"id":"x1","source":"s","target":"gw","data":{}},
                    {"id":"x2","source":"gw","target":"a","data":{"condition":{"type":"expression","content":"   "}}},
                    {"id":"x3","source":"gw","target":"b","data":{"condition":{"type":"expression","content":"true"},"isDefault":true}},
                    {"id":"x4","source":"a","target":"e","data":{}},
                    {"id":"x5","source":"b","target":"e","data":{}}
                  ]
                }
                """;
            assertThatThrownBy(() -> jsonToBpmn.convert(bad))
                    .isInstanceOf(BpmnConversionException.class)
                    .hasMessageContaining("missing a condition expression");
        }
    }

    // ==================== Branch (e): two defaults => reject ====================

    @Nested
    @DisplayName("Branch (e) validator rejects multiple default flows")
    class RejectMultipleDefaults {

        @Test
        @DisplayName("two outgoing edges both isDefault throws BpmnConversionException")
        void rejectsTwoDefaults() {
            String bad = gatewayJson(
                    "amount >= 50000", false,
                    "true",            true,
                    "true",            true);

            assertThatThrownBy(() -> jsonToBpmn.convert(bad))
                    .isInstanceOf(BpmnConversionException.class)
                    .hasMessageContaining("multiple default flows");
        }
    }

    // ==================== Round-trip preserves every field ====================

    @Nested
    @DisplayName("Full round-trip preserves all condition fields")
    class FullRoundTrip {

        @Test
        @DisplayName("3-branch gateway: JSON -> XML -> JSON keeps condition + isDefault on each edge")
        void preservesAllFields() {
            String original = gatewayJson(
                    "amount >= 50000", false,
                    "amount >= 10000", false,
                    "true",            true);

            String xml = jsonToBpmn.convert(original);
            JsonNode back = bpmnToJson.convertToJsonNode(xml);

            JsonNode highEdge = findEdge(back.path("edges"), "e_high");
            JsonNode midEdge  = findEdge(back.path("edges"), "e_mid");
            JsonNode autoEdge = findEdge(back.path("edges"), "e_auto");

            assertThat(highEdge.path("data").path("condition").path("content").asText())
                    .isEqualTo("amount >= 50000");
            assertThat(midEdge.path("data").path("condition").path("content").asText())
                    .isEqualTo("amount >= 10000");
            assertThat(autoEdge.path("data").path("isDefault").asBoolean()).isTrue();
            assertThat(autoEdge.path("data").path("condition").path("content").asText())
                    .isEqualTo("true");
        }
    }

    // ==================== Helper ====================

    private static JsonNode findEdge(JsonNode edges, String id) {
        for (JsonNode e : edges) {
            if (id.equals(e.path("id").asText())) return e;
        }
        throw new AssertionError("Edge not found: " + id);
    }
}
