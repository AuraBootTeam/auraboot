package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.dto.AiActionRiskLevel;
import com.auraboot.framework.agent.service.AiActionAuditService;
import com.auraboot.framework.agent.service.AiActionRiskAssessor;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI Action Safety API — risk assessment and audit log for client-driven AI suggestions.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/mobile/ai/action/assess-risk — assess risk level for an action</li>
 *   <li>POST /api/mobile/ai/action/audit — record an action audit entry</li>
 *   <li>GET /api/mobile/ai/audit-log — query audit logs</li>
 * </ul>
 *
 * <p><strong>Scope.</strong> This is the client-side half: a client asks what a suggested
 * action's risk is so it can choose a confirmation UX, then reports what the user decided.
 * It is advisory by nature — the client is free to ignore the answer. Enforcement of
 * {@code BLOCKED} lives server-side in {@code AiActionGuardrail}, on the agent tool-call
 * path, where it cannot be skipped.
 *
 * <p><strong>The audit no longer trusts the client's risk claim.</strong> {@code riskLevel}
 * used to be stored exactly as posted, so a buggy or hostile client could file a BLOCKED
 * action as {@code low}. The server re-assesses and stores its own verdict; the client's
 * claim is kept in the payload, and a disagreement is logged — that gap is the interesting
 * signal, not something to discard.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/ai")
@RequiredArgsConstructor
public class AiActionAuditController {

    private final AiActionRiskAssessor riskAssessor;
    private final AiActionAuditService auditService;

    /**
     * Assess risk level for an AI-suggested action.
     *
     * @param actionType  action type (copy, navigate, execute_command, create_task)
     * @param commandCode optional command code for execute_command actions
     * @return risk level: low, medium, high, or blocked
     */
    @PostMapping("/action/assess-risk")
    @AuthenticatedAccess("read-only risk lookup for the caller's own tenant; the answer is "
            + "advisory and enforcement happens server-side in AiActionGuardrail")
    public ApiResponse<Map<String, String>> assessRisk(
            @RequestParam String actionType,
            @RequestParam(required = false) String commandCode) {

        Long tenantId = MetaContext.getCurrentTenantId();
        AiActionRiskLevel level = riskAssessor.assess(actionType, commandCode, tenantId);

        return ApiResponse.success(Map.of("riskLevel", level.code()));
    }

    /**
     * Record an AI action audit entry.
     * Called by mobile clients after user confirms or cancels an AI-suggested action.
     */
    @PostMapping("/action/audit")
    @AuthenticatedAccess("a caller files an audit entry for their own decision; tenant and "
            + "user are taken from MetaContext, never from the body")
    public ApiResponse<Void> recordAudit(@RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        String conversationId = (String) body.get("conversationId");
        String messageId = (String) body.get("messageId");
        String actionType = (String) body.get("actionType");
        String commandCode = (String) body.get("commandCode");
        String modelCode = (String) body.get("modelCode");
        String recordPid = firstString(body, "recordPid", "targetRecordPid");
        String riskLevel = (String) body.get("riskLevel");
        String userDecision = (String) body.get("userDecision");
        String executionResult = (String) body.get("executionResult");
        String errorMessage = (String) body.get("errorMessage");
        String reasoning = (String) body.get("reasoning");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) body.get("payload");

        if (actionType == null || userDecision == null) {
            return ApiResponse.error(400, "actionType and userDecision are required");
        }

        // The server decides the risk level; the client's claim is evidence, not authority.
        String assessed = riskAssessor.assess(actionType, commandCode, tenantId).code();
        if (riskLevel != null && !riskLevel.equalsIgnoreCase(assessed)) {
            log.warn("Client reported riskLevel={} for action={} command={} but the server "
                            + "assessed {}; recording the server's verdict",
                    riskLevel, actionType, commandCode, assessed);
        }
        Map<String, Object> auditPayload = new java.util.LinkedHashMap<>();
        if (payload != null) {
            auditPayload.putAll(payload);
        }
        if (riskLevel != null) {
            auditPayload.put("clientClaimedRiskLevel", riskLevel);
        }

        auditService.record(tenantId, userId, conversationId, messageId,
                actionType, commandCode, modelCode, recordPid,
                assessed, userDecision,
                executionResult, errorMessage, reasoning, auditPayload);

        return ApiResponse.success(null);
    }

    /**
     * Query AI action audit logs for the current tenant.
     * Intended for admin / compliance review.
     */
    @GetMapping("/audit-log")
    @RequirePermission(MetaPermission.COMMAND_READ)
    public ApiResponse<Map<String, Object>> queryAuditLog(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {

        Long tenantId = MetaContext.getCurrentTenantId();

        List<Map<String, Object>> records = auditService.queryLogs(tenantId, pageNum, pageSize);
        long total = auditService.countLogs(tenantId);

        return ApiResponse.success(Map.of(
                "records", records,
                "total", total,
                "pageNum", pageNum,
                "pageSize", pageSize
        ));
    }

    private static String firstString(Map<String, Object> body, String... keys) {
        if (body == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Object value = body.get(key);
            if (value == null) {
                continue;
            }
            String text = String.valueOf(value).trim();
            if (!text.isEmpty()) {
                return text;
            }
        }
        return null;
    }
}
