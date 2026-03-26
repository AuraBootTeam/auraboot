package com.auraboot.framework.bpm.chain;

import com.auraboot.framework.bpm.chain.CommandChainDefinition.ChainMode;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Result of a command chain execution.
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
@Data
@Builder
public class CommandChainResult {

    private boolean success;
    private String chainExecutionId;
    private String processInstanceId;
    private String processKey;
    private String businessKey;
    private ChainMode chainMode;

    /**
     * Step-level results. Key: nodeId, Value: step result map.
     * Each step result contains: success, data, recordId, skipped, error.
     */
    private Map<String, Object> stepResults;

    /**
     * ID of the failed node (if any).
     */
    private String failedNodeId;

    /**
     * Command code of the failed step (if any).
     */
    private String failedCommandCode;

    /**
     * Error message (if chain failed).
     */
    private String errorMessage;

    /**
     * Total chain execution time in milliseconds.
     */
    private long durationMs;

    /**
     * Chain execution status for APPROVAL mode: RUNNING, SUSPENDED, COMPLETED, FAILED.
     */
    private String status;

    /**
     * PID of created approval task (when chain is SUSPENDED at a UserTask).
     */
    private String approvalTaskPid;

    /**
     * PID of the persistent chain execution record (APPROVAL mode).
     */
    private String chainExecutionPid;

    /**
     * Saga execution ID (only set in SAGA mode). Used to query saga status and retry.
     */
    private String sagaExecutionId;
}
