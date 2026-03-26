package com.auraboot.framework.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Represents a tool that an agent can call, in Anthropic tool_use format.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolDefinition {
    private String name;
    private String description;
    private Map<String, Object> inputSchema;

    // Internal routing info (not sent to Claude)
    private String toolType;
    private String sourceCode;
    private boolean requiresApproval;
    private String riskLevel;
    private String nativeToolConfig; // JSON string, e.g. {"type":"web_search_preview"}
}
