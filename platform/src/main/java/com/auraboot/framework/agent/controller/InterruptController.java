package com.auraboot.framework.agent.controller;

import com.auraboot.framework.agent.service.InterruptClassifier;
import com.auraboot.framework.agent.service.InterruptDispatcher;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Channel-gateway-facing Interrupt API (ACP-Ideal §6.1.5).
 *
 *   POST /api/aurabot/sessions/{sessionId}/interrupt
 *        Body: { "new_message": "...", "active_run_id": "...?", "current_intent_summary": "..." }
 *
 *   GET  /api/aurabot/sessions/{sessionId}/interrupt-log
 *        Returns recent interrupt decisions for the session (audit).
 */
@Slf4j
@RestController
@RequestMapping("/api/aurabot/sessions")
@RequiredArgsConstructor
public class InterruptController {

    private final InterruptClassifier classifier;
    private final InterruptDispatcher dispatcher;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Classify a user interrupt + dispatch its consequence. Returns the
     * decision record so the gateway can decide what to display.
     */
    @PostMapping("/{sessionId}/interrupt")
    public ApiResponse<Map<String, Object>> handleInterrupt(@PathVariable String sessionId,
                                                             @RequestBody Map<String, Object> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String newMessage = (String) body.get("new_message");
        String activeRunId = (String) body.get("active_run_id");
        String currentIntent = (String) body.get("current_intent_summary");

        InterruptClassifier.Classification c = classifier.classify(newMessage, currentIntent);
        InterruptDispatcher.DispatchResult r = dispatcher.dispatch(tenantId, sessionId,
                activeRunId, newMessage, c);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sub_policy", c.getSubPolicy());
        out.put("confidence", c.getConfidence());
        out.put("tier", c.getTier());
        out.put("reason", c.getReason());
        out.put("action_taken", r.getActionTaken());
        out.put("interrupt_log_pid", r.getInterruptLogPid());
        out.put("active_run_id", r.getActiveRunId());
        // P0-6: surface spawned child run id when INSERT_SUBTASK forked one.
        // Always present in the response shape (null for non-spawn paths) so
        // gateway clients can rely on the key without optional checks.
        out.put("subtask_run_id", r.getSubtaskRunId());
        return ApiResponse.ok(out);
    }

    /**
     * Tenant-wide interrupt audit log for Mission Control. Optional
     * sub_policy filter (replace_intent | append_context | insert_subtask).
     */
    @GetMapping("/interrupts")
    public ApiResponse<List<Map<String, Object>>> listTenantInterrupts(
            @RequestParam(required = false) String subPolicy,
            @RequestParam(defaultValue = "100") int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int capped = Math.min(Math.max(1, limit), 500);

        StringBuilder sql = new StringBuilder(
                "SELECT pid, session_id, active_run_id, new_message_excerpt, sub_policy, " +
                        "       classifier_tier, confidence, reason, action_taken, subtask_run_id, created_at " +
                        "FROM ab_agent_interrupt_log WHERE tenant_id = ? ");
        List<Object> params = new java.util.ArrayList<>();
        params.add(tenantId);
        if (subPolicy != null && !subPolicy.isBlank()) {
            sql.append("AND sub_policy = ? ");
            params.add(subPolicy);
        }
        sql.append("ORDER BY created_at DESC LIMIT ?");
        params.add(capped);

        return ApiResponse.ok(jdbcTemplate.queryForList(sql.toString(), params.toArray()));
    }

    @GetMapping("/{sessionId}/interrupt-log")
    public ApiResponse<List<Map<String, Object>>> listInterrupts(@PathVariable String sessionId,
                                                                  @RequestParam(defaultValue = "50") int limit) {
        Long tenantId = MetaContext.getCurrentTenantId();
        int capped = Math.min(Math.max(1, limit), 200);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT pid, active_run_id, new_message_excerpt, sub_policy, " +
                        "       classifier_tier, confidence, reason, action_taken, subtask_run_id, created_at " +
                        "FROM ab_agent_interrupt_log " +
                        "WHERE tenant_id = ? AND session_id = ? " +
                        "ORDER BY created_at DESC LIMIT ?",
                tenantId, sessionId, capped);
        return ApiResponse.ok(rows);
    }
}
