package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Durable snapshot for a suspended chat tool call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingToolSnapshot {

    private String turnId;
    private Long tenantId;
    private Long userId;
    private Long humanMemberId;
    private Long conversationId;
    private String agentCode;
    private String sessionId;
    private String channel;
    private String profileId;
    private String channelSessionPid;

    private String toolId;
    private String toolName;
    private String toolVersion;
    private Map<String, Object> input;
    private String argsHash;
    private String idempotencyKey;
    private Long expiresAt;
    private String contextVersion;
    private String recordVersion;
    private String contextConflictPolicy;
    private String policyDecisionReason;
    private String toolSchemaHash;
    private String preview;
    private String previewHash;
    private String description;
    private String modelCode;
    private String toolSpanId;
    private String runPid;
    private String taskPid;
    private List<AgentToolDefinition> agentToolDefinitions;

    private List<Map<String, Object>> messages;
    private String providerCode;
    private String apiKey;
    private String baseUrl;
    private String model;
    private String systemPrompt;
    private Integer maxTokens;
    private int currentLoop;

    @Builder.Default
    private long createdAt = Instant.now().toEpochMilli();

    private Map<String, Object> extension;
}
