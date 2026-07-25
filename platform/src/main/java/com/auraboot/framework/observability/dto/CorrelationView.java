package com.auraboot.framework.observability.dto;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.auraboot.framework.application.security.AdminAuditService;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import com.auraboot.framework.meta.dto.CommandAuditLogDTO;
import lombok.Data;

import java.util.List;

/**
 * Unified eagle-eye view: everything correlated to one distributed trace id
 * (SoT §2.3 cross-domain correlation). Joins the cost, behavior and audit domains
 * by {@code trace_id} — the cross-system key shared by ab_gen_ai_usage,
 * ab_behavior_event, ab_admin_event_log, ab_permission_audit_log and ab_admin_action_log
 * (all stamped with the OTel trace id). With ab_admin_action_log joined, every audit surface
 * SoT 121 §6 listed as missing from the unified entry point is now reachable from a trace id.
 *
 * <p>Permission denials were the last domain to join, and the most conspicuous omission:
 * ab_permission_audit_log is the busiest audit table in the product (1017 rows in a shared
 * database where ab_admin_event_log had 0), and "why was this refused" is the most common
 * question anyone brings to a troubleshooting console. Until it had a trace_id column there
 * was nothing to join on.
 */
@Data
public class CorrelationView {
    private String traceId;
    private List<CommandAuditLogDTO> commandAudits; // command pipeline domain (phase timings + error)
    private List<GenAiUsageRecord> llmUsage;     // cost domain (A-G6)
    private List<BehaviorEvent> behaviorEvents;  // behavior domain (M1)
    private List<AdminEventLog> auditEvents;      // audit domain (A-G2)
    private List<PermissionDenialView> permissionDenials; // permission DENY domain
    private List<AdminAuditService.AdminActionView> adminActions; // admin HTTP request domain
}
