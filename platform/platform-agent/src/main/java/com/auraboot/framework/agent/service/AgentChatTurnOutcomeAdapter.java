package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnOutcome;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
class AgentChatTurnOutcomeAdapter {

    static final String HANDOFF_TOOL_NAME = "transfer_to_agent";
    static final String META_HANDOFF_TO = "_handoff_to";
    static final String META_HANDOFF_CONTEXT = "_handoff_context";

    private final ChatTurnRuntime chatTurnRuntime;
    private final ObjectMapper objectMapper;

    TurnOutcome buildApprovalRequiredOutcome(Map<String, Object> result, String toolName,
                                             Map<String, Object> input, ResponseSink sink) {
        String approvalPid = approvalPidFrom(result);
        if (approvalPid == null) {
            String error = String.valueOf(result.getOrDefault("error",
                    "Approval required but no approval pid available. No data was changed."));
            sink.onError(error, null);
            return new TurnOutcome.Failed(error, null);
        }

        String description = String.valueOf(result.getOrDefault("message", "Approval required"));
        Map<String, Object> confirmInput = new LinkedHashMap<>();
        confirmInput.put("toolName", toolName);
        confirmInput.put("input", input != null ? input : Map.of());
        sink.onConfirmRequired(
                approvalPid,
                "agent_approval_gate",
                description,
                confirmInput,
                approvalPid);
        return new TurnOutcome.PendingConfirmation(approvalPid, "", approvalPid);
    }

    TurnOutcome buildHandoffOutcome(LlmChatResponse response, ResponseSink sink,
                                    Map<String, Object> input) {
        String text = chatTurnRuntime.finalResponseText(response);
        if (!text.isEmpty()) {
            sink.onTextChunk(text);
        }
        sink.onDone(text, null);

        Map<String, Object> meta = new LinkedHashMap<>();
        Object targetAgentCode = input != null ? input.get("agent_code") : null;
        if (targetAgentCode != null) {
            meta.put(META_HANDOFF_TO, String.valueOf(targetAgentCode));
        }
        Object context = input != null ? input.get("context") : null;
        if (context != null) {
            meta.put(META_HANDOFF_CONTEXT, String.valueOf(context));
        }
        log.info("Handoff signal detected: target={}, context={}",
                meta.get(META_HANDOFF_TO),
                meta.containsKey(META_HANDOFF_CONTEXT) ? "present" : "absent");
        return new TurnOutcome.Success(text, meta);
    }

    String buildToolDescription(String toolName, Map<String, Object> input) {
        try {
            return toolName + " " + objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return toolName;
        }
    }

    String approvalPidFrom(Map<String, Object> result) {
        if (result == null) {
            return null;
        }
        Object rawPid = result.get("approvalPid");
        String approvalPid = rawPid != null ? String.valueOf(rawPid) : null;
        return approvalPid == null || approvalPid.isBlank() ? null : approvalPid;
    }
}
