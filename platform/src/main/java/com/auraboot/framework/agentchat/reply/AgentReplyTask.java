package com.auraboot.framework.agentchat.reply;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.entity.AgentDefinition;
import com.auraboot.framework.agent.mapper.AgentDefinitionMapper;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agentchat.handoff.HandoffResult;
import com.auraboot.framework.agentchat.handoff.HandoffToolProvider;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import com.auraboot.framework.agentchat.spi.GroupChatMessagePort;
import com.auraboot.framework.agentchat.spi.NoOpGroupChatMessagePort;
import com.auraboot.framework.agentchat.sse.SseEmitterManager;
import com.auraboot.framework.agentchat.sse.SseEventType;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Executes agent reply in an async thread.
 * Handles typing indicators, context assembly, LLM invocation, and message saving.
 */
@Slf4j
@Component
public class AgentReplyTask {

    private static final int MAX_HANDOFF_DEPTH = 5;
    private static final int DEFAULT_CONTEXT_WINDOW = 20;

    private final AgentDefinitionMapper agentDefinitionMapper;
    private final GroupChatMessagePort messagePort;
    private final GroupChatTurnContextAssembler replyContext;
    private final SseEmitterManager sseEmitterManager;
    private final HandoffToolProvider handoffToolProvider;
    private final LlmProviderFactory llmProviderFactory;

    /**
     * Phase D.3 (Q-D.4=α): write {@code ab_agent_task} rows for each agent
     * reply (+ child task per handoff hop with {@code parent_id}). Optional
     * dependency so OSS test contexts that don't wire DB plumbing still
     * boot — when null, task-row creation degrades to log-only (handoff
     * recursion still works, just without cross-channel observability).
     */
    @Autowired(required = false)
    private DynamicDataMapper dynamicDataMapper;

    public AgentReplyTask(AgentDefinitionMapper agentDefinitionMapper,
                          ObjectProvider<GroupChatMessagePort> messagePortProvider,
                          GroupChatTurnContextAssembler replyContext,
                          SseEmitterManager sseEmitterManager,
                          HandoffToolProvider handoffToolProvider,
                          LlmProviderFactory llmProviderFactory) {
        this.agentDefinitionMapper = agentDefinitionMapper;
        this.messagePort = messagePortProvider.getIfAvailable(NoOpGroupChatMessagePort::new);
        this.replyContext = replyContext;
        this.sseEmitterManager = sseEmitterManager;
        this.handoffToolProvider = handoffToolProvider;
        this.llmProviderFactory = llmProviderFactory;
    }

    /**
     * Entry point for async agent reply execution. Per Phase D.3 (Q-D.4=α)
     * we open an {@code ab_agent_task} row at the root call so every group-
     * chat agent reply leaves a record cross-channel observable from the
     * ACP / mission view; handoff hops chain via {@code parent_id}.
     */
    @Async
    public void executeReply(Long conversationId, Long tenantId, Long agentId, String triggerContent) {
        String rootTaskPid = openAgentTaskRow(tenantId, agentId, triggerContent, null, 0);
        executeReplyWithDepth(conversationId, tenantId, agentId, triggerContent, 0, rootTaskPid);
    }

    /**
     * Internal method with depth tracking for handoff chains. Phase D.3:
     * {@code currentTaskPid} is the {@code ab_agent_task.pid} for THIS hop;
     * handoff into a target agent opens a child task with
     * {@code parent_id = currentTaskPid} (Q-D.4=α).
     */
    private void executeReplyWithDepth(Long conversationId, Long tenantId, Long agentId,
                                        String triggerContent, int depth, String currentTaskPid) {
        if (depth >= MAX_HANDOFF_DEPTH) {
            log.warn("Max handoff depth {} reached for conversation {}, agent {}",
                    MAX_HANDOFF_DEPTH, conversationId, agentId);
            closeAgentTaskRow(currentTaskPid, "failed", "max_handoff_depth_exceeded");
            return;
        }

        // 1. Load agent definition
        AgentDefinition agent = agentDefinitionMapper.selectById(agentId);
        if (agent == null) {
            log.warn("Agent {} not found, skipping reply for conversation {}", agentId, conversationId);
            closeAgentTaskRow(currentTaskPid, "failed", "agent_not_found");
            return;
        }

        // 2. Get human member IDs for SSE push
        Set<Long> humanMemberIds = messagePort.getHumanMemberIds(conversationId, tenantId);

        // 3. Send TYPING indicator
        sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.TYPING, Map.of(
                "conversationId", conversationId,
                "agentId", agentId,
                "agentName", agent.getName() != null ? agent.getName() : "AI"
        ));

        // 4. Build context
        int contextWindow = messagePort.getAiContextWindow(conversationId, tenantId);
        if (contextWindow <= 0) {
            contextWindow = DEFAULT_CONTEXT_WINDOW;
        }
        List<LlmChatRequest.Message> history = replyContext.buildHistory(conversationId, tenantId, contextWindow);

        // Build agent member DTO for system prompt
        AgentMemberDto agentDto = AgentMemberDto.builder()
                .agentId(agentId)
                .agentCode(agent.getAgentCode())
                .name(agent.getName())
                .employeeId(agent.getEmployeeId())
                .systemPrompt(agent.getSystemPrompt())
                .soulProfile(agent.getSoulProfile() != null ? agent.getSoulProfile().toString() : null)
                .tools(agent.getTools())
                .build();

        String systemPrompt = replyContext.buildSystemPrompt(agentDto, conversationId, tenantId);

        // 5. Build tools (agent's own + handoff if other agents exist)
        List<LlmChatRequest.Tool> tools = buildTools(conversationId, tenantId, agentId);

        // 6. Resolve LLM provider and config
        String providerCode = llmProviderFactory.resolveProviderByModel(agent.getModel());
        LlmProviderFactory.ProviderConfig providerConfig = llmProviderFactory.resolveConfig(tenantId, providerCode);
        if (providerConfig == null) {
            log.error("No LLM provider configured for tenant {}, agent {} (model={})",
                    tenantId, agent.getName(), agent.getModel());
            String errorMsg = "LLM provider not configured. Please configure an AI provider in Settings.";
            messagePort.saveAgentMessage(conversationId, tenantId, agentId, errorMsg, null);
            sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                    "conversationId", conversationId,
                    "agentId", agentId,
                    "error", errorMsg
            ));
            closeAgentTaskRow(currentTaskPid, "failed", "no_llm_provider");
            return;
        }

        String model = agent.getModel() != null ? agent.getModel()
                : providerConfig.getDefaultModel();

        LlmChatRequest request = LlmChatRequest.builder()
                .model(model)
                .providerCode(providerConfig.getProviderCode())
                .systemPrompt(systemPrompt)
                .messages(history)
                .tools(tools.isEmpty() ? null : tools)
                .maxTokens(providerConfig.getMaxTokens() > 0 ? providerConfig.getMaxTokens() : 4096)
                .build();

        // 7. Call LLM and process response
        LlmChatResponse response;
        try {
            LlmProvider provider = llmProviderFactory.getProvider(providerConfig.getProviderCode());
            log.info("Agent {} calling LLM (provider={}, model={}) for conversation {} (depth={}, tools={})",
                    agent.getName(), providerConfig.getProviderCode(), model,
                    conversationId, depth, tools.size());

            response = provider.chat(request, providerConfig.getApiKey(), providerConfig.getBaseUrl());
        } catch (Exception e) {
            // CATCH: non-transactional — LLM HTTP call failure, safe to handle and report to user
            log.error("LLM call failed for agent {} in conversation {}: {}",
                    agent.getName(), conversationId, e.getMessage(), e);
            String errorMsg = "Sorry, I encountered an error while processing your request.";
            messagePort.saveAgentMessage(conversationId, tenantId, agentId, errorMsg, null);
            sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                    "conversationId", conversationId,
                    "agentId", agentId,
                    "error", e.getMessage() != null ? e.getMessage() : "LLM call failed"
            ));
            closeAgentTaskRow(currentTaskPid, "failed",
                    e.getMessage() != null ? e.getMessage() : "llm_call_failed");
            return;
        }

        log.info("Agent {} received LLM response: stopReason={}, contentBlocks={}, tokens={}/{}",
                agent.getName(), response.getStopReason(),
                response.getContent() != null ? response.getContent().size() : 0,
                response.getInputTokens(), response.getOutputTokens());

        // 8. Process response based on stopReason
        if ("tool_use".equals(response.getStopReason())) {
            handleToolUseResponse(response, conversationId, tenantId, agentId, agent, humanMemberIds,
                    depth, currentTaskPid);
        } else {
            // "end_turn" or "max_tokens" — extract text and save
            String replyContent = extractTextContent(response);
            if (replyContent == null || replyContent.isBlank()) {
                replyContent = "[No response generated]";
            }

            // Send content as STREAM_CHUNK for frontend rendering
            sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_CHUNK, Map.of(
                    "conversationId", conversationId,
                    "agentId", agentId,
                    "agentName", agent.getName() != null ? agent.getName() : "AI",
                    "content", replyContent
            ));

            // Save the reply message
            messagePort.saveAgentMessage(conversationId, tenantId, agentId, replyContent, null);

            // Send STREAM_END
            sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                    "conversationId", conversationId,
                    "agentId", agentId,
                    "agentName", agent.getName() != null ? agent.getName() : "AI"
            ));
            closeAgentTaskRow(currentTaskPid, "completed", null);
        }
    }

    /**
     * Handle tool_use response — currently supports transfer_to_agent (handoff).
     * Other tool calls are logged and skipped (future work).
     *
     * <p>Phase D.3 (Q-D.4=α): on a successful handoff we close the current
     * task as {@code completed} (the upstream agent finished its work by
     * delegating) and open a child task with {@code parent_id =
     * currentTaskPid} so the chain is traversable from the ACP / mission
     * view.
     */
    private void handleToolUseResponse(LlmChatResponse response, Long conversationId, Long tenantId,
                                        Long agentId, AgentDefinition agent, Set<Long> humanMemberIds,
                                        int depth, String currentTaskPid) {
        if (response.getContent() == null) return;

        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if (!"tool_use".equals(block.getType())) continue;

            if ("transfer_to_agent".equals(block.getName())) {
                // Build agent lookup map
                List<AgentMemberDto> allAgents = messagePort.getAgentMembers(conversationId, tenantId);
                Map<String, AgentMemberDto> agentByCode = allAgents.stream()
                        .collect(Collectors.toMap(AgentMemberDto::getAgentCode, a -> a, (a1, a2) -> a1));

                HandoffResult result = handoffToolProvider.execute(block.getInput(), agentByCode);

                if (result.isSuccess()) {
                    // Save handoff message from current agent
                    AgentMemberDto targetAgent = agentByCode.get(result.getTargetAgentCode());
                    String targetName = targetAgent != null ? targetAgent.getName() : result.getTargetAgentCode();
                    String handoffMsg = "Handing off to " + targetName + "...";

                    messagePort.saveAgentMessage(conversationId, tenantId, agentId, handoffMsg, null);

                    // Send STREAM_CHUNK + STREAM_END for the handoff message
                    sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_CHUNK, Map.of(
                            "conversationId", conversationId,
                            "agentId", agentId,
                            "agentName", agent.getName() != null ? agent.getName() : "AI",
                            "content", handoffMsg
                    ));
                    sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                            "conversationId", conversationId,
                            "agentId", agentId,
                            "agentName", agent.getName() != null ? agent.getName() : "AI"
                    ));

                    // Phase D.3 Q-D.4=α: current task is "completed by handing
                    // off"; open a child task with parent_id linkage and
                    // recurse with target agent under the new task pid.
                    closeAgentTaskRow(currentTaskPid, "completed",
                            "handoff_to:" + result.getTargetAgentCode());
                    String handoffContext = result.getContext() != null ? result.getContext() : "";
                    String childTaskPid = openAgentTaskRow(tenantId, result.getTargetAgentId(),
                            handoffContext, currentTaskPid, depth + 1);
                    executeReplyWithDepth(conversationId, tenantId, result.getTargetAgentId(),
                            handoffContext, depth + 1, childTaskPid);
                    return;
                } else {
                    log.warn("Handoff failed for agent {} in conversation {}: {}",
                            agent.getName(), conversationId, result.getError());
                    // Let the agent know the handoff failed — save a message and end
                    String errorMsg = "I tried to transfer to another agent but it failed: " + result.getError();
                    messagePort.saveAgentMessage(conversationId, tenantId, agentId, errorMsg, null);
                    sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                            "conversationId", conversationId,
                            "agentId", agentId,
                            "agentName", agent.getName() != null ? agent.getName() : "AI"
                    ));
                    closeAgentTaskRow(currentTaskPid, "failed", "handoff_failed");
                    return;
                }
            } else {
                log.info("Agent {} called unsupported tool '{}' in conversation {} — skipping (future work)",
                        agent.getName(), block.getName(), conversationId);
            }
        }

        // If we get here, no actionable tool calls were found — extract any text content
        String textContent = extractTextContent(response);
        if (textContent != null && !textContent.isBlank()) {
            sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_CHUNK, Map.of(
                    "conversationId", conversationId,
                    "agentId", agentId,
                    "agentName", agent.getName() != null ? agent.getName() : "AI",
                    "content", textContent
            ));
            messagePort.saveAgentMessage(conversationId, tenantId, agentId, textContent, null);
        }

        sseEmitterManager.sendToUsers(humanMemberIds, SseEventType.STREAM_END, Map.of(
                "conversationId", conversationId,
                "agentId", agentId,
                "agentName", agent.getName() != null ? agent.getName() : "AI"
        ));
        closeAgentTaskRow(currentTaskPid, "completed", null);
    }

    /**
     * Extract concatenated text content from LLM response content blocks.
     */
    private String extractTextContent(LlmChatResponse response) {
        if (response.getContent() == null || response.getContent().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (LlmChatResponse.ContentBlock block : response.getContent()) {
            if ("text".equals(block.getType()) && block.getText() != null) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(block.getText());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Build tool list: agent's own tools + handoff tool if other agents exist.
     */
    private List<LlmChatRequest.Tool> buildTools(Long conversationId, Long tenantId, Long currentAgentId) {
        List<LlmChatRequest.Tool> tools = new ArrayList<>();

        // Check for other agents to enable handoff
        List<AgentMemberDto> allAgents = messagePort.getAgentMembers(conversationId, tenantId);
        List<AgentMemberDto> otherAgents = allAgents.stream()
                .filter(a -> !a.getAgentId().equals(currentAgentId))
                .toList();

        if (!otherAgents.isEmpty()) {
            LlmChatRequest.Tool handoffTool = handoffToolProvider.getToolDefinition(otherAgents);
            if (handoffTool != null) {
                tools.add(handoffTool);
            }
        }

        // TODO: Add agent's own tools from agent definition

        return tools;
    }

    // =========================================================================
    // Phase D.3 (Q-D.4=α) — ab_agent_task chain wiring
    // =========================================================================

    /**
     * Open an {@code ab_agent_task} row at the start of an agent reply, with
     * {@code parent_id} pointing at the upstream task pid for handoff hops.
     * Returns the new task pid so the caller threads it through the recursion.
     *
     * <p>Returns {@code null} when {@link #dynamicDataMapper} is unbound
     * (test contexts, OSS partial wiring) — callers handle null gracefully:
     * {@link #closeAgentTaskRow} short-circuits on null.
     */
    private String openAgentTaskRow(Long tenantId, Long agentId, String triggerContent,
                                     String parentTaskPid, int depth) {
        if (dynamicDataMapper == null) {
            return null;
        }
        try {
            AgentDefinition agent = agentDefinitionMapper.selectById(agentId);
            String agentCode = agent != null ? agent.getAgentCode() : ("agent_" + agentId);
            String taskPid = UniqueIdGenerator.generate();
            Map<String, Object> task = new HashMap<>();
            task.put("pid", taskPid);
            task.put("tenant_id", tenantId);
            task.put("title", buildTaskTitle(triggerContent));
            task.put("description", triggerContent != null ? triggerContent : "");
            task.put("task_status", "in_progress");
            task.put("task_priority", "normal");
            task.put("assignee_type", "ai");
            task.put("assignee_id", agentCode);
            if (parentTaskPid != null) {
                task.put("parent_id", parentTaskPid);
            }
            task.put("created_at", LocalDateTime.now());
            task.put("updated_at", LocalDateTime.now());

            // Carry handoff depth in input_data so the ACP / mission view can
            // render the chain without scanning every parent_id.
            task.put("input_data", "{\"handoffDepth\":" + depth + "}");

            dynamicDataMapper.insert("ab_agent_task", task);
            log.debug("Opened ab_agent_task pid={} for agent {} (handoffDepth={}, parent={})",
                    taskPid, agentCode, depth, parentTaskPid);
            return taskPid;
        } catch (Exception e) {
            // CATCH: task-row writes are observability hooks; a DB failure
            // here must not break the user-visible reply flow.
            log.warn("Failed to open ab_agent_task row for agent {}: {}", agentId, e.getMessage());
            return null;
        }
    }

    /**
     * Close an {@code ab_agent_task} row with terminal status. {@code status}
     * is one of {@code completed} / {@code failed}; {@code reason} is optional
     * free-text written into {@code error_message} so triage on cross-channel
     * mission view can see why a hop ended (e.g. {@code handoff_to:foo} for
     * a successful handoff, {@code llm_call_failed} for an error).
     */
    private void closeAgentTaskRow(String taskPid, String status, String reason) {
        if (dynamicDataMapper == null || taskPid == null) {
            return;
        }
        try {
            Map<String, Object> updates = new HashMap<>();
            updates.put("task_status", status);
            updates.put("updated_at", LocalDateTime.now());
            if (reason != null) {
                updates.put("error_message", reason);
            }
            dynamicDataMapper.update("ab_agent_task", updates, Map.of("pid", taskPid));
        } catch (Exception e) {
            log.warn("Failed to close ab_agent_task pid={} status={}: {}",
                    taskPid, status, e.getMessage());
        }
    }

    private static String buildTaskTitle(String triggerContent) {
        if (triggerContent == null) return "Group chat reply";
        String trimmed = triggerContent.trim();
        if (trimmed.isEmpty()) return "Group chat reply";
        return trimmed.length() > 80 ? trimmed.substring(0, 80) + "..." : trimmed;
    }
}
