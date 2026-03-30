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
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        chatService.streamChat(tenantId, request, emitter);
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
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        chatService.resumeAfterConfirmation(tenantId, request.getSessionId(),
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
