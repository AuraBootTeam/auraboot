package com.auraboot.framework.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.Set;

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
    private String modelCode;      // dynamic model the tool targets (null for non-model tools)
    private String operationKind;  // query, create, update, delete, transition (null when unknown)
    private boolean requiresApproval;
    private boolean requiresConfirmation;
    private String riskLevel;
    private Set<String> requiredPermissions;
    private String confirmationPolicy;
    private String nativeToolConfig; // JSON string, e.g. {"type":"web_search_preview"}
}
