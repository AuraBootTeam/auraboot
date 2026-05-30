package com.auraboot.framework.chatbi.v2.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.chatbi.v2.dto.ChatBiAnswerResponse;
import com.auraboot.framework.chatbi.v2.entity.ChatBiConversation;
import com.auraboot.framework.chatbi.v2.mapper.ChatBiConversationMapper;
import com.auraboot.framework.chatbi.v2.service.ChatBiAnswerService;
import com.auraboot.framework.chatbi.v2.service.ConversationService;
import com.auraboot.framework.chatbi.v2.service.DisambiguationService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST surface for ChatBI v2. PRD 17 §6 + §11. Six endpoints under
 * {@code /api/chatbi/v2}:
 *
 * <ul>
 *   <li>{@code POST   /conversations} — create a new multi-turn session.</li>
 *   <li>{@code GET    /conversations} — list current user's active sessions.</li>
 *   <li>{@code DELETE /conversations/{pid}} — close a session (idempotent).</li>
 *   <li>{@code POST   /conversations/{pid}/ask} — ask a question in-context.</li>
 *   <li>{@code POST   /conversations/{pid}/reset} — clear context window.</li>
 *   <li>{@code POST   /conversations/{pid}/disambiguate} — pick a candidate
 *       when a prior {@code ask} returned {@code DISAMBIGUATION}.</li>
 * </ul>
 *
 * <p>Tenant + user scoping is read from {@link MetaContext} (set by the
 * {@code JwtAuthenticationFilter}). All endpoints require
 * {@link MetaPermission#META_CHATBI_USE} ({@code meta.chatbi.use}), registered
 * in {@code default-bootstrap.json} and granted to {@code tenant_admin}
 * (via {@code *}), {@code operator}, and {@code viewer} roles by default.
 */
@RestController
@RequestMapping("/api/chatbi/v2")
@RequiredArgsConstructor
public class ChatBiV2Controller {

    private final ChatBiAnswerService answerService;
    private final ConversationService conversationService;
    private final ChatBiConversationMapper conversationMapper;
    private final DisambiguationService disambiguationService;

    // -- conversation lifecycle -----------------------------------------

    @PostMapping("/conversations")
    @RequirePermission(MetaPermission.META_CHATBI_USE)
    public ResponseEntity<Map<String, String>> createConversation(
            @RequestBody(required = false) CreateConversationRequest body) {
        String modelPid = body != null ? body.getSemanticModelPid() : null;
        String pid = conversationService.create(
                MetaContext.getCurrentTenantId(),
                MetaContext.getCurrentUserId(),
                modelPid);
        return ResponseEntity.ok(Map.of("conversationPid", pid));
    }

    @GetMapping("/conversations")
    @RequirePermission(MetaPermission.META_CHATBI_USE)
    public ResponseEntity<List<ChatBiConversation>> listConversations(
            @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(conversationMapper.listActiveByUser(
                MetaContext.getCurrentTenantId(),
                MetaContext.getCurrentUserId(),
                Math.min(100, Math.max(1, limit))));
    }

    @DeleteMapping("/conversations/{pid}")
    @RequirePermission(MetaPermission.META_CHATBI_USE)
    public ResponseEntity<Map<String, Boolean>> closeConversation(@PathVariable String pid) {
        boolean closed = conversationService.close(MetaContext.getCurrentTenantId(), pid);
        return ResponseEntity.ok(Map.of("closed", closed));
    }

    // -- ask / reset / disambiguate -------------------------------------

    @PostMapping("/conversations/{pid}/ask")
    @RequirePermission(MetaPermission.META_CHATBI_USE)
    public ResponseEntity<ChatBiAnswerResponse> ask(@PathVariable String pid,
                                                    @RequestBody AskRequest body) {
        ChatBiAnswerResponse r = answerService.ask(
                body.getQuestion(),
                pid,
                body.getSemanticModelPid());
        return ResponseEntity.ok(r);
    }

    @PostMapping("/conversations/{pid}/reset")
    @RequirePermission(MetaPermission.META_CHATBI_USE)
    public ResponseEntity<Map<String, Boolean>> resetContext(@PathVariable String pid) {
        boolean ok = conversationService.resetContext(
                MetaContext.getCurrentTenantId(), pid);
        return ResponseEntity.ok(Map.of("reset", ok));
    }

    @PostMapping("/conversations/{pid}/disambiguate")
    @RequirePermission(MetaPermission.META_CHATBI_USE)
    public ResponseEntity<Map<String, Boolean>> recordDisambiguationChoice(
            @PathVariable String pid,
            @RequestBody DisambiguateRequest body) {
        boolean ok = disambiguationService.recordChoice(
                MetaContext.getCurrentTenantId(),
                body.getDisambiguationLogPid(),
                body.getChosenCode());
        return ResponseEntity.ok(Map.of("recorded", ok));
    }

    // -- request DTOs ---------------------------------------------------

    @lombok.Data
    public static class CreateConversationRequest {
        private String semanticModelPid;
    }

    @lombok.Data
    public static class AskRequest {
        private String question;
        private String semanticModelPid;
    }

    @lombok.Data
    public static class DisambiguateRequest {
        private String disambiguationLogPid;
        private String chosenCode;
    }
}
