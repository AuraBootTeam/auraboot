package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.SkillInput;
import com.auraboot.framework.agent.dto.SkillResult;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.List;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import static org.mockito.ArgumentMatchers.eq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillEngine")
class SkillEngineTest {

    @Mock private AgentSkillService agentSkillService;
    @Mock private ToolLoopService toolLoopService;
    @Mock private StepLoopService stepLoopService;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private DynamicDataService dynamicDataService;

    private SkillEngine skillEngine;

    @BeforeEach
    void setUp() {
        skillEngine = new SkillEngine(
                agentSkillService,
                toolLoopService,
                stepLoopService,
                new ObjectMapper());
    }

    @Test
    @DisplayName("unknown execution mode fails closed instead of falling back to template")
    void unknownExecutionModeFailsClosedInsteadOfTemplateFallback() {
        when(agentSkillService.loadSkill(1L, "bad-mode")).thenReturn(Map.of(
                "skill_code", "bad-mode",
                "execution_mode", "shell_script",
                "failure_mode", "fail_fast",
                "skill_tools", "[\"tool.one\"]"));

        SkillResult result = skillEngine.execute(
                1L,
                "run-1",
                "bad-mode",
                SkillInput.builder().parameters(Map.of("x", 1)).build(),
                null,
                null,
                null);

        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getErrorMessage())
                .contains("Unsupported skill execution mode")
                .contains("shell_script");
        verify(toolLoopService, never())
                .executeToolCall(anyLong(), anyString(), any(), any(), anyString(), any(), any(), any());
    }
    @Test
    void dispatchUsesResolvedGovernedToolAndPreservesApproval() {
        when(agentSkillService.loadSkill(1L, "dsl.command")).thenReturn(Map.of(
                "skill_code", "dsl.command", "execution_mode", "dsl_dispatch"));
        AgentToolDefinition tool = AgentToolDefinition.builder().name("cmd:crm:create")
                .toolType("dsl_command").sourceCode("crm:create").requiresApproval(true).build();
        when(agentSkillService.resolveToolCodes(1L, List.of("cmd:crm:create"))).thenReturn(List.of(tool));
        when(toolLoopService.executeToolCall(eq(1L), eq("run-1"), any(), any(),
                eq("cmd:crm:create"), eq(Map.of("name", "Alice")), eq(List.of(tool)), any()))
                .thenReturn("{\"success\":false,\"approvalRequired\":true,\"approvalPid\":\"approval-1\"}");
        SkillResult result = skillEngine.execute(1L, "run-1", "dsl.command",
                SkillInput.builder().parameters(Map.of("commandCode", "crm:create", "name", "Alice")).build(),
                null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getData()).containsEntry("approvalPid", "approval-1");
        assertThat(result.getActionCount()).isZero();
        verify(toolLoopService, never()).executeDslCommand(anyString(), any());
    }

    @Test
    void undiscoverableDispatchCannotReachBackend() {
        when(agentSkillService.loadSkill(1L, "dsl.query")).thenReturn(Map.of(
                "skill_code", "dsl.query", "execution_mode", "dsl_dispatch"));
        when(agentSkillService.resolveToolCodes(1L, List.of("get:customer"))).thenReturn(List.of());
        SkillResult result = skillEngine.execute(1L, "run-1", "dsl.query",
                SkillInput.builder().parameters(Map.of("model", "customer", "recordPid", "private")).build(),
                null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        verify(toolLoopService, never()).executeToolCall(anyLong(), anyString(), any(), any(), anyString(), any(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(dynamicDataService);
    }

    @Test
    void schemaRejectsTypeEnumBoundsAndUnexpectedFieldsBeforeDiscovery() {
        Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("count", Map.of("type", "integer", "minimum", 1),
                        "mode", Map.of("enum", List.of("safe"))), "required", List.of("count"));
        when(agentSkillService.loadSkill(1L, "schema")).thenReturn(Map.of("skill_input_schema", schema));
        for (Map<String, Object> params : List.<Map<String, Object>>of(
                Map.of("count", "wrong"), Map.of("count", 0),
                Map.of("count", 1, "mode", "unsafe"), Map.of("count", 1, "extra", true))) {
            SkillResult result = skillEngine.execute(1L, "run-1", "schema",
                    SkillInput.builder().parameters(params).build(), null, null, null);
            assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
            assertThat(result.getErrorMessage()).contains("Input validation failed");
        }
        verify(agentSkillService, never()).resolveSkillTools(anyLong(), anyString());
    }

    @Test
    void templateListQueryRetainsItsSourceAndProducesNoBusinessActions() {
        when(agentSkillService.loadSkill(1L, "query-recipe")).thenReturn(Map.of(
                "execution_mode", "template", "skill_tools", List.of("list:customer")));
        when(agentSkillService.resolveSkillTools(1L, "query-recipe")).thenReturn(List.of(
                AgentToolDefinition.builder().name("list:customer").build()));
        when(toolLoopService.executeToolCall(anyLong(), anyString(), any(), any(), anyString(), any(), any(), any()))
                .thenReturn("{\"success\":true,\"records\":[{\"name\":\"Owned customer\"}],\"total\":1}");
        SkillResult result = skillEngine.execute(1L, "run", "query-recipe",
                SkillInput.builder().parameters(Map.of()).build(), null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);
        assertThat(result.getSourceToolCode()).isEqualTo("list:customer");
        assertThat(result.getOutputType()).isEqualTo("structured_result");
        assertThat(result.getRenderHint()).isEqualTo("table");
        assertThat(result.getActionCount()).isZero();
        assertThat(result.getData()).doesNotContainKey("success");
        assertThat(result.getData().get("records")).isEqualTo(List.of(Map.of("name", "Owned customer")));
    }

    @Test
    void jdbcJsonbSchemasToolsAndStepMappingsPreserveTheExecutionContract() throws Exception {
        org.postgresql.util.PGobject schema = new org.postgresql.util.PGobject();
        schema.setType("jsonb");
        schema.setValue("{\"type\":\"object\",\"properties\":{\"count\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"count\"]}");
        org.postgresql.util.PGobject tools = new org.postgresql.util.PGobject();
        tools.setType("jsonb"); tools.setValue("[\"first\",\"second\"]");
        org.postgresql.util.PGobject mappings = new org.postgresql.util.PGobject();
        mappings.setType("jsonb"); mappings.setValue("{\"1\":{\"copiedCount\":\"$ref:input.count\"}}");
        when(agentSkillService.loadSkill(1L, "jdbc")).thenReturn(Map.of(
                "execution_mode", "sequential", "skill_input_schema", schema,
                "skill_tools", tools, "step_input_mappings", mappings));
        when(agentSkillService.resolveSkillTools(1L, "jdbc")).thenReturn(List.of(
                AgentToolDefinition.builder().name("first").build(),
                AgentToolDefinition.builder().name("second").build()));
        when(toolLoopService.executeToolCall(anyLong(), anyString(), any(), any(), anyString(), any(), any(), any()))
                .thenReturn("{\"success\":true}");
        SkillResult result = skillEngine.execute(1L, "run", "jdbc",
                SkillInput.builder().parameters(Map.of("count", 2)).build(), null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);
        verify(toolLoopService).executeToolCall(eq(1L), eq("run"), any(), any(), eq("second"),
                eq(Map.of("count", 2, "copiedCount", 2)), any(), any());
    }

    @Test
    void pendingSequentialStepStopsBestEffortAndWholeRecipeRetry() {
        when(agentSkillService.loadSkill(1L, "sequence")).thenReturn(Map.of(
                "execution_mode", "sequential", "failure_mode", "retry_then_fail", "max_retry", 3,
                "skill_tools", List.of("first", "second")));
        AgentToolDefinition first = AgentToolDefinition.builder().name("first").build();
        AgentToolDefinition second = AgentToolDefinition.builder().name("second").build();
        when(agentSkillService.resolveSkillTools(1L, "sequence")).thenReturn(List.of(first, second));
        when(toolLoopService.executeToolCall(eq(1L), eq("run"), any(), any(), eq("first"), any(), any(), any()))
                .thenReturn("{\"success\":false,\"approvalRequired\":true,\"approvalPid\":\"pending\"}");
        SkillResult result = skillEngine.execute(1L, "run", "sequence",
                SkillInput.builder().parameters(Map.of()).build(), null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getActionCount()).isZero();
        verify(toolLoopService, org.mockito.Mockito.times(1))
                .executeToolCall(eq(1L), eq("run"), any(), any(), eq("first"), any(), any(), any());
        verify(toolLoopService, never()).executeToolCall(anyLong(), anyString(), any(), any(), eq("second"), any(), any(), any());
    }

    @Test
    void structuredNonretryableDenialIsNotReplayed() {
        when(agentSkillService.loadSkill(1L, "dsl.command")).thenReturn(Map.of(
                "skill_code", "dsl.command", "execution_mode", "dsl_dispatch",
                "failure_mode", "retry_then_fail", "max_retry", 3));
        AgentToolDefinition tool = AgentToolDefinition.builder().name("cmd:write").build();
        when(agentSkillService.resolveToolCodes(1L, List.of("cmd:write"))).thenReturn(List.of(tool));
        when(toolLoopService.executeToolCall(eq(1L), eq("run"), any(), any(), eq("cmd:write"), any(), any(), any()))
                .thenReturn("{\"success\":false,\"retryable\":false,\"errorCode\":\"TOOL_ACCESS_DENIED\"}");
        SkillResult result = skillEngine.execute(1L, "run", "dsl.command",
                SkillInput.builder().parameters(Map.of("commandCode", "write")).build(), null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getData()).containsEntry("errorCode", "TOOL_ACCESS_DENIED");
        verify(toolLoopService, org.mockito.Mockito.times(1))
                .executeToolCall(eq(1L), eq("run"), any(), any(), eq("cmd:write"), any(), any(), any());
    }

    @Test
    void orchestrationPreservesApprovalSuspensionWithoutRetry() throws Exception {
        when(agentSkillService.loadSkill(1L, "orchestrate")).thenReturn(Map.of(
                "skill_code", "orchestrate", "execution_mode", "orchestration",
                "failure_mode", "retry_then_fail", "max_retry", 3, "skill_tools", List.of("write")));
        AgentToolDefinition tool = AgentToolDefinition.builder().name("write").build();
        when(agentSkillService.resolveSkillTools(1L, "orchestrate")).thenReturn(List.of(tool));
        when(stepLoopService.executeAgentLoop(anyLong(), anyString(), any(), any(), anyString(),
                anyString(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new AgentApprovalPendingException("approval-1", "Pending review"));
        SkillResult result = skillEngine.execute(1L, "run", "orchestrate",
                SkillInput.builder().parameters(Map.of()).build(), null,
                org.mockito.Mockito.mock(com.auraboot.framework.agent.provider.LlmProvider.class),
                com.auraboot.framework.agent.provider.LlmProviderFactory.ProviderConfig.builder().build());
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getData()).containsEntry("approvalRequired", true).containsEntry("approvalPid", "approval-1");
        verify(stepLoopService, org.mockito.Mockito.times(1)).executeAgentLoop(anyLong(), anyString(), any(), any(), anyString(),
                anyString(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void failedOrchestrationCannotRestartPossiblyCommittedTools() throws Exception {
        when(agentSkillService.loadSkill(1L, "orchestrate")).thenReturn(Map.of(
                "skill_code", "orchestrate", "execution_mode", "orchestration",
                "failure_mode", "retry_then_fail", "max_retry", 3, "skill_tools", List.of("write")));
        when(agentSkillService.resolveSkillTools(1L, "orchestrate"))
                .thenReturn(List.of(AgentToolDefinition.builder().name("write").build()));
        when(stepLoopService.executeAgentLoop(anyLong(), anyString(), any(), any(), anyString(),
                anyString(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new IllegalStateException("Failure after a potentially committed write"));
        SkillResult result = skillEngine.execute(1L, "run", "orchestrate",
                SkillInput.builder().parameters(Map.of()).build(), null,
                org.mockito.Mockito.mock(com.auraboot.framework.agent.provider.LlmProvider.class),
                com.auraboot.framework.agent.provider.LlmProviderFactory.ProviderConfig.builder().build());
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
        assertThat(result.getData()).containsEntry("retryable", false);
        verify(stepLoopService, org.mockito.Mockito.times(1)).executeAgentLoop(anyLong(), anyString(), any(), any(), anyString(),
                anyString(), any(), any(), any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void externalSchemaReferenceCannotReachEvenALocalHttpCanary() throws Exception {
        java.util.concurrent.atomic.AtomicInteger requests = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer canary = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        canary.createContext("/schema", exchange -> {
            requests.incrementAndGet();
            byte[] body = "{\"type\":\"object\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        canary.start();
        try {
            when(agentSkillService.loadSkill(1L, "external-schema")).thenReturn(Map.of(
                    "skill_input_schema", Map.of("$ref", "http://127.0.0.1:" + canary.getAddress().getPort() + "/schema")));
            SkillResult result = skillEngine.execute(1L, "run", "external-schema",
                    SkillInput.builder().parameters(Map.of()).build(), null, null, null);
            assertThat(result.getStatus()).isEqualTo(SkillResult.Status.FAILED);
            assertThat(result.getErrorMessage()).contains("schema");
            assertThat(requests.get()).isZero();
            verify(agentSkillService, never()).resolveSkillTools(anyLong(), anyString());
        } finally {
            canary.stop(0);
        }
    }

    @Test
    void inlineSchemaDefinitionsStillValidateWithoutExternalLoading() {
        when(agentSkillService.loadSkill(1L, "inline-schema")).thenReturn(Map.of(
                "execution_mode", "template", "skill_tools", List.of("read"),
                "skill_input_schema", Map.of("type", "object", "$defs", Map.of("count", Map.of("type", "integer")),
                        "properties", Map.of("count", Map.of("$ref", "#/$defs/count")))));
        AgentToolDefinition tool = AgentToolDefinition.builder().name("read").build();
        when(agentSkillService.resolveSkillTools(1L, "inline-schema")).thenReturn(List.of(tool));
        when(toolLoopService.executeToolCall(anyLong(), anyString(), any(), any(), anyString(), any(), any(), any()))
                .thenReturn("{\"success\":true}");
        SkillResult result = skillEngine.execute(1L, "run", "inline-schema",
                SkillInput.builder().parameters(Map.of("count", 1)).build(), null, null, null);
        assertThat(result.getStatus()).isEqualTo(SkillResult.Status.SUCCESS);
    }

    @Test
    void orchestrationSeparatesApplicablePolicyCurrentTaskAndRecordFacts() {
        String policy = "Apply {{limit}} only to the configured approval task.";
        String prompt = org.springframework.test.util.ReflectionTestUtils.invokeMethod(skillEngine,
                "buildOrchestrationSystemPrompt", policy, List.of("read"), SkillInput.builder().parameters(Map.of("limit", 42)).build());
        assertThat(prompt).contains("Apply 42 only to the configured approval task.")
                .contains("## Task and Evidence Contract")
                .contains("Do not replace the requested task with an unrelated decision")
                .contains("not missing merely because no separate document was retrieved")
                .contains("Preserve source field names and meanings")
                .contains("does not grant permissions or substitute for approval")
                .doesNotContain("{{limit}}");
        org.mockito.Mockito.verifyNoInteractions(stepLoopService, toolLoopService);
    }

}
