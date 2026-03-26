package com.auraboot.framework.notification.controller;

import com.auraboot.framework.common.dto.ApiResponse;
// BusinessException is handled by the global exception handler
import com.auraboot.framework.notification.dto.NotificationRuleDTO;
import com.auraboot.framework.notification.dto.NotificationRuleRequest;
import com.auraboot.framework.notification.dto.NotificationRuleTestResult;
import com.auraboot.framework.notification.service.NotificationRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for notification rules.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET    /api/notification-rules         — list all rules</li>
 *   <li>GET    /api/notification-rules/{id}    — get single rule</li>
 *   <li>POST   /api/notification-rules         — create rule</li>
 *   <li>PUT    /api/notification-rules/{id}    — update rule</li>
 *   <li>DELETE /api/notification-rules/{id}    — soft delete</li>
 *   <li>POST   /api/notification-rules/{id}/test — test evaluate</li>
 *   <li>PUT    /api/notification-rules/{id}/toggle — enable/disable</li>
 * </ul>
 *
 * @since 5.2.0
 */
@Slf4j
@RestController
@RequestMapping("/api/notification-rules")
@RequiredArgsConstructor
public class NotificationRuleController {

    private final NotificationRuleService ruleService;

    /**
     * List all notification rules for the current tenant.
     * GET /api/notification-rules
     */
    @GetMapping
    public ApiResponse<List<NotificationRuleDTO>> list() {
        return ApiResponse.success(ruleService.listRules());
    }

    /**
     * Get a single notification rule.
     * GET /api/notification-rules/{id}
     */
    @GetMapping("/{id}")
    public ApiResponse<NotificationRuleDTO> get(@PathVariable Long id) {
        return ApiResponse.success(ruleService.getRule(id));
    }

    /**
     * Create a new notification rule.
     * POST /api/notification-rules
     */
    @PostMapping
    public ApiResponse<NotificationRuleDTO> create(@Valid @RequestBody NotificationRuleRequest request) {
        return ApiResponse.success(ruleService.createRule(request));
    }

    /**
     * Update an existing notification rule.
     * PUT /api/notification-rules/{id}
     */
    @PutMapping("/{id}")
    public ApiResponse<NotificationRuleDTO> update(@PathVariable Long id,
                                                    @Valid @RequestBody NotificationRuleRequest request) {
        return ApiResponse.success(ruleService.updateRule(id, request));
    }

    /**
     * Soft-delete a notification rule.
     * DELETE /api/notification-rules/{id}
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        ruleService.deleteRule(id);
        return ApiResponse.success();
    }

    /**
     * Enable or disable a rule.
     * PUT /api/notification-rules/{id}/toggle
     * Body: { "enabled": true/false }
     */
    @PutMapping("/{id}/toggle")
    public ApiResponse<NotificationRuleDTO> toggle(@PathVariable Long id,
                                                    @RequestBody Map<String, Boolean> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        return ApiResponse.success(ruleService.toggleEnabled(id, enabled));
    }

    /**
     * Test-evaluate a rule — runs the condition query without sending notifications.
     * POST /api/notification-rules/{id}/test
     */
    @PostMapping("/{id}/test")
    public ApiResponse<NotificationRuleTestResult> test(@PathVariable Long id) {
        NotificationRuleTestResult result = ruleService.testEvaluateRule(id);
        return ApiResponse.success(result);
    }
}
