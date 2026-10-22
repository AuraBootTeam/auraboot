package com.auraboot.framework.agentchat.handoff;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides the Swarm-style "transfer_to_agent" tool for agent handoff.
 * When an agent determines another agent is better suited for a task,
 * it can use this tool to transfer the conversation context.
 */
@Slf4j
@Component
public class HandoffToolProvider {

    private static final String TOOL_NAME = "transfer_to_agent";

    /**
     * Build the tool definition listing available agents for handoff.
     */
    public LlmChatRequest.Tool getToolDefinition(List<AgentMemberDto> availableAgents) {
        if (availableAgents == null || availableAgents.isEmpty()) {
            return null;
        }

        StringBuilder description = new StringBuilder();
        description.append("Transfer the conversation to another AI agent who is better suited for the current task. ");
        description.append("Available agents:\n");
        for (AgentMemberDto agent : availableAgents) {
            description.append("- ").append(agent.getAgentCode()).append(": ").append(agent.getName());
            if (agent.getEmployeeTitle() != null && !agent.getEmployeeTitle().isBlank()) {
                description.append(" (").append(agent.getEmployeeTitle()).append(")");
            }
            description.append("\n");
        }

        // Build enum of agent codes
        List<String> agentCodes = availableAgents.stream()
                .map(AgentMemberDto::getAgentCode)
                .toList();

        // Build input schema
        Map<String, Object> inputSchema = new LinkedHashMap<>();
        inputSchema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> agentCodeProp = new LinkedHashMap<>();
        agentCodeProp.put("type", "string");
        agentCodeProp.put("enum", agentCodes);
        agentCodeProp.put("description", "The code of the agent to transfer to");
        properties.put("agent_code", agentCodeProp);

        Map<String, Object> contextProp = new LinkedHashMap<>();
        contextProp.put("type", "string");
        contextProp.put("description", "Context and instructions to pass to the target agent");
        properties.put("context", contextProp);

        inputSchema.put("properties", properties);
        inputSchema.put("required", List.of("agent_code", "context"));

        return LlmChatRequest.Tool.builder()
                .name(TOOL_NAME)
                .description(description.toString().trim())
                .inputSchema(inputSchema)
                .build();
    }

    /**
     * Execute the handoff: resolve the target agent from the input parameters.
     */
    @SuppressWarnings("unchecked")
    public HandoffResult execute(Map<String, Object> input, Map<String, AgentMemberDto> agentByCode) {
        if (input == null) {
            return HandoffResult.builder()
                    .success(false)
                    .error("No input provided")
                    .build();
        }

        String agentCode = (String) input.get("agent_code");
        String context = (String) input.get("context");

        if (agentCode == null || agentCode.isBlank()) {
            return HandoffResult.builder()
                    .success(false)
                    .error("agent_code is required")
                    .build();
        }

        AgentMemberDto targetAgent = agentByCode.get(agentCode);
        if (targetAgent == null) {
            return HandoffResult.builder()
                    .success(false)
                    .error("Agent not found: " + agentCode)
                    .build();
        }

        log.info("Handoff to agent {} ({}), context: {}", targetAgent.getName(), agentCode, context);

        return HandoffResult.builder()
                .success(true)
                .targetAgentId(targetAgent.getAgentId())
                .targetAgentCode(agentCode)
                .context(context)
                .build();
    }
}
