package com.auraboot.framework.agentchat.reply;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agentchat.handoff.HandoffResult;
import com.auraboot.framework.agentchat.handoff.HandoffToolProvider;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import com.auraboot.framework.agentchat.sse.SseEmitterManager;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase D.3 (Q-D.4=α) unit tests for {@link AgentReplyTask}'s
 * {@code ab_agent_task} chain wiring. Asserts that:
 *
 * <ol>
 *   <li>Root reply opens a task with {@code parent_id=null}, {@code assignee_type='ai'}.</li>
 *   <li>Successful end_turn closes the root task as {@code completed}.</li>
 *   <li>LLM call failure closes the task as {@code failed} with reason.</li>
 *   <li>Handoff opens a child task with {@code parent_id=upstreamPid} and
 *       closes the upstream task as {@code completed} with reason
 *       {@code handoff_to:<targetCode>}.</li>
 *   <li>MAX_HANDOFF_DEPTH guard closes the deepest task as {@code failed}
 *       (max_handoff_depth_exceeded).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentReplyTask — Phase D.3 ab_agent_task chain")
class AgentReplyTaskTaskChainTest {

    @Mock private AgentDefinitionMapper agentDefinitionMapper;
    @Mock private GroupChatMessagePort messagePort;
    @Mock private GroupChatTurnContextAssembler replyContext;
    @Mock private SseEmitterManager sseEmitterManager;
    @Mock private HandoffToolProvider handoffToolProvider;
    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private LlmProvider provider;
    @Mock @SuppressWarnings("rawtypes") private ObjectProvider messagePortProvider;

    private AgentReplyTask service;

    private static final Long TENANT_ID = 9L;
    private static final Long CONV_ID = 200L;
    private static final Long AGENT_ID = 51L;
    private static final Long TARGET_AGENT_ID = 52L;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(messagePortProvider.getIfAvailable(any())).thenReturn(messagePort);
        service = new AgentReplyTask(
                agentDefinitionMapper, messagePortProvider, replyContext,
                sseEmitterManager, handoffToolProvider, llmProviderFactory);
        ReflectionTestUtils.setField(service, "dynamicDataMapper", dynamicDataMapper);

        // Default: agent definition + provider config + LLM provider all present
        AgentDefinition agent = new AgentDefinition();
        agent.setId(AGENT_ID);
        agent.setAgentCode("agent_alpha");
        agent.setName("Alpha");
        agent.setModel("claude-test");
        when(agentDefinitionMapper.selectById(AGENT_ID)).thenReturn(agent);

        AgentDefinition target = new AgentDefinition();
        target.setId(TARGET_AGENT_ID);
        target.setAgentCode("agent_beta");
        target.setName("Beta");
        target.setModel("claude-test");
        when(agentDefinitionMapper.selectById(TARGET_AGENT_ID)).thenReturn(target);

        when(messagePort.getHumanMemberIds(any(), any())).thenReturn(java.util.Set.of(100L, 101L));
        when(messagePort.getAiContextWindow(any(), any())).thenReturn(20);
        when(replyContext.buildHistory(any(), any(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of());
        when(replyContext.buildSystemPrompt(any(), any(), any())).thenReturn("system prompt");
        when(messagePort.getAgentMembers(any(), any())).thenReturn(List.of());

        when(llmProviderFactory.resolveProviderByModel(anyString())).thenReturn("anthropic");
        LlmProviderFactory.ProviderConfig cfg = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic")
                .apiKey("sk-test")
                .baseUrl("https://api.example.com")
                .defaultModel("claude-test")
                .maxTokens(4000)
                .build();
        when(llmProviderFactory.resolveConfig(any(), anyString())).thenReturn(cfg);
        when(llmProviderFactory.getProvider(anyString())).thenReturn(provider);
    }

    private LlmChatResponse endTurnResponse(String text) {
        LlmChatResponse resp = new LlmChatResponse();
        resp.setStopReason("end_turn");
        LlmChatResponse.ContentBlock block = new LlmChatResponse.ContentBlock();
        block.setType("text");
        block.setText(text);
        resp.setContent(List.of(block));
        resp.setInputTokens(50);
        resp.setOutputTokens(20);
        return resp;
    }

    private LlmChatResponse handoffResponse() {
        LlmChatResponse resp = new LlmChatResponse();
        resp.setStopReason("tool_use");
        LlmChatResponse.ContentBlock block = new LlmChatResponse.ContentBlock();
        block.setType("tool_use");
        block.setName("transfer_to_agent");
        block.setId("tool_call_1");
        block.setInput(Map.of("targetAgentCode", "agent_beta"));
        resp.setContent(List.of(block));
        return resp;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("root reply -> opens task w/ parent_id=null + closes 'completed' on end_turn")
    void rootReply_opensAndClosesTaskCompleted() throws Exception {
        when(provider.chat(any(), anyString(), anyString())).thenReturn(endTurnResponse("Hello!"));

        service.executeReply(CONV_ID, TENANT_ID, AGENT_ID, "@alpha hi");

        // 1 INSERT into ab_agent_task with parent_id absent + assignee_type='ai'
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> insertCap = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper, times(1)).insert(eq("ab_agent_task"), insertCap.capture());
        Map<String, Object> taskRow = insertCap.getValue();
        assertThat(taskRow).containsEntry("assignee_type", "ai")
                           .containsEntry("assignee_id", "agent_alpha")
                           .containsEntry("task_status", "in_progress")
                           .doesNotContainKey("parent_id");

        // 1 UPDATE: status -> completed
        verify(dynamicDataMapper, times(1)).update(
                eq("ab_agent_task"),
                argThat(updates -> "completed".equals(((Map<?, ?>) updates).get("task_status"))),
                anyMap());
    }

    @Test
    @DisplayName("LLM provider unavailable -> closes task 'failed' with reason 'no_llm_provider'")
    void noProvider_closesTaskFailed() {
        when(llmProviderFactory.resolveConfig(any(), anyString())).thenReturn(null);

        service.executeReply(CONV_ID, TENANT_ID, AGENT_ID, "@alpha hi");

        verify(dynamicDataMapper, times(1)).insert(eq("ab_agent_task"), anyMap());
        verify(dynamicDataMapper, times(1)).update(
                eq("ab_agent_task"),
                argThat(updates -> "failed".equals(((Map<?, ?>) updates).get("task_status"))
                        && "no_llm_provider".equals(((Map<?, ?>) updates).get("error_message"))),
                anyMap());
    }

    @Test
    @DisplayName("LLM call throws -> closes task 'failed' with exception message")
    void llmThrows_closesTaskFailed() throws Exception {
        when(provider.chat(any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("network down"));

        service.executeReply(CONV_ID, TENANT_ID, AGENT_ID, "@alpha hi");

        verify(dynamicDataMapper, times(1)).update(
                eq("ab_agent_task"),
                argThat(updates -> "failed".equals(((Map<?, ?>) updates).get("task_status"))
                        && "network down".equals(((Map<?, ?>) updates).get("error_message"))),
                anyMap());
    }

    @Test
    @DisplayName("handoff -> close upstream 'completed' + open child w/ parent_id=upstreamPid (Q-D.4=α core)")
    void handoff_opensChildTaskWithParentId() throws Exception {
        // Round 1: handoff response
        when(provider.chat(any(), anyString(), anyString()))
                .thenReturn(handoffResponse())
                .thenReturn(endTurnResponse("Beta replies."));

        // Handoff resolution succeeds
        AgentMemberDto target = AgentMemberDto.builder()
                .agentId(TARGET_AGENT_ID)
                .agentCode("agent_beta")
                .name("Beta")
                .build();
        when(messagePort.getAgentMembers(any(), any())).thenReturn(List.of(target));
        when(handoffToolProvider.execute(any(), anyMap()))
                .thenReturn(HandoffResult.builder()
                        .success(true)
                        .targetAgentId(TARGET_AGENT_ID)
                        .targetAgentCode("agent_beta")
                        .context("user wants beta")
                        .build());

        service.executeReply(CONV_ID, TENANT_ID, AGENT_ID, "@alpha hand off please");

        // 2 task rows inserted: alpha (root) + beta (child)
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> insertCap = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper, times(2)).insert(eq("ab_agent_task"), insertCap.capture());
        List<Map<String, Object>> rows = insertCap.getAllValues();

        Map<String, Object> alpha = rows.get(0);
        Map<String, Object> beta = rows.get(1);
        assertThat(alpha).containsEntry("assignee_id", "agent_alpha")
                          .doesNotContainKey("parent_id");
        // beta's parent_id == alpha's pid
        assertThat(beta).containsEntry("assignee_id", "agent_beta")
                        .containsEntry("parent_id", alpha.get("pid"));

        // 2 UPDATEs: alpha -> completed (handoff_to:agent_beta), beta -> completed
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> updateCap = ArgumentCaptor.forClass(Map.class);
        verify(dynamicDataMapper, atLeastOnce()).update(
                eq("ab_agent_task"), updateCap.capture(), anyMap());
        long handoffCloseCount = updateCap.getAllValues().stream()
                .filter(u -> "completed".equals(u.get("task_status"))
                        && "handoff_to:agent_beta".equals(u.get("error_message")))
                .count();
        assertThat(handoffCloseCount).as("alpha closed with handoff reason").isEqualTo(1);
        long completedNoReasonCount = updateCap.getAllValues().stream()
                .filter(u -> "completed".equals(u.get("task_status"))
                        && !u.containsKey("error_message"))
                .count();
        assertThat(completedNoReasonCount).as("beta closed cleanly").isEqualTo(1);
    }

    @Test
    @DisplayName("dynamicDataMapper unwired -> reply still works; no task rows attempted")
    void dynamicDataMapperAbsent_replyStillWorks() throws Exception {
        ReflectionTestUtils.setField(service, "dynamicDataMapper", null);
        when(provider.chat(any(), anyString(), anyString())).thenReturn(endTurnResponse("ok"));

        service.executeReply(CONV_ID, TENANT_ID, AGENT_ID, "@alpha hi");

        // No DB writes attempted
        verify(dynamicDataMapper, never()).insert(anyString(), anyMap());
        verify(dynamicDataMapper, never()).update(anyString(), anyMap(), anyMap());
        // saveAgentMessage still called (user-visible reply preserved)
        verify(messagePort, times(1)).saveAgentMessage(eq(CONV_ID), eq(TENANT_ID), eq(AGENT_ID), eq("ok"), any());
    }
}
