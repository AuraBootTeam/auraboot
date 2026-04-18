package com.auraboot.framework.meta.dto;

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
     * Optional target record ID (for UPDATE/DELETE)
     */
    private String targetRecordId;

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
}
