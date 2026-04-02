package com.auraboot.framework.agent.dto;

import com.auraboot.framework.meta.dto.FieldChange;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ACP ActionEngine: represents a single business action executed by an agent.
 * Each Action records what business impact the AI caused — not just which tool was called.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionRecord {
    private String pid;
    private Long tenantId;

    // execution context
    private String runId;
    private Integer stepIndex;
    private Integer toolCallIndex;

    // business semantics
    private String actionCode;          // {model_code}.{operation_type}
    private String actionType;          // read | create | update | delete | transition | bulk_update | bulk_delete
    private String transactionScope;    // single_record | bulk_records | cross_model | read_only | external_call | composite
    private String sideEffectType;      // state_change | workflow_transition | artifact_generation | external_effect | human_notification
    private String intentSummary;
    private String businessDomain;
    private String businessOperation;

    // target
    private String targetModel;
    private String targetRecordId;
    private List<String> targetRecordIds;
    private int affectedCount;

    // change snapshot
    private Map<String, Object> beforeSnapshot;
    private Map<String, Object> afterSnapshot;
    private List<FieldChange> fieldChanges;

    // associated command
    private String commandCode;
    private String commandResult;       // success | failed | skipped

    // risk and governance
    private String riskLevel;           // L0-L4 (actual risk at execution time)
    private String estimatedRisk;       // L0-L4 (risk predicted at plan/grounding time)
    private boolean riskDeviation;      // true if actual_risk > estimated_risk
    private String approvalId;
    private String reversalMode;        // auto_undo | auto_compensate | manual_compensate | notify_only | irreversible

    // lifecycle
    private String actionStatus;        // executing | success | failed
    private String errorMessage;

    // cost
    private BigDecimal costUsd;
    private int tokenUsage;

    // audit
    private String actorType;           // agent | human
    private String actorId;
}
