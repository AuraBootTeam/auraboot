package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.runtime.context.AgentContextBlock;
import com.auraboot.framework.agent.runtime.context.AgentContextProvenance;
import com.auraboot.framework.agent.runtime.context.AgentContextSource;
import com.auraboot.framework.conversation.TurnContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.Builder;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds pending-tool snapshots shared by chat-turn adapters.
 */
@Component
public class PendingToolSnapshotFactory {

    private static final String DEFAULT_TOOL_VERSION = "v1";
    private static final long DEFAULT_PENDING_TTL_MILLIS = 30 * 60 * 1000L;
    private static final ObjectMapper HASH_MAPPER = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final AgentRuntimeStateFactory runtimeStateFactory;
    private final PendingContextVersionResolver contextVersionResolver;

    @Autowired
    public PendingToolSnapshotFactory(AgentRuntimeStateFactory runtimeStateFactory,
                                      ObjectProvider<PendingContextVersionResolver> contextVersionResolverProvider) {
        this(runtimeStateFactory,
                contextVersionResolverProvider != null ? contextVersionResolverProvider.getIfAvailable() : null);
    }

    public PendingToolSnapshotFactory(AgentRuntimeStateFactory runtimeStateFactory) {
        this(runtimeStateFactory, (PendingContextVersionResolver) null);
    }

    public PendingToolSnapshotFactory(AgentRuntimeStateFactory runtimeStateFactory,
                                      PendingContextVersionResolver contextVersionResolver) {
        this.runtimeStateFactory = Objects.requireNonNull(runtimeStateFactory, "runtimeStateFactory is required");
        this.contextVersionResolver = contextVersionResolver;
    }

    public PendingToolSnapshot build(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is required");
        }
        TurnContext ctx = snapshot.getCtx();
        List<AgentToolDefinition> toolDefinitions = resolveAgentToolDefinitions(snapshot);
        Map<String, Object> input = safeMap(snapshot.getInput());
        Map<String, Object> extension = buildExtension(snapshot);
        long createdAt = System.currentTimeMillis();
        String toolVersion = firstNonBlank(snapshot.getToolVersion(), DEFAULT_TOOL_VERSION);
        String argsHash = firstNonBlank(snapshot.getArgsHash(), hash(input));
        String idempotencyKey = firstNonBlank(snapshot.getIdempotencyKey(),
                buildIdempotencyKey(snapshot.getToolName(), toolVersion, argsHash));
        Long expiresAt = snapshot.getExpiresAt() != null
                ? snapshot.getExpiresAt()
                : createdAt + DEFAULT_PENDING_TTL_MILLIS;
        String toolSchemaHash = firstNonBlank(snapshot.getToolSchemaHash(),
                hash(resolveToolSchema(snapshot, toolDefinitions)));
        String preview = firstNonBlank(snapshot.getPreview(), snapshot.getDescription());
        String previewHash = firstNonBlank(snapshot.getPreviewHash(), hashText(preview));
        String policyDecisionReason = firstNonBlank(snapshot.getPolicyDecisionReason(),
                inferPolicyDecisionReason(snapshot, toolDefinitions));
        PendingContextVersion contextVersion = resolveContextVersion(ctx, snapshot, input);
        String modelCode = firstNonBlank(snapshot.getModelCode(), contextVersion.modelCode());
        String recordVersion = firstNonBlank(snapshot.getRecordVersion(), contextVersion.recordVersion());
        String pendingContextVersion = firstNonBlank(snapshot.getContextVersion(), contextVersion.contextVersion());
        String contextConflictPolicy = firstNonBlank(snapshot.getContextConflictPolicy(),
                contextVersion.verifiable() ? ContextConflictPolicy.REJECT_AND_REPLAN.name() : null);

        return PendingToolSnapshot.builder()
                .turnId(ctx != null ? ctx.turnId() : null)
                .tenantId(ctx != null ? ctx.tenantId() : null)
                .userId(ctx != null ? ctx.userId() : null)
                .humanMemberId(ctx != null ? ctx.humanMemberId() : null)
                .conversationId(ctx != null ? ctx.conversationId() : null)
                .agentCode(snapshot.getAgentCode())
                .sessionId(snapshot.getSessionId())
                .channel(firstNonBlank(snapshot.getChannel(), ctx != null ? ctx.channel() : null))
                .profileId(firstNonBlank(snapshot.getProfileId(), ctx != null ? ctx.profileId() : null))
                .channelSessionPid(firstNonBlank(snapshot.getChannelSessionPid(),
                        ctx != null ? ctx.channelSessionId() : null))
                .triageBucket(ctx != null && ctx.triageBucket() != null ? ctx.triageBucket().name() : null)
                .toolId(snapshot.getToolId())
                .toolName(snapshot.getToolName())
                .toolVersion(toolVersion)
                .toolSpanId(snapshot.getToolSpanId())
                .input(input)
                .argsHash(argsHash)
                .idempotencyKey(idempotencyKey)
                .expiresAt(expiresAt)
                .contextVersion(pendingContextVersion)
                .recordVersion(recordVersion)
                .contextConflictPolicy(contextConflictPolicy)
                .policyDecisionReason(policyDecisionReason)
                .toolSchemaHash(toolSchemaHash)
                .preview(preview)
                .previewHash(previewHash)
                .description(snapshot.getDescription())
                .modelCode(modelCode)
                .runPid(firstNonBlank(snapshot.getRunPid(), ctx != null ? ctx.turnId() : null))
                .taskPid(firstNonBlank(snapshot.getTaskPid(), ctx != null ? ctx.taskPid() : null))
                .agentToolDefinitions(toolDefinitions)
                .messages(LlmMessageTapeSupport.serializeMessages(snapshot.getMessages()))
                .providerCode(snapshot.getProviderCode())
                .model(snapshot.getModel())
                .systemPrompt(snapshot.getSystemPrompt())
                .maxTokens(snapshot.getMaxTokens())
                .currentLoop(snapshot.getCurrentLoop() != null ? snapshot.getCurrentLoop() : 0)
                .createdAt(createdAt)
                .extension(extension.isEmpty() ? null : extension)
                .build();
    }

    public PendingToolSnapshot buildFromBasis(PendingToolSnapshot basis,
                                                       BasisSnapshot snapshot) {
        if (basis == null) {
            throw new IllegalArgumentException("basis is required");
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot is required");
        }
        TurnContext ctx = snapshot.getCtx();
        return build(Snapshot.builder()
                .ctx(ctx)
                .agentCode(basis.getAgentCode())
                .sessionId(basis.getSessionId())
                .channel(firstNonBlank(snapshot.getChannel(),
                        ctx != null ? ctx.channel() : basis.getChannel()))
                .profileId(firstNonBlank(snapshot.getProfileId(),
                        ctx != null ? ctx.profileId() : basis.getProfileId()))
                .channelSessionPid(firstNonBlank(snapshot.getChannelSessionPid(),
                        ctx != null ? ctx.channelSessionId() : basis.getChannelSessionPid()))
                .toolId(snapshot.getToolId())
                .toolName(snapshot.getToolName())
                .toolSpanId(snapshot.getToolSpanId())
                .input(snapshot.getInput())
                .toolVersion(snapshot.getToolVersion())
                .argsHash(snapshot.getArgsHash())
                .idempotencyKey(snapshot.getIdempotencyKey())
                .expiresAt(snapshot.getExpiresAt())
                .contextVersion(firstNonBlank(snapshot.getContextVersion(), basis.getContextVersion()))
                .recordVersion(firstNonBlank(snapshot.getRecordVersion(), basis.getRecordVersion()))
                .contextConflictPolicy(firstNonBlank(snapshot.getContextConflictPolicy(), basis.getContextConflictPolicy()))
                .policyDecisionReason(snapshot.getPolicyDecisionReason())
                .toolSchemaHash(snapshot.getToolSchemaHash())
                .preview(snapshot.getPreview())
                .previewHash(snapshot.getPreviewHash())
                .description(snapshot.getDescription())
                .modelCode(basis.getModelCode())
                .runPid(firstNonBlank(snapshot.getRunPid(), basis.getRunPid()))
                .taskPid(firstNonBlank(snapshot.getTaskPid(), basis.getTaskPid()))
                .agentToolDefinitions(basis.getAgentToolDefinitions())
                .contextBlocks(snapshot.getContextBlocks())
                .messages(snapshot.getMessages())
                .providerCode(basis.getProviderCode())
                .model(basis.getModel())
                .systemPrompt(basis.getSystemPrompt())
                .maxTokens(basis.getMaxTokens())
                .currentLoop(snapshot.getCurrentLoop() != null
                        ? snapshot.getCurrentLoop()
                        : basis.getCurrentLoop())
                .extension(snapshot.getExtension())
                .build());
    }

    public List<AgentToolDefinition> toAgentToolDefinitions(List<ToolDefinition> toolDefinitions) {
        if (toolDefinitions == null || toolDefinitions.isEmpty()) {
            return List.of();
        }
        List<AgentToolDefinition> result = new ArrayList<>();
        for (ToolDefinition def : toolDefinitions) {
            if (def == null || def.getToolCode() == null || def.getToolCode().isBlank()) {
                continue;
            }
            result.add(AgentToolDefinition.builder()
                    .name(def.getToolCode())
                    .description(def.getDescription())
                    .inputSchema(def.getParameterSchema())
                    .toolType(def.getToolType())
                    .sourceCode(def.getSourceCode())
                    .requiresApproval(def.isRequiresApproval())
                    .requiresConfirmation(def.isRequiresConfirmation())
                    .riskLevel(def.getRiskLevel())
                    .requiredPermissions(def.getRequiredPermissions())
                    .confirmationPolicy(def.getConfirmationPolicy())
                    .build());
        }
        return List.copyOf(result);
    }

    private Map<String, Object> buildExtension(Snapshot snapshot) {
        Map<String, Object> extension = new LinkedHashMap<>(safeMap(snapshot.getExtension()));
        if (snapshot.getRuntimeSystemPrompt() == null && snapshot.getToolChoice() == null) {
            return extension;
        }

        Map<String, Object> pending = new LinkedHashMap<>();
        putIfNotBlank(pending, "toolId", snapshot.getToolId());
        putIfNotBlank(pending, "toolName", snapshot.getToolName());
        putIfNotBlank(pending, "approvalPid", snapshot.getApprovalPid());
        if (snapshot.getInput() != null && !snapshot.getInput().isEmpty()) {
            pending.put("input", snapshot.getInput());
        }

        AgentExecutionState state = runtimeStateFactory.chatTurnState(
                snapshot.getCtx(),
                snapshot.getAgentCode(),
                snapshot.getSessionId(),
                snapshot.getProviderCode(),
                snapshot.getModel(),
                snapshot.getCurrentLoop() != null ? snapshot.getCurrentLoop() : 0,
                snapshot.getToolChoice(),
                firstNonBlank(snapshot.getRuntimeSystemPrompt(), snapshot.getSystemPrompt()),
                snapshot.getMaxTokens() != null ? snapshot.getMaxTokens() : 0,
                snapshot.getMessages() != null ? snapshot.getMessages() : List.of(),
                toLlmTools(snapshot.getToolDefinitions()),
                snapshot.getToolDefinitions() != null ? snapshot.getToolDefinitions() : List.of(),
                pending);
        extension.put("_runtime_state", state.toSnapshotMap());
        return extension;
    }

    private List<AgentToolDefinition> resolveAgentToolDefinitions(Snapshot snapshot) {
        if (snapshot.getAgentToolDefinitions() != null) {
            return List.copyOf(snapshot.getAgentToolDefinitions());
        }
        return toAgentToolDefinitions(snapshot.getToolDefinitions());
    }

    private List<LlmChatRequest.Tool> toLlmTools(List<ToolDefinition> toolDefinitions) {
        if (toolDefinitions == null || toolDefinitions.isEmpty()) {
            return List.of();
        }
        List<LlmChatRequest.Tool> tools = new ArrayList<>();
        for (ToolDefinition def : toolDefinitions) {
            if (def == null || def.getToolCode() == null || def.getToolCode().isBlank()) {
                continue;
            }
            tools.add(LlmChatRequest.Tool.builder()
                    .name(def.getToolCode())
                    .description(def.getDescription())
                    .inputSchema(def.getParameterSchema() != null
                            ? def.getParameterSchema()
                            : Map.of("type", "object", "properties", Map.of()))
                    .build());
        }
        return List.copyOf(tools);
    }

    private Map<String, Object> resolveToolSchema(Snapshot snapshot,
                                                  List<AgentToolDefinition> agentToolDefinitions) {
        if (snapshot.getToolDefinitions() != null) {
            for (ToolDefinition definition : snapshot.getToolDefinitions()) {
                if (matchesTool(snapshot.getToolName(), definition)) {
                    return safeMap(definition.getParameterSchema());
                }
            }
        }
        if (agentToolDefinitions != null) {
            for (AgentToolDefinition definition : agentToolDefinitions) {
                if (matchesTool(snapshot.getToolName(), definition)) {
                    return safeMap(definition.getInputSchema());
                }
            }
        }
        return Map.of();
    }

    private String inferPolicyDecisionReason(Snapshot snapshot,
                                             List<AgentToolDefinition> agentToolDefinitions) {
        AgentToolDefinition matched = null;
        if (agentToolDefinitions != null) {
            for (AgentToolDefinition definition : agentToolDefinitions) {
                if (matchesTool(snapshot.getToolName(), definition)) {
                    matched = definition;
                    break;
                }
            }
        }
        if (matched != null && matched.isRequiresApproval()) {
            return "human_approval_required";
        }
        if (matched != null && (matched.isRequiresConfirmation()
                || isWriteTool(matched.getName(), matched.getToolType()))) {
            return "user_confirmation_required";
        }
        if (isWriteTool(snapshot.getToolName(), null)) {
            return "user_confirmation_required";
        }
        return "pending_tool_resume";
    }

    private PendingContextVersion resolveContextVersion(TurnContext ctx,
                                                        Snapshot snapshot,
                                                        Map<String, Object> input) {
        RecordScope contextScope = recordScopeFromContext(snapshot.getContextBlocks());
        String modelCode = firstNonBlank(snapshot.getModelCode(), contextScope.modelCode(),
                stringValue(input, "modelCode"),
                stringValue(input, "model_code"), stringValue(input, "object"), inferModelCode(snapshot));
        String recordPid = firstNonBlank(contextScope.recordPid(), recordPidFromInput(input));
        PendingContextVersion unresolved = PendingContextVersion.unresolved(modelCode, recordPid);
        if (contextVersionResolver == null) {
            return unresolved;
        }
        PendingContextVersionRequest request = new PendingContextVersionRequest(
                ctx != null ? ctx.tenantId() : null,
                modelCode,
                recordPid);
        if (!request.verifiable()) {
            return unresolved;
        }
        try {
            PendingContextVersion resolved = contextVersionResolver.resolve(request);
            return resolved != null ? resolved : unresolved;
        } catch (RuntimeException e) {
            return unresolved;
        }
    }

    private RecordScope recordScopeFromContext(List<AgentContextBlock> contextBlocks) {
        if (contextBlocks == null || contextBlocks.isEmpty()) {
            return RecordScope.empty();
        }
        RecordScope fallback = RecordScope.empty();
        for (AgentContextBlock block : contextBlocks) {
            if (block == null || block.provenance() == null) {
                continue;
            }
            AgentContextProvenance provenance = block.provenance();
            if (!provenance.readWriteRelevant() || provenance.recordPids().isEmpty()) {
                continue;
            }
            RecordScope scope = new RecordScope(
                    modelCodeFromProvenance(provenance),
                    firstNonBlank(provenance.recordPids().toArray(String[]::new)));
            if (!scope.hasRecordPid()) {
                continue;
            }
            if (provenance.source() == AgentContextSource.RECORD && scope.hasModelCode()) {
                return scope;
            }
            if (fallback.isEmpty()) {
                fallback = scope;
            }
        }
        return fallback;
    }

    private String modelCodeFromProvenance(AgentContextProvenance provenance) {
        if (provenance == null || provenance.scope() == null || provenance.scope().isBlank()) {
            return null;
        }
        String[] parts = provenance.scope().split("/");
        if (provenance.source() == AgentContextSource.RECORD) {
            return firstNonBlank(parts.length > 0 ? parts[0] : null);
        }
        if (provenance.source() == AgentContextSource.PAGE) {
            String candidate = parts.length > 1 ? parts[1] : parts[0];
            if (isPageKind(candidate)) {
                return null;
            }
            return firstNonBlank(candidate);
        }
        return firstNonBlank(parts.length > 0 ? parts[0] : null);
    }

    private boolean isPageKind(String value) {
        if (value == null) {
            return false;
        }
        return List.of("list", "detail", "form", "dashboard").contains(value.trim().toLowerCase());
    }

    private record RecordScope(String modelCode, String recordPid) {
        static RecordScope empty() {
            return new RecordScope(null, null);
        }

        boolean hasModelCode() {
            return modelCode != null && !modelCode.isBlank();
        }

        boolean hasRecordPid() {
            return recordPid != null && !recordPid.isBlank();
        }

        boolean isEmpty() {
            return !hasModelCode() && !hasRecordPid();
        }
    }

    private String inferModelCode(Snapshot snapshot) {
        String fromToolName = modelCodeFromToolName(snapshot.getToolName());
        if (fromToolName != null) {
            return fromToolName;
        }
        if (snapshot.getToolDefinitions() != null) {
            for (ToolDefinition definition : snapshot.getToolDefinitions()) {
                if (matchesTool(snapshot.getToolName(), definition)) {
                    String modelCode = firstNonBlank(modelCodeFromToolName(definition.getToolCode()),
                            modelCodeFromToolName(definition.getToolName()),
                            modelCodeFromToolName(definition.getSourceCode()));
                    if (modelCode != null) {
                        return modelCode;
                    }
                }
            }
        }
        if (snapshot.getAgentToolDefinitions() != null) {
            for (AgentToolDefinition definition : snapshot.getAgentToolDefinitions()) {
                if (matchesTool(snapshot.getToolName(), definition)) {
                    String modelCode = firstNonBlank(modelCodeFromToolName(definition.getName()),
                            modelCodeFromToolName(definition.getSourceCode()));
                    if (modelCode != null) {
                        return modelCode;
                    }
                }
            }
        }
        return null;
    }

    private String modelCodeFromToolName(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        for (String prefix : List.of("get_", "list_", "get:", "list:")) {
            if (normalized.startsWith(prefix) && normalized.length() > prefix.length()) {
                return normalized.substring(prefix.length());
            }
        }
        return null;
    }

    private String recordPidFromInput(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return null;
        }
        for (String key : List.of("recordPid", "targetRecordPid", "record_pid", "target_record_pid")) {
            String value = stringValue(input, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Map<String, Object> input, String key) {
        if (input == null || key == null) {
            return null;
        }
        Object value = input.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private boolean matchesTool(String toolName, ToolDefinition definition) {
        if (definition == null) {
            return false;
        }
        return equalsAny(toolName, definition.getToolCode(), definition.getToolName(), definition.getSourceCode());
    }

    private boolean matchesTool(String toolName, AgentToolDefinition definition) {
        if (definition == null) {
            return false;
        }
        return equalsAny(toolName, definition.getName(), definition.getSourceCode());
    }

    private boolean equalsAny(String target, String... values) {
        if (target == null || values == null) {
            return false;
        }
        for (String value : values) {
            if (target.equals(value)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWriteTool(String toolName, String toolType) {
        String name = toolName == null ? "" : toolName.trim().toLowerCase();
        String type = toolType == null ? "" : toolType.trim().toLowerCase();
        return "dsl_command".equals(type)
                || "platform".equals(type)
                || "aurabot_skill".equals(type)
                || name.startsWith("cmd:")
                || name.startsWith("cmd_")
                || name.contains("create")
                || name.contains("update")
                || name.contains("delete")
                || name.contains("execute");
    }

    private String buildIdempotencyKey(String toolName, String toolVersion, String argsHash) {
        return firstNonBlank(toolName, "unknown_tool")
                + ":" + firstNonBlank(toolVersion, DEFAULT_TOOL_VERSION)
                + ":" + argsHash;
    }

    private String hash(Map<String, Object> value) {
        try {
            byte[] bytes = HASH_MAPPER.writeValueAsBytes(value == null ? Map.of() : value);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            return HexFormat.of().formatHex(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        }
    }

    private String hashText(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return HexFormat.of().formatHex(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static Map<String, Object> safeMap(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return Map.of();
        }
        return new LinkedHashMap<>(input);
    }

    private static void putIfNotBlank(Map<String, Object> out, String key, String value) {
        if (value != null && !value.isBlank()) {
            out.put(key, value);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Getter
    @Builder
    public static class Snapshot {
        private TurnContext ctx;
        private String agentCode;
        private String sessionId;
        private String channel;
        private String profileId;
        private String channelSessionPid;
        private String toolId;
        private String toolName;
        private String toolSpanId;
        private Map<String, Object> input;
        private String toolVersion;
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
        private String runPid;
        private String taskPid;
        private List<AgentToolDefinition> agentToolDefinitions;
        private List<ToolDefinition> toolDefinitions;
        private List<AgentContextBlock> contextBlocks;
        private List<LlmChatRequest.Message> messages;
        private String providerCode;
        private String model;
        private String systemPrompt;
        private String runtimeSystemPrompt;
        private Integer maxTokens;
        private Integer currentLoop;
        private String toolChoice;
        private String approvalPid;
        private Map<String, Object> extension;
    }

    @Getter
    @Builder
    public static class BasisSnapshot {
        private TurnContext ctx;
        private String channel;
        private String profileId;
        private String channelSessionPid;
        private String toolId;
        private String toolName;
        private String toolSpanId;
        private Map<String, Object> input;
        private String toolVersion;
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
        private String runPid;
        private String taskPid;
        private List<LlmChatRequest.Message> messages;
        private List<AgentContextBlock> contextBlocks;
        private Integer currentLoop;
        private Map<String, Object> extension;
    }
}
