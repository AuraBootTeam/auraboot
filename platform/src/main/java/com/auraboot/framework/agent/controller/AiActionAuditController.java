package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.dto.AiActionRiskLevel;
import com.auraboot.framework.agent.service.AiActionAuditService;
import com.auraboot.framework.agent.service.AiActionRiskAssessor;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * AI Action Safety API — risk assessment and audit log.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /api/mobile/ai/action/assess-risk — assess risk level for an action</li>
 *   <li>POST /api/mobile/ai/action/audit — record an action audit entry</li>
 *   <li>GET /api/mobile/ai/audit-log — query audit logs (admin)</li>
 * </ul>
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
    public ApiResponse<Void> recordAudit(@RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        String conversationId = (String) body.get("conversationId");
        String messageId = (String) body.get("messageId");
        String actionType = (String) body.get("actionType");
        String commandCode = (String) body.get("commandCode");
        String modelCode = (String) body.get("modelCode");
        String recordId = (String) body.get("recordId");
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

        auditService.record(tenantId, userId, conversationId, messageId,
                actionType, commandCode, modelCode, recordId,
                riskLevel != null ? riskLevel : "low", userDecision,
                executionResult, errorMessage, reasoning, payload);

        return ApiResponse.success(null);
    }

    /**
     * Query AI action audit logs for the current tenant.
     * Intended for admin / compliance review.
     */
    @GetMapping("/audit-log")
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
}
