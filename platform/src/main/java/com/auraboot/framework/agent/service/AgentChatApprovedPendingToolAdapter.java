package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.runtime.PendingToolExecutionClaim;
import com.auraboot.framework.agent.runtime.PendingToolExecutionRecord;
import com.auraboot.framework.agent.runtime.PendingToolExecutionStatus;
import com.auraboot.framework.agent.runtime.PendingToolSnapshot;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.ToolLoopResultNormalizer;
import com.auraboot.framework.common.util.LogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
class AgentChatApprovedPendingToolAdapter {

    private final PendingToolStore pendingToolStore;
    private final ToolLoopService toolLoopService;
    private final ObjectMapper objectMapper;

    Map<String, Object> execute(Long tenantId, String approvalPid) {
        if (approvalPid == null || approvalPid.isBlank()) {
            return Map.of("handled", false);
        }

        PendingToolSnapshot pending = pendingToolStore.consumePendingForOwner(approvalPid, tenantId, null);
        if (pending == null) {
            return Map.of("handled", false);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("handled", true);
        response.put("approvalPid", approvalPid);
        response.put("toolName", pending.getToolName());

        if (tenantId == null || pending.getTenantId() == null || !tenantId.equals(pending.getTenantId())) {
            response.put("success", false);
            response.put("error", "Tenant mismatch for approved pending tool. No tool was executed.");
            return response;
        }
        if (toolLoopService == null) {
            response.put("success", false);
            response.put("error", "Agent tool execution kernel is not available. No tool was executed.");
            return response;
        }
        if (pending.getAgentToolDefinitions() == null || pending.getAgentToolDefinitions().isEmpty()) {
            response.put("success", false);
            response.put("error", "Approved pending tool has no tool definition snapshot. No tool was executed.");
            return response;
        }

        PendingToolExecutionClaim executionClaim = pendingToolStore.claimExecution(pending);
        if (executionClaim == null) {
            executionClaim = PendingToolExecutionClaim.acquired(PendingToolStore.executionKey(pending));
        }
        if (!executionClaim.acquired()) {
            response.putAll(replayPendingExecution(executionClaim.record()));
            return response;
        }
        String executionKey = executionClaim.record() != null
                ? executionClaim.record().executionKey()
                : PendingToolStore.executionKey(pending);

        try {
            List<AgentToolDefinition> approvedDefs = markToolApproved(
                    pending.getAgentToolDefinitions(), pending.getToolName());
            String rawResult = toolLoopService.executeToolCall(
                    tenantId,
                    pending.getRunPid(),
                    pending.getTaskPid(),
                    pending.getAgentCode(),
                    pending.getToolName(),
                    pending.getInput() != null ? pending.getInput() : Map.of(),
                    approvedDefs,
                    null);
            Map<String, Object> result = ToolLoopResultNormalizer.normalize(
                    objectMapper, rawResult, pending.getToolName(), pending.getInput());
            response.put("success", Boolean.TRUE.equals(result.get("success")));
            response.put("result", result);
            if (result.get("error") != null) {
                response.put("error", result.get("error"));
            }
            if (Boolean.TRUE.equals(result.get("success"))) {
                pendingToolStore.completeExecution(pending, executionKey, result);
            } else {
                pendingToolStore.failExecution(pending, executionKey, result,
                        result.get("error") != null ? String.valueOf(result.get("error")) : "Tool execution failed");
            }
            return response;
        } catch (Exception e) {
            log.warn("Approved pending tool execution failed: errorType={}", e.getClass().getSimpleName());
            String safeError = safeExceptionMessage(e);
            pendingToolStore.failExecution(pending, executionKey,
                    Map.of("success", false, "error", safeError),
                    safeError);
            response.put("success", false);
            response.put("error", safeError);
            return response;
        }
    }

    private Map<String, Object> replayPendingExecution(PendingToolExecutionRecord record) {
        Map<String, Object> replay = new LinkedHashMap<>();
        replay.put("replayed", true);
        if (record == null || record.status() == PendingToolExecutionStatus.RUNNING) {
            replay.put("success", false);
            replay.put("error", "Pending tool execution is already running.");
            return replay;
        }
        replay.put("success", record.status() == PendingToolExecutionStatus.SUCCEEDED);
        if (record.result() != null && !record.result().isEmpty()) {
            replay.put("result", record.result());
        }
        if (record.status() == PendingToolExecutionStatus.FAILED) {
            replay.put("error", record.errorMessage() != null ? record.errorMessage() : "Tool execution failed.");
        }
        return replay;
    }

    private List<AgentToolDefinition> markToolApproved(List<AgentToolDefinition> toolDefs, String approvedToolName) {
        if (toolDefs == null || toolDefs.isEmpty()) {
            return Collections.emptyList();
        }
        List<AgentToolDefinition> result = new ArrayList<>();
        for (AgentToolDefinition def : toolDefs) {
            if (def == null) {
                continue;
            }
            boolean approvedTarget = approvedToolName != null && approvedToolName.equals(def.getName());
            result.add(AgentToolDefinition.builder()
                    .name(def.getName())
                    .description(def.getDescription())
                    .inputSchema(def.getInputSchema())
                    .toolType(def.getToolType())
                    .sourceCode(def.getSourceCode())
                    .requiresApproval(approvedTarget ? false : def.isRequiresApproval())
                    .requiresConfirmation(def.isRequiresConfirmation())
                    .riskLevel(def.getRiskLevel())
                    .requiredPermissions(def.getRequiredPermissions())
                    .confirmationPolicy(def.getConfirmationPolicy())
                    .nativeToolConfig(def.getNativeToolConfig())
                    .build());
        }
        return result;
    }

    private String safeExceptionMessage(Exception e) {
        if (e == null) {
            return "Unknown error";
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return e.getClass().getSimpleName();
        }
        return LogSanitizer.safe(message);
    }
}
