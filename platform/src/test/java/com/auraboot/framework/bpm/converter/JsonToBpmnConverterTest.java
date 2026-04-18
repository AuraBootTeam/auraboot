package com.auraboot.framework.bpm.converter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JsonToBpmnConverter and BpmnToJsonConverter.
 * Verifies JSON-to-BPMN-XML conversion, BPMN-XML-to-JSON conversion,
 * and round-trip fidelity.
 */
class JsonToBpmnConverterTest {

    private JsonToBpmnConverter jsonToBpmn;
    private BpmnToJsonConverter bpmnToJson;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jsonToBpmn = new JsonToBpmnConverter(objectMapper, null);
        bpmnToJson = new BpmnToJsonConverter(objectMapper);
    }

    // ==================== Simple Linear Process ====================

    @Nested
    @DisplayName("Simple linear process (start -> task -> end)")
    class SimpleLinearProcess {

        @Test
        @DisplayName("should produce valid BPMN XML with correct structure")
        void shouldProduceValidBpmnXml() {
            String json = """
                {
                  "key": "simple-process",
                  "name": "Simple Process",
                  "nodes": [
                    {"id": "node_1", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "node_2", "type": "userTask", "position": {"x": 300, "y": 200}, "data": {"type": "userTask", "label": "Review", "config": {}}},
                    {"id": "node_3", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "edge_1", "source": "node_1", "target": "node_2", "data": {}},
                    {"id": "edge_2", "source": "node_2", "target": "node_3", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertNotNull(xml);
            assertTrue(xml.contains("<?xml"));
            assertTrue(xml.contains("<definitions"));
            assertTrue(xml.contains("xmlns=\"http://www.omg.org/spec/BPMN/20100524/MODEL\""));
            assertTrue(xml.contains("<process id=\"simple-process\""));
            assertTrue(xml.contains("name=\"Simple Process\""));
            assertTrue(xml.contains("isExecutable=\"true\""));
        }

        @Test
        @DisplayName("should contain all node elements")
        void shouldContainAllNodes() {
            String json = """
                {
                  "key": "test",
                  "name": "Test",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "task1", "type": "userTask", "position": {"x": 300, "y": 200}, "data": {"type": "userTask", "label": "Task 1", "config": {}}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "task1", "data": {}},
                    {"id": "e2", "source": "task1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("startEvent"));
            assertTrue(xml.contains("id=\"start\""));
            assertTrue(xml.contains("userTask"));
            assertTrue(xml.contains("id=\"task1\""));
            assertTrue(xml.contains("endEvent"));
            assertTrue(xml.contains("id=\"end\""));
        }

        @Test
        @DisplayName("should contain sequence flows with correct source/target")
        void shouldContainSequenceFlows() {
            String json = """
                {
                  "key": "test",
                  "name": "Test",
                  "nodes": [
                    {"id": "s", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "S"}},
                    {"id": "t", "type": "userTask", "position": {"x": 300, "y": 200}, "data": {"type": "userTask", "label": "T", "config": {}}},
                    {"id": "e", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "E"}}
                  ],
                  "edges": [
                    {"id": "flow1", "source": "s", "target": "t", "data": {}},
                    {"id": "flow2", "source": "t", "target": "e", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("sequenceFlow"));
            assertTrue(xml.contains("id=\"flow1\""));
            assertTrue(xml.contains("sourceRef=\"s\""));
            assertTrue(xml.contains("targetRef=\"t\""));
            assertTrue(xml.contains("id=\"flow2\""));
            assertTrue(xml.contains("sourceRef=\"t\""));
            assertTrue(xml.contains("targetRef=\"e\""));
        }
    }

    // ==================== Exclusive Gateway with Conditions ====================

    @Nested
    @DisplayName("Process with exclusive gateway and conditions")
    class ExclusiveGatewayProcess {

        @Test
        @DisplayName("should render exclusive gateway element")
        void shouldRenderExclusiveGateway() {
            String json = """
                {
                  "key": "gateway-process",
                  "name": "Gateway Process",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "gw", "type": "exclusiveGateway", "position": {"x": 300, "y": 200}, "data": {"type": "exclusiveGateway", "label": "Decision", "config": {}}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "gw", "data": {}},
                    {"id": "e2", "source": "gw", "target": "end", "data": {"condition": {"type": "expression", "content": "true"}}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("exclusiveGateway"));
            assertTrue(xml.contains("id=\"gw\""));
            assertTrue(xml.contains("name=\"Decision\""));
        }

        @Test
        @DisplayName("should render condition expressions on sequence flows")
        void shouldRenderConditionExpressions() {
            String json = """
                {
                  "key": "cond-process",
                  "name": "Conditional Process",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "gw", "type": "exclusiveGateway", "position": {"x": 300, "y": 200}, "data": {"type": "exclusiveGateway", "label": "Check"}},
                    {"id": "task1", "type": "userTask", "position": {"x": 500, "y": 100}, "data": {"type": "userTask", "label": "Approve", "config": {}}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 300}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "gw", "data": {}},
                    {"id": "e2", "source": "gw", "target": "task1", "data": {"label": "Approved", "condition": {"type": "expression", "content": "approved == true"}}},
                    {"id": "e3", "source": "gw", "target": "end", "data": {"label": "Rejected", "condition": {"type": "expression", "content": "approved != true"}}},
                    {"id": "e4", "source": "task1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("<conditionExpression"));
            assertTrue(xml.contains("approved == true"));
            assertTrue(xml.contains("name=\"Approved\""));
        }

        @Test
        @DisplayName("should set default attribute on gateway when defaultFlow is specified in config")
        void shouldSetDefaultFlowFromConfig() {
            String json = """
                {
                  "key": "default-flow",
                  "name": "Default Flow Test",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "gw", "type": "exclusiveGateway", "position": {"x": 300, "y": 200}, "data": {"type": "exclusiveGateway", "label": "GW", "config": {"defaultFlow": "e3"}}},
                    {"id": "task1", "type": "userTask", "position": {"x": 500, "y": 100}, "data": {"type": "userTask", "label": "T1", "config": {}}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 300}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "gw", "data": {}},
                    {"id": "e2", "source": "gw", "target": "task1", "data": {"condition": {"type": "expression", "content": "x > 10"}}},
                    {"id": "e3", "source": "gw", "target": "end", "data": {"isDefault": true, "condition": {"type": "expression", "content": "true"}}},
                    {"id": "e4", "source": "task1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            // The gateway should have default="e3"
            assertTrue(xml.contains("default=\"e3\""), "Gateway should have default attribute. XML: " + xml);
        }

        @Test
        @DisplayName("should set default attribute from edge isDefault flag")
        void shouldSetDefaultFlowFromEdgeFlag() {
            String json = """
                {
                  "key": "default-edge",
                  "name": "Default Edge Test",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "gw", "type": "exclusiveGateway", "position": {"x": 300, "y": 200}, "data": {"type": "exclusiveGateway", "label": "GW", "config": {}}},
                    {"id": "end1", "type": "endEvent", "position": {"x": 500, "y": 100}, "data": {"type": "endEvent", "label": "End1"}},
                    {"id": "end2", "type": "endEvent", "position": {"x": 500, "y": 300}, "data": {"type": "endEvent", "label": "End2"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "gw", "data": {}},
                    {"id": "e2", "source": "gw", "target": "end1", "data": {"condition": {"type": "expression", "content": "x > 0"}}},
                    {"id": "e3", "source": "gw", "target": "end2", "data": {"isDefault": true, "condition": {"type": "expression", "content": "true"}}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("default=\"e3\""), "Gateway should have default attribute from edge isDefault flag. XML: " + xml);
        }
    }

    // ==================== UserTask Assignee Configurations ====================

    @Nested
    @DisplayName("UserTask assignee configurations")
    class UserTaskAssignee {

        @Test
        @DisplayName("should render user type assignee with smart:assigneeType and smart:assigneeId")
        void shouldRenderUserAssignee() {
            String json = """
                {
                  "key": "user-assignee",
                  "name": "User Assignee Test",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "task1", "type": "userTask", "position": {"x": 300, "y": 200}, "data": {"type": "userTask", "label": "Review", "config": {
                      "assignee": {"type": "user", "userIds": ["manager1"]}
                    }}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "task1", "data": {}},
                    {"id": "e2", "source": "task1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("assigneeType=\"user\""), "Should contain assigneeType. XML: " + xml);
            assertTrue(xml.contains("assigneeId=\"manager1\""), "Should contain assigneeId. XML: " + xml);
        }

        @Test
        @DisplayName("should render expression-based assignee with smart:assignee")
        void shouldRenderExpressionAssignee() {
            String json = """
                {
                  "key": "expr-assignee",
                  "name": "Expression Assignee Test",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "task1", "type": "userTask", "position": {"x": 300, "y": 200}, "data": {"type": "userTask", "label": "Review", "config": {
                      "assignee": {"type": "expression", "expression": "${assignee}"}
                    }}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "task1", "data": {}},
                    {"id": "e2", "source": "task1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("assignee=\"${assignee}\""), "Should contain expression assignee. XML: " + xml);
            // Should NOT contain assigneeType for expression-based
            assertFalse(xml.contains("assigneeType"), "Should not contain assigneeType for expression. XML: " + xml);
        }

        @Test
        @DisplayName("should render candidate users and groups")
        void shouldRenderCandidateUsersAndGroups() {
            String json = """
                {
                  "key": "candidates",
                  "name": "Candidates Test",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "task1", "type": "userTask", "position": {"x": 300, "y": 200}, "data": {"type": "userTask", "label": "Review", "config": {
                      "candidateUsers": ["user1", "user2"],
                      "candidateGroups": ["managers", "admins"]
                    }}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "task1", "data": {}},
                    {"id": "e2", "source": "task1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("candidateUsers=\"user1,user2\""), "Should contain candidateUsers. XML: " + xml);
            assertTrue(xml.contains("candidateGroups=\"managers,admins\""), "Should contain candidateGroups. XML: " + xml);
        }

        @Test
        @DisplayName("should render role-based assignee")
        void shouldRenderRoleAssignee() {
            String json = """
                {
                  "key": "role-assignee",
                  "name": "Role Assignee Test",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "task1", "type": "userTask", "position": {"x": 300, "y": 200}, "data": {"type": "userTask", "label": "Review", "config": {
                      "assignee": {"type": "role", "roleIds": ["mgr_role"]}
                    }}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "task1", "data": {}},
                    {"id": "e2", "source": "task1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("assigneeType=\"role\""), "Should contain assigneeType=role. XML: " + xml);
            assertTrue(xml.contains("assigneeId=\"mgr_role\""), "Should contain assigneeId. XML: " + xml);
        }
    }

    // ==================== ServiceTask Configurations ====================

    @Nested
    @DisplayName("ServiceTask configurations")
    class ServiceTaskConfig {

        @Test
        @DisplayName("should render Java service task with smart:class")
        void shouldRenderJavaServiceTask() {
            String json = """
                {
                  "key": "svc-process",
                  "name": "Service Process",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "svc1", "type": "serviceTask", "position": {"x": 300, "y": 200}, "data": {"type": "serviceTask", "label": "Notify", "config": {
                      "serviceType": "java",
                      "className": "com.example.NotifyService"
                    }}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "svc1", "data": {}},
                    {"id": "e2", "source": "svc1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("serviceTask"), "Should contain serviceTask element. XML: " + xml);
            assertTrue(xml.contains("id=\"svc1\""));
            assertTrue(xml.contains("class=\"com.example.NotifyService\""),
                    "Should contain smart:class attribute. XML: " + xml);
        }

        @Test
        @DisplayName("should render async service task")
        void shouldRenderAsyncServiceTask() {
            String json = """
                {
                  "key": "async-svc",
                  "name": "Async Service",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "svc1", "type": "serviceTask", "position": {"x": 300, "y": 200}, "data": {"type": "serviceTask", "label": "Async Task", "config": {
                      "serviceType": "java",
                      "className": "com.example.AsyncService",
                      "async": true
                    }}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "svc1", "data": {}},
                    {"id": "e2", "source": "svc1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("async=\"true\""), "Should contain smart:async attribute. XML: " + xml);
        }

        @Test
        @DisplayName("should render COMMAND service task with commandServiceTaskDelegate class")
        void shouldRenderCommandServiceTask() {
            String json = """
                {
                  "key": "chain-process",
                  "name": "Chain Process",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "cmd1", "type": "serviceTask", "position": {"x": 300, "y": 200}, "data": {"type": "serviceTask", "label": "Create Order", "config": {
                      "serviceType": "command"
                    }}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "cmd1", "data": {}},
                    {"id": "e2", "source": "cmd1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("serviceTask"), "Should contain serviceTask element. XML: " + xml);
            assertTrue(xml.contains("id=\"cmd1\""));
            assertTrue(xml.contains("class=\"commandServiceTaskDelegate\""),
                    "Should use commandServiceTaskDelegate for COMMAND type. XML: " + xml);
        }

        @Test
        @DisplayName("should render service task without className gracefully")
        void shouldHandleServiceTaskWithoutClassName() {
            String json = """
                {
                  "key": "empty-svc",
                  "name": "Empty Service",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "svc1", "type": "serviceTask", "position": {"x": 300, "y": 200}, "data": {"type": "serviceTask", "label": "Empty", "config": {
                      "serviceType": "http"
                    }}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "svc1", "data": {}},
                    {"id": "e2", "source": "svc1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("serviceTask"));
            assertTrue(xml.contains("id=\"svc1\""));
            // Should NOT contain smart:class since no className was specified
            assertFalse(xml.contains("class="), "Should not contain class attribute. XML: " + xml);
        }
    }

    // ==================== ReceiveTask ====================

    @Nested
    @DisplayName("ReceiveTask")
    class ReceiveTaskTests {

        @Test
        @DisplayName("should render receive task element")
        void shouldRenderReceiveTask() {
            String json = """
                {
                  "key": "recv-process",
                  "name": "Receive Process",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "recv1", "type": "receiveTask", "position": {"x": 300, "y": 200}, "data": {"type": "receiveTask", "label": "Wait Signal", "config": {}}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "recv1", "data": {}},
                    {"id": "e2", "source": "recv1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertTrue(xml.contains("receiveTask"), "Should contain receiveTask element. XML: " + xml);
            assertTrue(xml.contains("id=\"recv1\""));
            assertTrue(xml.contains("name=\"Wait Signal\""));
        }
    }

    // ==================== Round-trip Tests ====================

    @Nested
    @DisplayName("Round-trip: JSON -> XML -> JSON preserves key data")
    class RoundTrip {

        @Test
        @DisplayName("should preserve process key and name")
        void shouldPreserveProcessKeyAndName() throws Exception {
            String json = """
                {
                  "key": "round-trip-test",
                  "name": "Round Trip Test",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "end", "data": {}}
                  ]
                }
                """;

            // JSON -> XML -> JSON
            String xml = jsonToBpmn.convert(json);
            String resultJson = bpmnToJson.convert(xml);
            JsonNode result = objectMapper.readTree(resultJson);

            assertEquals("round-trip-test", result.path("key").asText());
            assertEquals("Round Trip Test", result.path("name").asText());
        }

        @Test
        @DisplayName("should preserve node count and types")
        void shouldPreserveNodeCountAndTypes() throws Exception {
            String json = """
                {
                  "key": "node-count-test",
                  "name": "Node Count Test",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "task1", "type": "userTask", "position": {"x": 300, "y": 200}, "data": {"type": "userTask", "label": "Task 1", "config": {"assignee": {"type": "user", "userIds": ["admin"]}}}},
                    {"id": "svc1", "type": "serviceTask", "position": {"x": 500, "y": 200}, "data": {"type": "serviceTask", "label": "Service 1", "config": {"serviceType": "java", "className": "com.example.Svc"}}},
                    {"id": "end", "type": "endEvent", "position": {"x": 700, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "task1", "data": {}},
                    {"id": "e2", "source": "task1", "target": "svc1", "data": {}},
                    {"id": "e3", "source": "svc1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);
            String resultJson = bpmnToJson.convert(xml);
            JsonNode result = objectMapper.readTree(resultJson);

            JsonNode nodes = result.path("nodes");
            assertEquals(4, nodes.size(), "Should preserve 4 nodes");

            // Verify node types are preserved
            boolean hasStart = false, hasEnd = false, hasUserTask = false, hasServiceTask = false;
            for (JsonNode node : nodes) {
                String type = node.path("data").path("type").asText();
                switch (type) {
                    case "startEvent" -> hasStart = true;
                    case "endEvent" -> hasEnd = true;
                    case "userTask" -> hasUserTask = true;
                    case "serviceTask" -> hasServiceTask = true;
                }
            }
            assertTrue(hasStart, "Should have startEvent");
            assertTrue(hasEnd, "Should have endEvent");
            assertTrue(hasUserTask, "Should have userTask");
            assertTrue(hasServiceTask, "Should have serviceTask");
        }

        @Test
        @DisplayName("should preserve edge count and connections")
        void shouldPreserveEdgeCountAndConnections() throws Exception {
            String json = """
                {
                  "key": "edge-test",
                  "name": "Edge Test",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "task1", "type": "userTask", "position": {"x": 300, "y": 200}, "data": {"type": "userTask", "label": "Task", "config": {}}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "flow1", "source": "start", "target": "task1", "data": {}},
                    {"id": "flow2", "source": "task1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);
            String resultJson = bpmnToJson.convert(xml);
            JsonNode result = objectMapper.readTree(resultJson);

            JsonNode edges = result.path("edges");
            assertEquals(2, edges.size(), "Should preserve 2 edges");

            // Find flow1 and verify
            boolean foundFlow1 = false;
            for (JsonNode edge : edges) {
                if ("flow1".equals(edge.path("id").asText())) {
                    assertEquals("start", edge.path("source").asText());
                    assertEquals("task1", edge.path("target").asText());
                    foundFlow1 = true;
                }
            }
            assertTrue(foundFlow1, "Should find flow1 edge");
        }

        @Test
        @DisplayName("should preserve condition expressions through round-trip")
        void shouldPreserveConditions() throws Exception {
            String json = """
                {
                  "key": "cond-roundtrip",
                  "name": "Condition Round Trip",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "gw", "type": "exclusiveGateway", "position": {"x": 300, "y": 200}, "data": {"type": "exclusiveGateway", "label": "GW", "config": {}}},
                    {"id": "end1", "type": "endEvent", "position": {"x": 500, "y": 100}, "data": {"type": "endEvent", "label": "End1"}},
                    {"id": "end2", "type": "endEvent", "position": {"x": 500, "y": 300}, "data": {"type": "endEvent", "label": "End2"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "gw", "data": {}},
                    {"id": "e2", "source": "gw", "target": "end1", "data": {"label": "Yes", "condition": {"type": "expression", "content": "approved == true"}}},
                    {"id": "e3", "source": "gw", "target": "end2", "data": {"label": "No", "condition": {"type": "expression", "content": "approved != true"}}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);
            String resultJson = bpmnToJson.convert(xml);
            JsonNode result = objectMapper.readTree(resultJson);

            // Find the edge with condition
            JsonNode edges = result.path("edges");
            boolean foundCondition = false;
            for (JsonNode edge : edges) {
                if ("e2".equals(edge.path("id").asText())) {
                    JsonNode condition = edge.path("data").path("condition");
                    assertFalse(condition.isMissingNode(), "Should have condition");
                    assertEquals("approved == true", condition.path("content").asText());
                    foundCondition = true;
                }
            }
            assertTrue(foundCondition, "Should find edge e2 with condition");
        }

        @Test
        @DisplayName("should preserve userTask assignee through round-trip")
        void shouldPreserveAssignee() throws Exception {
            String json = """
                {
                  "key": "assignee-roundtrip",
                  "name": "Assignee Round Trip",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "task1", "type": "userTask", "position": {"x": 300, "y": 200}, "data": {"type": "userTask", "label": "Review", "config": {
                      "assignee": {"type": "user", "userIds": ["manager1"]}
                    }}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "task1", "data": {}},
                    {"id": "e2", "source": "task1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);
            String resultJson = bpmnToJson.convert(xml);
            JsonNode result = objectMapper.readTree(resultJson);

            // Find userTask node
            JsonNode nodes = result.path("nodes");
            boolean foundAssignee = false;
            for (JsonNode node : nodes) {
                if ("task1".equals(node.path("id").asText())) {
                    JsonNode assignee = node.path("data").path("config").path("assignee");
                    assertFalse(assignee.isMissingNode(), "Should have assignee config");
                    assertEquals("user", assignee.path("type").asText());
                    JsonNode userIds = assignee.path("userIds");
                    assertTrue(userIds.isArray() && userIds.size() == 1);
                    assertEquals("manager1", userIds.get(0).asText());
                    foundAssignee = true;
                }
            }
            assertTrue(foundAssignee, "Should find task1 with assignee");
        }

        @Test
        @DisplayName("should preserve serviceTask className through round-trip")
        void shouldPreserveServiceTaskClass() throws Exception {
            String json = """
                {
                  "key": "svc-roundtrip",
                  "name": "Service Round Trip",
                  "nodes": [
                    {"id": "start", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start"}},
                    {"id": "svc1", "type": "serviceTask", "position": {"x": 300, "y": 200}, "data": {"type": "serviceTask", "label": "Notify", "config": {
                      "serviceType": "java",
                      "className": "com.example.NotifyService"
                    }}},
                    {"id": "end", "type": "endEvent", "position": {"x": 500, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
                  ],
                  "edges": [
                    {"id": "e1", "source": "start", "target": "svc1", "data": {}},
                    {"id": "e2", "source": "svc1", "target": "end", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);
            String resultJson = bpmnToJson.convert(xml);
            JsonNode result = objectMapper.readTree(resultJson);

            // Find serviceTask node
            JsonNode nodes = result.path("nodes");
            boolean foundService = false;
            for (JsonNode node : nodes) {
                if ("svc1".equals(node.path("id").asText())) {
                    JsonNode config = node.path("data").path("config");
                    assertEquals("java", config.path("serviceType").asText());
                    assertEquals("com.example.NotifyService", config.path("className").asText());
                    foundService = true;
                }
            }
            assertTrue(foundService, "Should find svc1 with className");
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle empty nodes array")
        void shouldHandleEmptyNodes() {
            String json = """
                {
                  "key": "empty",
                  "name": "Empty Process",
                  "nodes": [],
                  "edges": []
                }
                """;

            String xml = jsonToBpmn.convert(json);

            assertNotNull(xml);
            assertTrue(xml.contains("<process"));
            assertTrue(xml.contains("id=\"empty\""));
        }

        @Test
        @DisplayName("should handle missing optional fields gracefully")
        void shouldHandleMissingOptionalFields() {
            String json = """
                {
                  "key": "minimal",
                  "name": "Minimal",
                  "nodes": [
                    {"id": "s", "type": "startEvent", "position": {"x": 0, "y": 0}, "data": {"type": "startEvent", "label": "S"}},
                    {"id": "t", "type": "userTask", "position": {"x": 200, "y": 0}, "data": {"type": "userTask", "label": "T"}},
                    {"id": "e", "type": "endEvent", "position": {"x": 400, "y": 0}, "data": {"type": "endEvent", "label": "E"}}
                  ],
                  "edges": [
                    {"id": "f1", "source": "s", "target": "t", "data": {}},
                    {"id": "f2", "source": "t", "target": "e", "data": {}}
                  ]
                }
                """;

            // Should not throw
            String xml = jsonToBpmn.convert(json);
            assertNotNull(xml);
            assertTrue(xml.contains("userTask"));
        }

        @Test
        @DisplayName("should handle node with no config")
        void shouldHandleNodeWithNoConfig() {
            String json = """
                {
                  "key": "no-config",
                  "name": "No Config",
                  "nodes": [
                    {"id": "s", "type": "startEvent", "position": {"x": 0, "y": 0}, "data": {"type": "startEvent", "label": "S"}},
                    {"id": "t", "type": "serviceTask", "position": {"x": 200, "y": 0}, "data": {"type": "serviceTask", "label": "SvcTask"}},
                    {"id": "e", "type": "endEvent", "position": {"x": 400, "y": 0}, "data": {"type": "endEvent", "label": "E"}}
                  ],
                  "edges": [
                    {"id": "f1", "source": "s", "target": "t", "data": {}},
                    {"id": "f2", "source": "t", "target": "e", "data": {}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);
            assertNotNull(xml);
            assertTrue(xml.contains("serviceTask"));
            assertTrue(xml.contains("id=\"t\""));
        }

        @Test
        @DisplayName("should throw BpmnConversionException for invalid JSON")
        void shouldThrowForInvalidJson() {
            assertThrows(BpmnConversionException.class, () ->
                    jsonToBpmn.convert("not valid json"));
        }

        @Test
        @DisplayName("should use defaults when key/name are missing")
        void shouldUseDefaultsWhenKeyMissing() {
            String json = """
                {
                  "nodes": [],
                  "edges": []
                }
                """;

            String xml = jsonToBpmn.convert(json);
            assertNotNull(xml);
            assertTrue(xml.contains("id=\"process_1\""), "Should use default process id. XML: " + xml);
        }

        @Test
        @DisplayName("should handle edge with label but no condition")
        void shouldHandleEdgeWithLabelOnly() {
            String json = """
                {
                  "key": "label-edge",
                  "name": "Label Edge",
                  "nodes": [
                    {"id": "s", "type": "startEvent", "position": {"x": 0, "y": 0}, "data": {"type": "startEvent", "label": "S"}},
                    {"id": "e", "type": "endEvent", "position": {"x": 200, "y": 0}, "data": {"type": "endEvent", "label": "E"}}
                  ],
                  "edges": [
                    {"id": "f1", "source": "s", "target": "e", "data": {"label": "Go"}}
                  ]
                }
                """;

            String xml = jsonToBpmn.convert(json);
            assertTrue(xml.contains("name=\"Go\""), "Should contain edge label as name. XML: " + xml);
            // Should NOT contain conditionExpression
            assertFalse(xml.contains("conditionExpression"), "Should not contain condition. XML: " + xml);
        }
    }

    // ==================== BpmnToJsonConverter Specific Tests ====================

    @Nested
    @DisplayName("BpmnToJsonConverter specific tests")
    class BpmnToJsonTests {

        @Test
        @DisplayName("should parse SmartEngine simple-approval.bpmn20.xml format")
        void shouldParseSmartEngineFormat() {
            String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:smart="http://smart.alibaba.com"
                             targetNamespace="http://auraboot.com/bpm">
                  <process id="simple-approval" name="Simple Approval" isExecutable="true">
                    <startEvent id="start" name="Start"/>
                    <sequenceFlow id="flow1" sourceRef="start" targetRef="task1"/>
                    <userTask id="task1" name="Approval Task"
                              smart:assigneeType="user"
                              smart:assigneeId="approver1"/>
                    <sequenceFlow id="flow2" sourceRef="task1" targetRef="end"/>
                    <endEvent id="end" name="End"/>
                  </process>
                </definitions>
                """;

            String json = bpmnToJson.convert(bpmn);
            assertNotNull(json);

            JsonNode result;
            try {
                result = objectMapper.readTree(json);
            } catch (Exception e) {
                fail("Should produce valid JSON: " + e.getMessage());
                return;
            }

            assertEquals("simple-approval", result.path("key").asText());
            assertEquals("Simple Approval", result.path("name").asText());
            assertEquals(3, result.path("nodes").size());
            assertEquals(2, result.path("edges").size());

            // Verify userTask assignee was parsed
            for (JsonNode node : result.path("nodes")) {
                if ("task1".equals(node.path("id").asText())) {
                    JsonNode assignee = node.path("data").path("config").path("assignee");
                    assertEquals("user", assignee.path("type").asText());
                    assertEquals("approver1", assignee.path("userIds").get(0).asText());
                }
            }
        }

        @Test
        @DisplayName("should parse BPMN with smartengine.org namespace")
        void shouldParseSmartEngineOrgNamespace() {
            String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:smart="http://smartengine.org/schema/process"
                             targetNamespace="smart">
                  <process id="test-process" isExecutable="true">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="svc"/>
                    <serviceTask id="svc" name="Service" smart:class="com.example.MyService"/>
                    <sequenceFlow id="f2" sourceRef="svc" targetRef="end"/>
                    <endEvent id="end"/>
                  </process>
                </definitions>
                """;

            String json = bpmnToJson.convert(bpmn);
            JsonNode result;
            try {
                result = objectMapper.readTree(json);
            } catch (Exception e) {
                fail("Should produce valid JSON: " + e.getMessage());
                return;
            }

            // Find serviceTask
            for (JsonNode node : result.path("nodes")) {
                if ("svc".equals(node.path("id").asText())) {
                    JsonNode config = node.path("data").path("config");
                    assertEquals("java", config.path("serviceType").asText());
                    assertEquals("com.example.MyService", config.path("className").asText());
                }
            }
        }

        @Test
        @DisplayName("should parse BPMN with condition expressions")
        void shouldParseConditions() throws Exception {
            String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             targetNamespace="test">
                  <process id="cond-test">
                    <startEvent id="start"/>
                    <sequenceFlow id="f1" sourceRef="start" targetRef="gw"/>
                    <exclusiveGateway id="gw" name="Decision" default="f3"/>
                    <sequenceFlow id="f2" sourceRef="gw" targetRef="end1">
                      <conditionExpression xsi:type="mvel">amount > 1000</conditionExpression>
                    </sequenceFlow>
                    <sequenceFlow id="f3" sourceRef="gw" targetRef="end2"/>
                    <endEvent id="end1"/>
                    <endEvent id="end2"/>
                  </process>
                </definitions>
                """;

            String json = bpmnToJson.convert(bpmn);
            JsonNode result = objectMapper.readTree(json);

            // Verify condition on f2
            for (JsonNode edge : result.path("edges")) {
                if ("f2".equals(edge.path("id").asText())) {
                    JsonNode condition = edge.path("data").path("condition");
                    assertEquals("amount > 1000", condition.path("content").asText());
                }
                // Verify f3 is marked as default
                if ("f3".equals(edge.path("id").asText())) {
                    assertTrue(edge.path("data").path("isDefault").asBoolean(false),
                            "f3 should be marked as default");
                }
            }
        }

        @Test
        @DisplayName("should throw BpmnConversionException for invalid XML")
        void shouldThrowForInvalidXml() {
            assertThrows(BpmnConversionException.class, () ->
                    bpmnToJson.convert("not valid xml at all"));
        }

        @Test
        @DisplayName("should throw BpmnConversionException for XML without process element")
        void shouldThrowForMissingProcess() {
            String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL">
                </definitions>
                """;

            assertThrows(BpmnConversionException.class, () ->
                    bpmnToJson.convert(xml));
        }
    }

    // ==================== Full Scenario Test ====================

    @Test
    @DisplayName("should handle the complete leave-approval example from the spec")
    void shouldHandleCompleteLeaveApprovalExample() throws Exception {
        String json = """
            {
              "key": "leave-approval",
              "name": "Leave Approval Process",
              "nodes": [
                {"id": "node_1", "type": "startEvent", "position": {"x": 100, "y": 200}, "data": {"type": "startEvent", "label": "Start", "config": {}}},
                {"id": "node_2", "type": "userTask", "position": {"x": 300, "y": 200}, "data": {"type": "userTask", "label": "Manager Approval", "config": {
                  "description": "Manager reviews leave request",
                  "assignee": {"type": "user", "userIds": ["manager1"], "assigneeMode": "single"},
                  "candidateUsers": [],
                  "candidateGroups": ["managers"],
                  "priority": 50,
                  "skipable": false
                }}},
                {"id": "node_3", "type": "exclusiveGateway", "position": {"x": 500, "y": 200}, "data": {"type": "exclusiveGateway", "label": "Decision", "config": {"defaultFlow": "edge_4"}}},
                {"id": "node_4", "type": "serviceTask", "position": {"x": 700, "y": 100}, "data": {"type": "serviceTask", "label": "Send Notification", "config": {
                  "serviceType": "java",
                  "className": "com.example.NotifyService",
                  "async": false
                }}},
                {"id": "node_5", "type": "endEvent", "position": {"x": 900, "y": 200}, "data": {"type": "endEvent", "label": "End"}}
              ],
              "edges": [
                {"id": "edge_1", "source": "node_1", "target": "node_2", "data": {}},
                {"id": "edge_2", "source": "node_2", "target": "node_3", "data": {}},
                {"id": "edge_3", "source": "node_3", "target": "node_4", "data": {"label": "Approved", "condition": {"type": "expression", "content": "approved == true"}}},
                {"id": "edge_4", "source": "node_3", "target": "node_5", "data": {"label": "Rejected", "isDefault": true, "condition": {"type": "expression", "content": "true"}}},
                {"id": "edge_5", "source": "node_4", "target": "node_5", "data": {}}
              ]
            }
            """;

        // Convert to BPMN XML
        String xml = jsonToBpmn.convert(json);

        // Verify XML structure
        assertTrue(xml.contains("id=\"leave-approval\""));
        assertTrue(xml.contains("name=\"Leave Approval Process\""));
        assertTrue(xml.contains("startEvent"));
        assertTrue(xml.contains("userTask"));
        assertTrue(xml.contains("exclusiveGateway"));
        assertTrue(xml.contains("serviceTask"));
        assertTrue(xml.contains("endEvent"));
        assertTrue(xml.contains("assigneeType=\"user\""));
        assertTrue(xml.contains("assigneeId=\"manager1\""));
        assertTrue(xml.contains("candidateGroups=\"managers\""));
        assertTrue(xml.contains("class=\"com.example.NotifyService\""));
        assertTrue(xml.contains("default=\"edge_4\""));
        assertTrue(xml.contains("approved == true"));

        // Round-trip back to JSON
        String resultJson = bpmnToJson.convert(xml);
        JsonNode result = objectMapper.readTree(resultJson);

        assertEquals("leave-approval", result.path("key").asText());
        assertEquals(5, result.path("nodes").size());
        assertEquals(5, result.path("edges").size());
    }

    @Test
    @DisplayName("convertFromMap should work with Map input")
    void shouldConvertFromMap() throws Exception {
        ObjectNode processMap = objectMapper.createObjectNode();
        processMap.put("key", "map-test");
        processMap.put("name", "Map Test");

        ArrayNode nodes = objectMapper.createArrayNode();
        ObjectNode startNode = objectMapper.createObjectNode();
        startNode.put("id", "s");
        startNode.put("type", "startEvent");
        ObjectNode startPos = objectMapper.createObjectNode();
        startPos.put("x", 100);
        startPos.put("y", 200);
        startNode.set("position", startPos);
        ObjectNode startData = objectMapper.createObjectNode();
        startData.put("type", "startEvent");
        startData.put("label", "Start");
        startNode.set("data", startData);
        nodes.add(startNode);

        ObjectNode endNode = objectMapper.createObjectNode();
        endNode.put("id", "e");
        endNode.put("type", "endEvent");
        ObjectNode endPos = objectMapper.createObjectNode();
        endPos.put("x", 300);
        endPos.put("y", 200);
        endNode.set("position", endPos);
        ObjectNode endData = objectMapper.createObjectNode();
        endData.put("type", "endEvent");
        endData.put("label", "End");
        endNode.set("data", endData);
        nodes.add(endNode);

        processMap.set("nodes", nodes);

        ArrayNode edges = objectMapper.createArrayNode();
        ObjectNode edge = objectMapper.createObjectNode();
        edge.put("id", "f1");
        edge.put("source", "s");
        edge.put("target", "e");
        edge.set("data", objectMapper.createObjectNode());
        edges.add(edge);
        processMap.set("edges", edges);

        Map<String, Object> map = objectMapper.convertValue(processMap, new TypeReference<>() {});

        String xml = jsonToBpmn.convertFromMap(map);
        assertNotNull(xml);
        assertTrue(xml.contains("id=\"map-test\""));
        assertTrue(xml.contains("startEvent"));
        assertTrue(xml.contains("endEvent"));
    }

    // ==================== Aura policy extensions (Epic C) ====================

    @Nested
    @DisplayName("AuraBoot policy extensions via <smart:properties>")
    class AuraPolicyExtensions {

        @Test
        @DisplayName("process-level aura.withdrawPolicy/ccPolicy emit <smart:properties> on <process>")
        void processLevelAuraEmitsSmartProperties() {
            String json = """
                {
                  "key": "p1",
                  "name": "P1",
                  "aura": {"withdrawPolicy": "strict", "ccPolicy": "initiator"},
                  "nodes": [
                    {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":"S"}},
                    {"id":"e","type":"endEvent","position":{"x":1,"y":0},"data":{"type":"endEvent","label":"E"}}
                  ],
                  "edges": [
                    {"id":"f1","source":"s","target":"e","data":{}}
                  ]
                }
                """;
            String xml = jsonToBpmn.convert(json);
            // extensionElements block appears inside <process>
            assertTrue(xml.contains("<extensionElements>"),
                    "expected <extensionElements> when aura policies present:\n" + xml);
            assertTrue(xml.contains("<smart:properties"), xml);
            assertTrue(xml.contains("name=\"aura.withdrawPolicy\""));
            assertTrue(xml.contains("value=\"strict\""));
            assertTrue(xml.contains("name=\"aura.ccPolicy\""));
            assertTrue(xml.contains("value=\"initiator\""));
        }

        @Test
        @DisplayName("missing process-level aura block emits no <extensionElements>")
        void missingAuraEmitsNothing() {
            String json = """
                {
                  "key":"p0","name":"P0",
                  "nodes":[
                    {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":"S"}},
                    {"id":"e","type":"endEvent","position":{"x":1,"y":0},"data":{"type":"endEvent","label":"E"}}
                  ],
                  "edges":[{"id":"f1","source":"s","target":"e","data":{}}]
                }
                """;
            String xml = jsonToBpmn.convert(json);
            assertFalse(xml.contains("<extensionElements>"),
                    "did not expect <extensionElements> when aura absent:\n" + xml);
            assertFalse(xml.contains("<smart:properties"), xml);
        }

        @Test
        @DisplayName("userTask aura.requiredPermissions serialized as JSON array in <smart:properties>")
        void userTaskRequiredPermissionsEmitted() {
            String json = """
                {
                  "key":"p2","name":"P2",
                  "nodes":[
                    {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":"S"}},
                    {"id":"t1","type":"userTask","position":{"x":1,"y":0},
                     "data":{"type":"userTask","label":"Approve",
                             "config":{"aura":{"requiredPermissions":["hr.leave.approve","hr.leave.view"]}}}},
                    {"id":"e","type":"endEvent","position":{"x":2,"y":0},"data":{"type":"endEvent","label":"E"}}
                  ],
                  "edges":[
                    {"id":"f1","source":"s","target":"t1","data":{}},
                    {"id":"f2","source":"t1","target":"e","data":{}}
                  ]
                }
                """;
            String xml = jsonToBpmn.convert(json);
            assertTrue(xml.contains("<smart:properties"), xml);
            assertTrue(xml.contains("name=\"aura.requiredPermissions\""));
            // Value is a JSON array string; XML attribute quoting escapes the inner quotes.
            assertTrue(xml.contains("hr.leave.approve"));
            assertTrue(xml.contains("hr.leave.view"));
            // userTask must be a container (not empty element) when carrying children
            assertTrue(xml.contains("</userTask>"), xml);
        }

        @Test
        @DisplayName("userTask aura.ccPolicyOverride emits override key")
        void userTaskCcPolicyOverrideEmitted() {
            String json = """
                {
                  "key":"p3","name":"P3",
                  "nodes":[
                    {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":"S"}},
                    {"id":"t1","type":"userTask","position":{"x":1,"y":0},
                     "data":{"type":"userTask","label":"Approve",
                             "config":{"aura":{"ccPolicyOverride":"assignee"}}}},
                    {"id":"e","type":"endEvent","position":{"x":2,"y":0},"data":{"type":"endEvent","label":"E"}}
                  ],
                  "edges":[
                    {"id":"f1","source":"s","target":"t1","data":{}},
                    {"id":"f2","source":"t1","target":"e","data":{}}
                  ]
                }
                """;
            String xml = jsonToBpmn.convert(json);
            assertTrue(xml.contains("name=\"aura.ccPolicyOverride\""), xml);
            assertTrue(xml.contains("value=\"assignee\""));
        }
    }

    // ==================== GAP-254: Node Hook Compilation ====================

    @Nested
    @DisplayName("Node hook compilation (GAP-254)")
    class NodeHookCompilation {

        private static final String HOOK_USER_TASK_JSON = """
                {
                  "key": "wf_hook_compile",
                  "name": "Hook Compile",
                  "nodes": [
                    {"id":"start","type":"startEvent","position":{"x":80,"y":200},
                      "data":{"type":"startEvent","label":"Start"}},
                    {"id":"approve","type":"userTask","position":{"x":280,"y":200},
                      "data":{"type":"userTask","label":"Approve","config":{
                        "assigneeType":"user","assigneeIds":["admin"],
                        "hooks":[
                          {"hookType":"pre_execute","executionOrder":0,
                           "hookConfig":{"actionType":"command","commandCode":"wd:notify","params":"{}"},
                           "failStrategy":"block","async":false,"enabled":true},
                          {"hookType":"post_execute","executionOrder":1,
                           "hookConfig":{"actionType":"script","script":"#vars[\\"x\\"]=1"},
                           "failStrategy":"ignore","async":true,"enabled":true}
                        ]
                      }}},
                    {"id":"end","type":"endEvent","position":{"x":520,"y":200},
                      "data":{"type":"endEvent","label":"End"}}
                  ],
                  "edges":[
                    {"id":"e1","source":"start","target":"approve","data":{}},
                    {"id":"e2","source":"approve","target":"end","data":{}}
                  ]
                }
                """;

        @Test
        @DisplayName("emits aura.hooks smart:property holding serialized hook list")
        void emitsSmartHookPerEntry() {
            String xml = jsonToBpmn.convert(HOOK_USER_TASK_JSON);
            // Wrapper element appears once on the userTask
            assertTrue(xml.contains("<extensionElements>"), xml);
            // Hooks land under name="aura.hooks" (single property carrying JSON array)
            assertTrue(xml.contains("name=\"aura.hooks\""), xml);
            // The hookType / actionType / failStrategy values appear inside the
            // serialized JSON value of the property — exact XML escaping makes
            // attribute matching brittle, so we assert key fragments instead.
            assertTrue(xml.contains("pre_execute"), xml);
            assertTrue(xml.contains("post_execute"), xml);
            assertTrue(xml.contains("command"), xml);
            assertTrue(xml.contains("script"), xml);
            assertTrue(xml.contains("commandCode"), xml);
            assertTrue(xml.contains("wd:notify"), xml);
        }

        @Test
        @DisplayName("missing hookType throws BpmnConversionException with diagnostic")
        void missingHookTypeFails() {
            String json = """
                    {
                      "key":"k","name":"k",
                      "nodes":[
                        {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":""}},
                        {"id":"t1","type":"userTask","position":{"x":1,"y":1},"data":{"type":"userTask","label":"t1","config":{
                          "hooks":[{"hookConfig":{"actionType":"command"}}]
                        }}},
                        {"id":"e","type":"endEvent","position":{"x":2,"y":2},"data":{"type":"endEvent","label":""}}
                      ],
                      "edges":[
                        {"id":"f1","source":"s","target":"t1","data":{}},
                        {"id":"f2","source":"t1","target":"e","data":{}}
                      ]
                    }
                    """;
            BpmnConversionException ex = assertThrows(BpmnConversionException.class,
                    () -> jsonToBpmn.convert(json));
            assertTrue(ex.getMessage().contains("hookType"), ex.getMessage());
        }

        @Test
        @DisplayName("extractHookEntries returns one entry per (nodeId, hook) pair")
        void extractHookEntriesReturnsFlatList() throws Exception {
            JsonNode root = objectMapper.readTree(HOOK_USER_TASK_JSON);
            var entries = jsonToBpmn.extractHookEntries(root);
            assertEquals(2, entries.size(), "two hooks must surface");
            assertEquals("approve", entries.get(0).nodeId());
            assertEquals("approve", entries.get(1).nodeId());
            assertEquals("pre_execute", entries.get(0).descriptor().hookType());
            assertEquals("command", entries.get(0).descriptor().actionType());
            assertEquals(0, entries.get(0).descriptor().executionOrder());
            assertEquals("block", entries.get(0).descriptor().failStrategy());
            assertEquals(false, entries.get(0).descriptor().async());
            assertEquals("post_execute", entries.get(1).descriptor().hookType());
            assertEquals("script", entries.get(1).descriptor().actionType());
            assertEquals(true, entries.get(1).descriptor().async());
            // hookConfigJson is real JSON object text — assert key presence rather
            // than full string equality to avoid coupling to map iteration order.
            assertTrue(entries.get(0).descriptor().hookConfigJson().contains("commandCode"));
            assertTrue(entries.get(1).descriptor().hookConfigJson().contains("script"));
        }

        @Test
        @DisplayName("nodes without hooks emit no <smart:hook> elements")
        void noHooksEmitsNothing() {
            String json = """
                    {
                      "key":"k","name":"k",
                      "nodes":[
                        {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":""}},
                        {"id":"t1","type":"userTask","position":{"x":1,"y":1},"data":{"type":"userTask","label":"t1","config":{
                          "assigneeType":"user","assigneeIds":["admin"]
                        }}},
                        {"id":"e","type":"endEvent","position":{"x":2,"y":2},"data":{"type":"endEvent","label":""}}
                      ],
                      "edges":[
                        {"id":"f1","source":"s","target":"t1","data":{}},
                        {"id":"f2","source":"t1","target":"e","data":{}}
                      ]
                    }
                    """;
            String xml = jsonToBpmn.convert(json);
            assertFalse(xml.contains("<smart:hook"), "no hook element when designer hooks empty: " + xml);
            assertFalse(xml.contains("hookType="), xml);
        }
    }

    // ==================== CallActivity (GAP-250) ====================
    // SmartEngine's CallActivityParser only reads `calledElement` and
    // `calledElementVersion` attributes; the XML parser facade throws on any
    // unknown child element (EngineException "No parser found for QName").
    // These tests lock in the fix: emit a self-closing <callActivity/> with
    // only the attributes SmartEngine actually parses — NEVER emit
    // <extensionElements> or <smart:in>/<smart:out>, even when the UI config
    // carries inputMappings / outputMappings. See writeCallActivity javadoc.

    @Nested
    @DisplayName("CallActivity — GAP-250 SmartEngine parser compatibility")
    class CallActivityCompat {

        @Test
        @DisplayName("emits calledElement + smart:calledElementVersion attributes only")
        void emitsCalledElementAttributes() {
            String json = """
                {
                  "key":"parent_proc","name":"Parent",
                  "nodes":[
                    {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":""}},
                    {"id":"invoke","type":"callActivity","position":{"x":1,"y":0},"data":{"type":"callActivity","label":"Invoke","config":{
                      "calledProcessKey":"child_proc","calledProcessVersion":"1.0.0"
                    }}},
                    {"id":"e","type":"endEvent","position":{"x":2,"y":0},"data":{"type":"endEvent","label":""}}
                  ],
                  "edges":[
                    {"id":"f1","source":"s","target":"invoke","data":{}},
                    {"id":"f2","source":"invoke","target":"e","data":{}}
                  ]
                }
                """;
            String xml = jsonToBpmn.convert(json);
            assertTrue(xml.contains("<callActivity "), "callActivity element missing: " + xml);
            assertTrue(xml.contains("id=\"invoke\""), xml);
            assertTrue(xml.contains("calledElement=\"child_proc\""), xml);
            assertTrue(xml.contains("calledElementVersion=\"1.0.0\""), xml);
        }

        @Test
        @DisplayName("never emits raw <smart:in>/<smart:out> (SmartEngine parser contract) — mappings live in aura.callMappings smart:property")
        void neverEmitsMappingExtensionElements() {
            // The UI allows users to author inputMappings / outputMappings in
            // the CallActivity property panel. Those values must be carried
            // through to the deployed BPMN so AuraCallActivityListener can
            // bridge SmartEngine's parent/child request-map isolation at
            // runtime. Prior GAP-250 iterations emitted <smart:in>/<smart:out>
            // directly under <callActivity>, which SmartEngine's BPMN parser
            // rejected with "Parse process definition file failure!" because
            // those QNames have no registered parser.
            //
            // The current contract piggybacks on the generic <smart:properties>
            // extension (which IS a registered parser, shared with aura.hooks /
            // aura.formKey) — the mappings ride as a JSON payload under
            // <smart:property name="aura.callMappings" .../>. No raw
            // <smart:in>/<smart:out> are ever emitted.
            String json = """
                {
                  "key":"parent_proc","name":"Parent",
                  "nodes":[
                    {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":""}},
                    {"id":"invoke","type":"callActivity","position":{"x":1,"y":0},"data":{"type":"callActivity","label":"Invoke","config":{
                      "calledProcessKey":"child_proc",
                      "calledProcessVersion":"latest",
                      "inputMappings":{"parentInput":"childInput"},
                      "outputMappings":{"childOutput":"parentOutput"}
                    }}},
                    {"id":"e","type":"endEvent","position":{"x":2,"y":0},"data":{"type":"endEvent","label":""}}
                  ],
                  "edges":[
                    {"id":"f1","source":"s","target":"invoke","data":{}},
                    {"id":"f2","source":"invoke","target":"e","data":{}}
                  ]
                }
                """;
            String xml = jsonToBpmn.convert(json);

            // callActivity element is still emitted with attributes.
            // Version "latest" is resolved to the concrete max-deployed
            // version at convert-time when a SmartEngine bean is available;
            // in this unit test the converter is constructed with
            // {@code smartEngine = null}, so the literal "latest" is
            // stripped rather than emitted (SmartEngine's parser would
            // accept the string but the runtime container has no alias
            // lookup — see resolveLatestVersion javadoc). The net is: the
            // "latest" literal must never appear in the deployed BPMN.
            assertTrue(xml.contains("calledElement=\"child_proc\""), xml);
            assertFalse(xml.contains("calledElementVersion=\"latest\""),
                    "literal 'latest' must not leak into deployed BPMN: " + xml);

            // Raw <smart:in>/<smart:out> are still forbidden — SmartEngine has
            // no parser for them. This invariant is the original GAP-250 fix.
            assertFalse(xml.contains("<smart:in "),
                    "GAP-250: <smart:in> breaks SmartEngine deploy: " + xml);
            assertFalse(xml.contains("<smart:out "),
                    "GAP-250: <smart:out> breaks SmartEngine deploy: " + xml);

            // Extract the <callActivity ...> ... </callActivity> segment to
            // scope further assertions to this one element.
            int caStart = xml.indexOf("<callActivity");
            int caEnd = xml.indexOf("</callActivity>", caStart);
            String caBlock = caEnd > 0 ? xml.substring(caStart, caEnd) : xml.substring(caStart);

            // The mappings payload IS emitted — nested under the generic
            // <smart:properties> extension (registered parser) rather than as
            // free-standing <smart:in>/<smart:out> children.
            assertTrue(caBlock.contains("extensionElements"),
                    "mappings must ride as <extensionElements><smart:properties>: " + caBlock);
            assertTrue(caBlock.contains("aura.callMappings"),
                    "aura.callMappings smart:property missing: " + caBlock);
            assertTrue(caBlock.contains("parentInput"),
                    "inputMappings payload must include parentInput: " + caBlock);
            assertTrue(caBlock.contains("childInput"),
                    "inputMappings payload must include childInput: " + caBlock);
            assertTrue(caBlock.contains("childOutput"),
                    "outputMappings payload must include childOutput: " + caBlock);
            assertTrue(caBlock.contains("parentOutput"),
                    "outputMappings payload must include parentOutput: " + caBlock);
        }

        @Test
        @DisplayName("callActivity without config still serializes (id only)")
        void callActivityWithoutConfig() {
            // Edge case: designer creates a blank callActivity node before
            // the user picks a target process. Converter should still produce
            // well-formed XML (deploy will of course fail until calledElement
            // is set, but the converter itself must not throw).
            String json = """
                {
                  "key":"parent_proc","name":"Parent",
                  "nodes":[
                    {"id":"s","type":"startEvent","position":{"x":0,"y":0},"data":{"type":"startEvent","label":""}},
                    {"id":"invoke","type":"callActivity","position":{"x":1,"y":0},"data":{"type":"callActivity","label":"Invoke"}},
                    {"id":"e","type":"endEvent","position":{"x":2,"y":0},"data":{"type":"endEvent","label":""}}
                  ],
                  "edges":[
                    {"id":"f1","source":"s","target":"invoke","data":{}},
                    {"id":"f2","source":"invoke","target":"e","data":{}}
                  ]
                }
                """;
            String xml = jsonToBpmn.convert(json);
            assertTrue(xml.contains("<callActivity "), xml);
            assertTrue(xml.contains("id=\"invoke\""), xml);
            assertFalse(xml.contains("<smart:in"), xml);
            assertFalse(xml.contains("<smart:out"), xml);
        }
    }
}
