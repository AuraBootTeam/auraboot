package com.auraboot.framework.aurabot.controller;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.AuraBotChatService;
import com.auraboot.framework.aurabot.service.ChatToolResolver;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/ai/aurabot")
@RequiredArgsConstructor
public class AuraBotController {

    private final AuraBotChatService chatService;
    private final ChatToolResolver chatToolResolver;

    /**
     * Stream chat via SSE — main endpoint.
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@RequestBody ChatRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String userPid = MetaContext.getCurrentUserPid();
        String username = MetaContext.getCurrentUsername();
        Long memberId = MetaContext.getCurrentMemberId();
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        chatService.streamChat(tenantId, userId, userPid, username, memberId, request, emitter);
        return emitter;
    }

    /**
     * Synchronous chat — for simple requests.
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest request) {
        return Map.of("code", "0", "message", "Use /chat/stream for streaming responses", "data", Map.of());
    }

    /**
     * Execute a tool/command action from the AI Panel.
     * Resumes the chat session after user confirms or cancels a pending tool call.
     */
    @PostMapping(value = "/execute", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter executeAction(@RequestBody ChatRequest.ExecuteRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String userPid = MetaContext.getCurrentUserPid();
        String username = MetaContext.getCurrentUsername();
        Long memberId = MetaContext.getCurrentMemberId();
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        chatService.resumeAfterConfirmation(tenantId, userId, userPid, username, memberId,
                request.getSessionId(),
                request.getToolId(), request.isConfirmed(), emitter);
        return emitter;
    }

    /**
     * Get available actions for current model/record context.
     * Returns write-only tools (commands that mutate data) as action descriptors.
     */
    @GetMapping("/actions")
    public Map<String, Object> getActions(
            @RequestParam(required = false) String modelCode,
            @RequestParam(required = false) String recordPid,
            @RequestParam(required = false) String recordStatus) {
        if (modelCode == null || modelCode.isBlank()) {
            return Map.of("code", "0", "data", List.of());
        }

        var resolved = chatToolResolver.resolveTools(null, modelCode, recordPid);

        // Filter to write-only tools (exclude read-only ones like NQ and builtin)
        List<Map<String, Object>> actions = resolved.tools().stream()
                .filter(tool -> !chatToolResolver.isReadOnly(tool.getName()))
                .map(tool -> {
                    Map<String, Object> action = new LinkedHashMap<>();
                    action.put("code", tool.getName());
                    action.put("label", tool.getDescription());
                    action.put("type", deriveToolType(tool.getName()));
                    return action;
                })
                .collect(Collectors.toList());

        return Map.of("code", "0", "data", actions);
    }

    /**
     * Proactive context-aware suggestions based on page context.
     * Returns rule-based suggestion chips (no LLM call, lightweight).
     */
    @PostMapping("/suggest")
    public Map<String, Object> suggest(@RequestBody ChatRequest.PageContext context) {
        if (context == null || context.getModelCode() == null) {
            return Map.of("code", "0", "data", List.of());
        }

        List<Map<String, String>> suggestions = new ArrayList<>();

        // Rule: if on a list page, suggest creating a new record
        if ("list".equals(context.getKind())) {
            suggestions.add(Map.of(
                    "text", "Create a new " + humanize(context.getModelCode()),
                    "action", "chat",
                    "prompt", "Create a new " + humanize(context.getModelCode()) + " record"
            ));
            suggestions.add(Map.of(
                    "text", "Show statistics for " + humanize(context.getModelCode()),
                    "action", "chat",
                    "prompt", "Give me a summary of " + humanize(context.getModelCode()) + " data — total count, status breakdown, recent trends"
            ));
        }

        // Rule: if on a detail page, suggest related actions
        if ("detail".equals(context.getKind()) && context.getRecordPid() != null) {
            suggestions.add(Map.of(
                    "text", "Analyze this record",
                    "action", "chat",
                    "prompt", "Analyze the " + humanize(context.getModelCode()) + " record " + context.getRecordPid() + " — show key fields and suggest next actions"
            ));
        }

        // Rule: if on a dashboard page, suggest data insights
        if ("dashboard".equals(context.getKind())) {
            suggestions.add(Map.of(
                    "text", "Explain this dashboard",
                    "action", "chat",
                    "prompt", "Explain what this dashboard shows and highlight any important trends or anomalies"
            ));
        }

        return Map.of("code", "0", "data", suggestions);
    }

    private String humanize(String modelCode) {
        if (modelCode == null) return "record";
        return modelCode.replace("_", " ");
    }

    /**
     * Derive a human-readable tool type from the tool name prefix.
     */
    private String deriveToolType(String toolName) {
        if (toolName == null) return "unknown";
        if (toolName.startsWith("cmd_")) return "command";
        if (toolName.startsWith("nq_")) return "query";
        if (toolName.startsWith("list_") || toolName.startsWith("get_")) return "query";
        if (toolName.startsWith("platform_")) return "platform";
        return "unknown";
    }
}
