package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.agent.service.ActiveMemoryService;
import com.auraboot.framework.agent.service.AgentRunService;
import com.auraboot.framework.agent.service.RunOutcome;
import com.auraboot.framework.agent.dto.ChatRequest;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.ResponseSinkContext;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ACP-backed durable workflow substrate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcpDurableWorkflowEngine implements DurableWorkflowEngine {

    private final AgentRunService agentRunService;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    @Override
    public boolean isAvailable() {
        return agentRunService != null && dynamicDataMapper != null;
    }

    @Override
    public TurnOutcome startConversationRun(TurnContext ctx, ChatRequest legacyRequest, ResponseSink sink) {
        ResponseSinkContext.set(sink);
        try {
            if (!isAvailable()) {
                return unavailableOutcome("start", null, null, sink);
            }
            String taskPid = createConversationTaskRow(ctx, legacyRequest);
            log.info("Durable conversation run dispatch: tenantId={}, turnId={}, taskPid={}",
                    ctx.tenantId(), ctx.turnId(), taskPid);
            RunOutcome runOutcome = agentRunService.executeTaskSync(
                    ctx.tenantId(), taskPid, ActiveMemoryService.DEFAULT_AGENT, null);
            return mapRunToTurnOutcome(runOutcome, sink);
        } catch (Exception e) {
            log.error("Durable conversation run dispatch failed: {}", e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            sink.onError(msg, null);
            return new TurnOutcome.Failed(msg, e);
        } finally {
            ResponseSinkContext.clear();
        }
    }

    @Override
    public TurnOutcome resumeConversationRun(TurnContext ctx, String taskPid, String runPid, ResponseSink sink) {
        if (!isAvailable() || taskPid == null || runPid == null) {
            return unavailableOutcome("resume", taskPid, runPid, sink);
        }
        ResponseSinkContext.set(sink);
        try {
            RunOutcome resumeOutcome = agentRunService.executeTaskSync(
                    ctx.tenantId(), taskPid, ActiveMemoryService.DEFAULT_AGENT, runPid);
            return mapRunToTurnOutcome(resumeOutcome, sink);
        } finally {
            ResponseSinkContext.clear();
        }
    }

    private TurnOutcome unavailableOutcome(String operation, String taskPid, String runPid, ResponseSink sink) {
        String msg = "Durable workflow " + operation + " blocked: missing substrate"
                + " (taskPid=" + taskPid
                + ", runPid=" + runPid
                + ", agentRunService=" + (agentRunService != null)
                + ", dynamicDataMapper=" + (dynamicDataMapper != null) + ")";
        log.error(msg);
        sink.onError(msg, null);
        return new TurnOutcome.Failed(msg, null);
    }

    private String createConversationTaskRow(TurnContext ctx, ChatRequest legacyRequest) {
        String taskPid = UniqueIdGenerator.generate();
        Map<String, Object> task = new HashMap<>();
        task.put("pid", taskPid);
        task.put("tenant_id", ctx.tenantId());
        task.put("title", buildTaskTitle(legacyRequest));
        task.put("description", legacyRequest != null ? legacyRequest.getMessage() : "");
        task.put("task_status", "in_progress");
        task.put("task_priority", "normal");
        task.put("assignee_type", "ai");
        task.put("assignee_id", ActiveMemoryService.DEFAULT_AGENT);
        task.put("created_at", LocalDateTime.now());
        task.put("updated_at", LocalDateTime.now());

        Map<String, Object> inputData = new LinkedHashMap<>();
        inputData.put("turnId", ctx.turnId());
        inputData.put("conversationId", ctx.conversationId());
        inputData.put("inboundMessageId", ctx.inboundMessageId());
        inputData.put("triageBucket", ctx.triageBucket() != null ? ctx.triageBucket().name() : null);
        inputData.put("userMessage", legacyRequest != null ? legacyRequest.getMessage() : null);
        try {
            task.put("input_data", objectMapper.writeValueAsString(inputData));
        } catch (JsonProcessingException ex) {
            task.put("input_data", "{}");
        }

        dynamicDataMapper.insert("ab_agent_task", task);
        return taskPid;
    }

    private static String buildTaskTitle(ChatRequest legacyRequest) {
        if (legacyRequest == null || legacyRequest.getMessage() == null) {
            return "Aurabot turn";
        }
        String msg = legacyRequest.getMessage().trim();
        if (msg.length() > 80) {
            return msg.substring(0, 80) + "...";
        }
        return msg.isEmpty() ? "Aurabot turn" : msg;
    }

    private TurnOutcome mapRunToTurnOutcome(RunOutcome ro, ResponseSink sink) {
        return switch (ro) {
            case RunOutcome.Success s -> {
                String response = s.finalResponse() != null ? s.finalResponse() : "";
                sink.onDone(response, null);
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("runPid", s.runPid());
                meta.put("inputTokens", s.inputTokens());
                meta.put("outputTokens", s.outputTokens());
                meta.put("totalCost", s.totalCost());
                yield new TurnOutcome.Success(response, meta);
            }
            case RunOutcome.PendingApproval pa -> {
                String approvalPid = pa.approvalPid();
                if (approvalPid == null || approvalPid.isBlank()) {
                    String msg = "Approval required but durable run returned no approval pid"
                            + " (runPid=" + pa.runPid() + ")";
                    log.error(msg);
                    sink.onError(msg, null);
                    yield new TurnOutcome.Failed(msg, null);
                }
                Map<String, Object> input = new LinkedHashMap<>();
                input.put("runPid", pa.runPid());
                input.put("approvalPid", approvalPid);
                input.put("message", pa.message());
                sink.onConfirmRequired(
                        approvalPid,
                        "agent_approval_gate",
                        pa.message() != null ? pa.message() : "Approval required",
                        input,
                        approvalPid);
                yield new TurnOutcome.PendingConfirmation(
                        approvalPid,
                        pa.message() != null ? pa.message() : "Approval required",
                        approvalPid);
            }
            case RunOutcome.Failed f -> {
                String msg = f.errorMessage() != null ? f.errorMessage() : "Agent run failed";
                sink.onError(msg, null);
                yield new TurnOutcome.Failed(msg, null);
            }
            case RunOutcome.Skipped sk -> {
                String msg = sk.reason() != null ? sk.reason() : "Agent run skipped";
                sink.onError(msg, null);
                yield new TurnOutcome.Failed(msg, null);
            }
        };
    }
}
