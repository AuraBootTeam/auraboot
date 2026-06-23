package com.auraboot.framework.observability.dto;

import com.auraboot.framework.agent.trace.entity.GenAiUsageRecord;
import com.auraboot.framework.audit.entity.AdminEventLog;
import com.auraboot.framework.behavior.entity.BehaviorEvent;
import lombok.Data;

import java.util.List;

/**
 * Unified eagle-eye view: everything correlated to one distributed trace id
 * (SoT §2.3 cross-domain correlation). Joins the cost, behavior and audit domains
 * by {@code trace_id} — the cross-system key shared by ab_gen_ai_usage,
 * ab_behavior_event and ab_admin_event_log (all stamped with the OTel trace id).
 */
@Data
public class CorrelationView {
    private String traceId;
    private List<GenAiUsageRecord> llmUsage;     // cost domain (A-G6)
    private List<BehaviorEvent> behaviorEvents;  // behavior domain (M1)
    private List<AdminEventLog> auditEvents;      // audit domain (A-G2)
}
