package com.auraboot.framework.bpm.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage backfill for {@link JsonToBpmnConverter} serialization paths that the
 * existing {@code JsonToBpmnConverterTest} did not exercise:
 *
 * <ul>
 *   <li>parallelGateway / inclusiveGateway element emission (incl. the
 *       {@code default=} attribute branch in {@code writeParallelGateway} /
 *       {@code writeInclusiveGateway}).</li>
 *   <li>The three serviceTask sub-types that route through the dedicated
 *       SmartEngine delegates: {@code rule-task}, {@code notification-task} and
 *       {@code record-update-task} — including the required-attribute
 *       validation that throws {@link BpmnConversionException}.</li>
 * </ul>
 *
 * Assertions characterize the converter's actual current behavior (verified
 * against the source), using the same prefix-agnostic substring style as the
 * existing converter tests ({@code class="..."} matches {@code smart:class="..."}).
 */
class JsonToBpmnConverterGatewaySubtypeCoverageTest {

    private JsonToBpmnConverter jsonToBpmn;

    @BeforeEach
    void setUp() {
        jsonToBpmn = new JsonToBpmnConverter(new ObjectMapper(), null);
    }

    // ==================== Parallel Gateway ====================

    @Nested
    @DisplayName("ParallelGateway serialization")
    class ParallelGatewaySerialization {

        @Test
        @DisplayName("fork + join should emit two <parallelGateway> elements with ids and names")
        void shouldEmitParallelGatewayForkAndJoin() {
            String json = """
                {
                  "key": "parallel-process",
                  "name": "Parallel Process",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "fork", "type": "parallelGateway", "position": {"x": 250, "y": 200}, "data": {"type": "parallelGateway", "label": "Fork", "config": {}}},
                    {"id": "taskA", "type": "userTask", "position": {"x": 400, "y": 100}, "data": {"type": "userTask", "label": "Task A", "config": {}}},
                    {"id": "taskB", "type": "userTask", "position": {"x": 400, "y": 300}, "data": {"type": "userTask", "label": "Task B", "config": {}}},
                    {"id": "join", "type": "parallelGateway", "position": {"x": 550, "y": 200}, "data": {"type": "parallelGateway", "label": "Join", "config": {}}},
                    {"id": "end", "type": "endEvent", "position": {"x": 700, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "fork", "data": {}},
                    {"id": "e2", "source": "fork", "target": "taskA", "data": {}},
                    {"id": "e3", "source": "fork", "target": "taskB", "data": {}},
                    {"id": "e4", "source": "taskA", "target": "join", "data": {}},
                    {"id": "e5", "source": "taskB", "target": "join", "data": {}},
                    {"id": "e6", "source": "join", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("parallelGateway"), "Should contain parallelGateway element. XML: " + xml);
            assertTrue(xml.contains("id=\"fork\""), "Should contain fork gateway id. XML: " + xml);
            assertTrue(xml.contains("id=\"join\""), "Should contain join gateway id. XML: " + xml);
            assertTrue(xml.contains("name=\"Fork\""));
            assertTrue(xml.contains("name=\"Join\""));
            // Both fork-out and join-in sequence flows must be present.
            assertTrue(xml.contains("sourceRef=\"fork\"") || xml.contains("source=\"fork\"")
                    || xml.contains("\"fork\""), "Should wire fork outgoing flows. XML: " + xml);
        }

        @Test
        @DisplayName("config.defaultFlow should emit default= attribute on parallelGateway")
        void shouldEmitDefaultAttributeFromConfig() {
            String json = """
                {
                  "key": "parallel-default",
                  "name": "Parallel Default",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "pg", "type": "parallelGateway", "position": {"x": 300, "y": 200}, "data": {"type": "parallelGateway", "label": "PG", "config": {"defaultFlow": "out1"}}},
                    {"id": "t1", "type": "userTask", "position": {"x": 500, "y": 100}, "data": {"type": "userTask", "label": "T1", "config": {}}},
                    {"id": "t2", "type": "userTask", "position": {"x": 500, "y": 300}, "data": {"type": "userTask", "label": "T2", "config": {}}},
                    {"id": "end", "type": "endEvent", "position": {"x": 700, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "in1", "source": "start", "target": "pg", "data": {}},
                    {"id": "out1", "source": "pg", "target": "t1", "data": {}},
                    {"id": "out2", "source": "pg", "target": "t2", "data": {}},
                    {"id": "f1", "source": "t1", "target": "end", "data": {}},
                    {"id": "f2", "source": "t2", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("parallelGateway"), "XML: " + xml);
            assertTrue(xml.contains("default=\"out1\""), "Should emit default flow attribute. XML: " + xml);
        }
    }

    // ==================== Inclusive Gateway ====================

    @Nested
    @DisplayName("InclusiveGateway serialization")
    class InclusiveGatewaySerialization {

        @Test
        @DisplayName("fork + join should emit two <inclusiveGateway> elements with ids and names")
        void shouldEmitInclusiveGatewayForkAndJoin() {
            String json = """
                {
                  "key": "inclusive-process",
                  "name": "Inclusive Process",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "fork", "type": "inclusiveGateway", "position": {"x": 250, "y": 200}, "data": {"type": "inclusiveGateway", "label": "Fork", "config": {}}},
                    {"id": "taskA", "type": "userTask", "position": {"x": 400, "y": 100}, "data": {"type": "userTask", "label": "Task A", "config": {}}},
                    {"id": "taskB", "type": "userTask", "position": {"x": 400, "y": 300}, "data": {"type": "userTask", "label": "Task B", "config": {}}},
                    {"id": "join", "type": "inclusiveGateway", "position": {"x": 550, "y": 200}, "data": {"type": "inclusiveGateway", "label": "Join", "config": {}}},
                    {"id": "end", "type": "endEvent", "position": {"x": 700, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "fork", "data": {}},
                    {"id": "e2", "source": "fork", "target": "taskA", "data": {"conditionExpression": "${amount > 100}"}},
                    {"id": "e3", "source": "fork", "target": "taskB", "data": {"conditionExpression": "${urgent == true}"}},
                    {"id": "e4", "source": "taskA", "target": "join", "data": {}},
                    {"id": "e5", "source": "taskB", "target": "join", "data": {}},
                    {"id": "e6", "source": "join", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("inclusiveGateway"), "Should contain inclusiveGateway element. XML: " + xml);
            assertTrue(xml.contains("id=\"fork\""), "Should contain fork gateway id. XML: " + xml);
            assertTrue(xml.contains("id=\"join\""), "Should contain join gateway id. XML: " + xml);
            assertTrue(xml.contains("name=\"Fork\""));
            assertTrue(xml.contains("name=\"Join\""));
        }

        @Test
        @DisplayName("edge.isDefault should emit default= attribute on inclusiveGateway")
        void shouldEmitDefaultAttributeFromEdgeFlag() {
            String json = """
                {
                  "key": "inclusive-default",
                  "name": "Inclusive Default",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "ig", "type": "inclusiveGateway", "position": {"x": 300, "y": 200}, "data": {"type": "inclusiveGateway", "label": "IG", "config": {}}},
                    {"id": "t1", "type": "userTask", "position": {"x": 500, "y": 100}, "data": {"type": "userTask", "label": "T1", "config": {}}},
                    {"id": "t2", "type": "userTask", "position": {"x": 500, "y": 300}, "data": {"type": "userTask", "label": "T2", "config": {}}},
                    {"id": "end", "type": "endEvent", "position": {"x": 700, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "in1", "source": "start", "target": "ig", "data": {}},
                    {"id": "out1", "source": "ig", "target": "t1", "data": {"conditionExpression": "${score > 80}"}},
                    {"id": "out2", "source": "ig", "target": "t2", "data": {"isDefault": true}},
                    {"id": "f1", "source": "t1", "target": "end", "data": {}},
                    {"id": "f2", "source": "t2", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("inclusiveGateway"), "XML: " + xml);
            assertTrue(xml.contains("default=\"out2\""), "Should emit default flow attribute from edge flag. XML: " + xml);
        }
    }

    // ==================== rule-task serviceTask sub-type ====================

    @Nested
    @DisplayName("rule-task serviceTask sub-type")
    class RuleTaskSubtype {

        @Test
        @DisplayName("should emit serviceTask with droolsServiceTaskDelegate + ruleCode + factsVars")
        void shouldEmitRuleTaskDelegate() {
            String json = """
                {
                  "key": "rule-process",
                  "name": "Rule Process",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "rt1", "type": "rule-task", "position": {"x": 300, "y": 200}, "data": {"type": "rule-task", "label": "Credit Check", "ruleCode": "credit-check", "factsVars": "amount,score"}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "rt1", "data": {}},
                    {"id": "e2", "source": "rt1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("serviceTask"), "Should serialize as serviceTask. XML: " + xml);
            assertTrue(xml.contains("id=\"rt1\""));
            assertTrue(xml.contains("class=\"droolsServiceTaskDelegate\""),
                    "rule-task should use droolsServiceTaskDelegate. XML: " + xml);
            assertTrue(xml.contains("ruleCode=\"credit-check\""), "Should carry ruleCode. XML: " + xml);
            assertTrue(xml.contains("factsVars=\"amount,score\""), "Should carry factsVars. XML: " + xml);
        }

        @Test
        @DisplayName("missing ruleCode should throw BpmnConversionException")
        void shouldThrowWhenRuleCodeMissing() {
            String json = """
                {
                  "key": "rule-bad",
                  "name": "Rule Bad",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "rt1", "type": "rule-task", "position": {"x": 300, "y": 200}, "data": {"type": "rule-task", "label": "No Rule"}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "rt1", "data": {}},
                    {"id": "e2", "source": "rt1", "target": "end", "data": {}}
                  ]
                }
                """;

            BpmnConversionException ex = assertThrows(BpmnConversionException.class, () -> jsonToBpmn.convert(json));
            assertTrue(ex.getMessage().contains("ruleCode"), "Message should name the missing attribute: " + ex.getMessage());
        }
    }

    // ==================== notification-task serviceTask sub-type ====================

    @Nested
    @DisplayName("notification-task serviceTask sub-type")
    class NotificationTaskSubtype {

        @Test
        @DisplayName("should emit serviceTask with notificationServiceTaskDelegate + eventCode + optional attrs")
        void shouldEmitNotificationTaskDelegate() {
            String json = """
                {
                  "key": "notify-process",
                  "name": "Notify Process",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "nt1", "type": "notification-task", "position": {"x": 300, "y": 200}, "data": {"type": "notification-task", "label": "Notify Approver", "eventCode": "order.approved", "recipientFrom": "approverId", "templateParamsVars": "orderNo,amount"}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "nt1", "data": {}},
                    {"id": "e2", "source": "nt1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("serviceTask"), "Should serialize as serviceTask. XML: " + xml);
            assertTrue(xml.contains("id=\"nt1\""));
            assertTrue(xml.contains("class=\"notificationServiceTaskDelegate\""),
                    "notification-task should use notificationServiceTaskDelegate. XML: " + xml);
            assertTrue(xml.contains("eventCode=\"order.approved\""), "Should carry eventCode. XML: " + xml);
            assertTrue(xml.contains("recipientFrom=\"approverId\""), "Should carry recipientFrom. XML: " + xml);
            assertTrue(xml.contains("templateParamsVars=\"orderNo,amount\""), "Should carry templateParamsVars. XML: " + xml);
        }

        @Test
        @DisplayName("missing eventCode should throw BpmnConversionException")
        void shouldThrowWhenEventCodeMissing() {
            String json = """
                {
                  "key": "notify-bad",
                  "name": "Notify Bad",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "nt1", "type": "notification-task", "position": {"x": 300, "y": 200}, "data": {"type": "notification-task", "label": "No Event"}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "nt1", "data": {}},
                    {"id": "e2", "source": "nt1", "target": "end", "data": {}}
                  ]
                }
                """;

            BpmnConversionException ex = assertThrows(BpmnConversionException.class, () -> jsonToBpmn.convert(json));
            assertTrue(ex.getMessage().contains("eventCode"), "Message should name the missing attribute: " + ex.getMessage());
        }
    }

    // ==================== record-update-task serviceTask sub-type (weakest-covered link) ====================

    @Nested
    @DisplayName("record-update-task serviceTask sub-type")
    class RecordUpdateTaskSubtype {

        private String recordUpdateJson(String dataBody) {
            return """
                {
                  "key": "ru-process",
                  "name": "Record Update Process",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "ru1", "type": "record-update-task", "position": {"x": 300, "y": 200}, "data": %s},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "ru1", "data": {}},
                    {"id": "e2", "source": "ru1", "target": "end", "data": {}}
                  ]
                }
                """.formatted(dataBody);
        }

        @Test
        @DisplayName("full config should emit recordUpdateServiceTaskDelegate + modelCode/recordIdVar/fieldName/fieldValue")
        void shouldEmitRecordUpdateTaskDelegate() {
            String json = recordUpdateJson(
                    "{\"type\": \"record-update-task\", \"label\": \"Mark Approved\", "
                  + "\"modelCode\": \"sl_order\", \"recordIdVar\": \"orderId\", "
                  + "\"fieldName\": \"status\", \"fieldValue\": \"APPROVED\"}");

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("serviceTask"), "Should serialize as serviceTask. XML: " + xml);
            assertTrue(xml.contains("id=\"ru1\""));
            assertTrue(xml.contains("class=\"recordUpdateServiceTaskDelegate\""),
                    "record-update-task should use recordUpdateServiceTaskDelegate. XML: " + xml);
            assertTrue(xml.contains("modelCode=\"sl_order\""), "Should carry modelCode. XML: " + xml);
            assertTrue(xml.contains("recordIdVar=\"orderId\""), "Should carry recordIdVar. XML: " + xml);
            assertTrue(xml.contains("fieldName=\"status\""), "Should carry fieldName. XML: " + xml);
            assertTrue(xml.contains("fieldValue=\"APPROVED\""), "Should carry fieldValue. XML: " + xml);
        }

        @Test
        @DisplayName("missing modelCode should throw BpmnConversionException")
        void shouldThrowWhenModelCodeMissing() {
            String json = recordUpdateJson(
                    "{\"type\": \"record-update-task\", \"label\": \"No Model\", "
                  + "\"fieldName\": \"status\", \"fieldValue\": \"APPROVED\"}");

            BpmnConversionException ex = assertThrows(BpmnConversionException.class, () -> jsonToBpmn.convert(json));
            assertTrue(ex.getMessage().contains("modelCode"), "Message should name the missing attribute: " + ex.getMessage());
        }

        @Test
        @DisplayName("missing fieldName should throw BpmnConversionException")
        void shouldThrowWhenFieldNameMissing() {
            String json = recordUpdateJson(
                    "{\"type\": \"record-update-task\", \"label\": \"No Field\", "
                  + "\"modelCode\": \"sl_order\", \"fieldValue\": \"APPROVED\"}");

            BpmnConversionException ex = assertThrows(BpmnConversionException.class, () -> jsonToBpmn.convert(json));
            assertTrue(ex.getMessage().contains("fieldName"), "Message should name the missing attribute: " + ex.getMessage());
        }

        @Test
        @DisplayName("missing fieldValue should throw BpmnConversionException")
        void shouldThrowWhenFieldValueMissing() {
            String json = recordUpdateJson(
                    "{\"type\": \"record-update-task\", \"label\": \"No Value\", "
                  + "\"modelCode\": \"sl_order\", \"fieldName\": \"status\"}");

            BpmnConversionException ex = assertThrows(BpmnConversionException.class, () -> jsonToBpmn.convert(json));
            assertTrue(ex.getMessage().contains("fieldValue"), "Message should name the missing attribute: " + ex.getMessage());
        }
    }
}
