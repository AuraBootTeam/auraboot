package com.auraboot.framework.automation.entity;

import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Trigger configuration for automation rules
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TriggerConfig {

    // ==================== Common fields ====================

    /**
     * Model code (for data-triggered automations)
     */
    private String modelCode;

    /**
     * Optional business sample used by the designer Test Run action. This keeps the
     * product-level verification context with the trigger instead of hard-coding demo
     * payloads in the frontend.
     */
    private String testRecordPid;

    private Map<String, Object> testContext;

    // ==================== DecisionRuntime integration (M4) ====================

    /**
     * Optional reference to a DecisionRuntime decision (decision_code). When set, the trigger
     * evaluates this decision against the event record before the SpEL condition and injects the
     * result as a {@code #decision} variable (e.g. {@code #decision['matched']},
     * {@code #decision['outputs']['route']}). Additive — null means no decision integration.
     */
    private String decisionRef;

    /** Version binding for {@link #decisionRef} (LATEST by default). */
    private String decisionBinding;

    /**
     * Platform rule-center binding contract. New consumers should prefer this over the legacy
     * {@link #decisionRef}/{@link #decisionBinding} pair so field/decision refs, fallback and trace
     * semantics stay consistent across Automation, BPM, SLA, EventPolicy and permissions.
     */
    private RuleConsumerBinding ruleBinding;

    // ==================== ON_RECORD_UPDATE specific ====================

    /**
     * Fields to watch for changes (for ON_RECORD_UPDATE)
     * If empty, triggers on any field change
     */
    private List<String> watchFields;

    // ==================== ON_FIELD_CHANGE specific ====================

    /**
     * Specific field to watch (for ON_FIELD_CHANGE)
     */
    private String fieldCode;

    /**
     * Trigger only when field changes from this value
     */
    private Object fromValue;

    /**
     * Trigger only when field changes to this value
     */
    private Object toValue;

    // ==================== ON_STATE_CHANGE specific ====================

    /**
     * State machine field code (for ON_STATE_CHANGE)
     */
    private String stateField;

    /**
     * Trigger on transition from these states
     */
    private List<String> fromStates;

    /**
     * Trigger on transition to these states
     */
    private List<String> toStates;

    // ==================== SCHEDULED specific ====================

    /**
     * Cron expression (for SCHEDULED)
     */
    private String cron;

    /**
     * Timezone for cron (default: system timezone)
     */
    private String timezone;

    /**
     * Maximum execution time in seconds
     */
    private Integer maxExecutionTime;

    // ==================== ON_BPM_EVENT specific ====================

    /**
     * BPM event types to listen for (for ON_BPM_EVENT).
     * e.g. ["process_started", "task_completed", "process_ended"]
     * If empty, triggers on all BPM events for the process.
     */
    private List<String> eventTypes;

    /**
     * Process key to filter (for ON_BPM_EVENT).
     * Stored in modelCode field of automation, this is for documentation.
     */
    private String processKey;

    // ==================== ON_INACTIVITY specific ====================

    /**
     * Inactivity threshold in hours (for ON_INACTIVITY).
     * e.g. 168 = 7 days. Triggers when a record has not been updated for this duration.
     */
    private Integer inactivityHours;

    /**
     * Optional state filter: only check records in these states (for ON_INACTIVITY).
     * e.g. ["open", "in_progress"] — skip CLOSED/ARCHIVED records.
     */
    private List<String> inactivityStates;

    /**
     * Optional: the field to check for last activity (for ON_INACTIVITY).
     * Defaults to "updated_at" if null.
     */
    private String inactivityField;

    // ==================== WEBHOOK specific ====================

    /**
     * Webhook secret for signature validation
     */
    private String secret;

    /**
     * Validation mode: SIGNATURE, TOKEN, NONE
     */
    private String validationMode;

    /**
     * Expected headers for validation
     */
    private List<String> expectedHeaders;
}
