package com.auraboot.framework.agent.provider;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolDefinition {
    private String toolCode;
    private String toolName;
    private String description;
    private String providerCode;   // which provider owns this tool
    private String toolType;       // dsl_command, dsl_query, platform, custom, mcp
    private String sourceCode;     // underlying command/query/platform code
    private String riskLevel;      // L0-L4
    private String confirmationPolicy;
    private boolean requiresApproval;
    private boolean requiresConfirmation;
    private Map<String, Object> parameterSchema; // JSON Schema for parameters
}
