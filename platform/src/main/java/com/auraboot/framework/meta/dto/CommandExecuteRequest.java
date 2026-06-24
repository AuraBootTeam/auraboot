package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

/**
 * Command Execute Request
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Data
public class CommandExecuteRequest {

    /**
     * Command payload data
     */
    private Map<String, Object> payload;

    /**
     * Client request ID for idempotency
     */
    private String clientRequestId;

    /**
     * Optional operation type hint (CREATE/UPDATE/DELETE)
     */
    private String operationType;

    /**
     * Internal pipeline target for UPDATE/DELETE.
     * Public JSON callers must use targetRecordPid.
     */
    @JsonIgnore
    private String targetRecordId;

    /**
     * Public target record pid for UPDATE/DELETE.
     */
    @JsonProperty("targetRecordPid")
    private String targetRecordPid;

    /**
     * Optional execution-source metadata for audit logs.
     * This is not merged into the business payload seen by command handlers.
     */
    private Map<String, Object> auditContext;

    /**
     * Expected row version for optimistic locking (optional)
     */
    private Integer expectedVersion;

    /**
     * When true, run the full pipeline but force the wrapping transaction
     * to roll back at the end — producing a side-effect-free simulation
     * of the write. Used by Shadow Mode (learning-loop.md §6) to exercise
     * dsl.command drafts without mutating DB state. External side effects
     * (BPM triggers, webhooks) are skipped in this mode.
     */
    private boolean dryRun;

    @JsonIgnore
    public String getTargetRecordId() {
        return targetRecordId != null ? targetRecordId : targetRecordPid;
    }

    @JsonIgnore
    public void setTargetRecordId(String targetRecordId) {
        this.targetRecordId = targetRecordId;
        this.targetRecordPid = targetRecordId;
    }

    @JsonProperty("targetRecordPid")
    public String getTargetRecordPid() {
        return targetRecordPid;
    }

    @JsonProperty("targetRecordPid")
    public void setTargetRecordPid(String targetRecordPid) {
        this.targetRecordPid = targetRecordPid;
        this.targetRecordId = targetRecordPid;
    }
}
