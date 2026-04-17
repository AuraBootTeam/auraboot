package com.auraboot.framework.bpm.chain;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Definition of a command chain — a series of commands orchestrated by SmartEngine.
 *
 * <p>Chain definitions can be stored in plugin config (processes.json) or created
 * programmatically. Each node in the chain maps to a SmartEngine ServiceTask that
 * executes a Command through the full 16-phase pipeline.
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
@Data
public class CommandChainDefinition {

    /**
     * Unique process key for SmartEngine deployment.
     */
    private String processKey;

    /**
     * Human-readable name.
     */
    private String name;

    /**
     * Execution mode: LOCAL_TX (single transaction) or SAGA (independent transactions + compensation).
     */
    private ChainMode chainMode = ChainMode.LOCAL_TX;

    /**
     * Ordered list of chain nodes (start/end events + service tasks + gateways).
     */
    private List<ChainNode> nodes;

    /**
     * Ordered list of edges connecting nodes.
     */
    private List<ChainEdge> edges;

    public enum ChainMode {
        /**
         * All commands execute within a single Spring @Transactional.
         * Any failure rolls back all changes. SmartEngine runs in CUSTOM (memory) mode.
         * Suitable for: same-database, millisecond-level operations.
         */
        LOCAL_TX,

        /**
         * Each command runs in its own transaction. SmartEngine persists process state.
         * Failure triggers compensation sub-process or manual intervention.
         * Suitable for: external API calls, long-running operations.
         */
        SAGA
    }

    @Data
    public static class ChainNode {
        private String id;
        private String type; // startEvent, endEvent, serviceTask, exclusiveGateway, etc.
        private ChainNodeData data;
    }

    @Data
    public static class ChainNodeData {
        private String label;

        /**
         * Service type: "command" for command execution.
         */
        private String serviceType;

        /**
         * The command code to execute (e.g., "pe:create_stock_out").
         */
        private String commandCode;

        /**
         * Operation type: CREATE, UPDATE, DELETE.
         */
        private String operationType;

        /**
         * Parameter template. Values can contain SpEL expressions referencing process variables.
         * E.g., "${orderId}", "${_step_create_stock_out_result.id}"
         */
        private Map<String, Object> params;

        /**
         * Target record ID expression (for UPDATE/DELETE). Resolved from process variables.
         */
        private String targetRecordId;

        /**
         * Failure handling strategy.
         */
        private OnFailStrategy onFail = OnFailStrategy.ABORT;

        /**
         * Condition expression (SpEL). If evaluates to false, this node is skipped.
         */
        private String condition;

        // === UserTask fields (APPROVAL mode) ===

        /** Assignee resolution rule type (SPECIFIC_USER, ROLE, STARTER, EXPRESSION, etc.) */
        private String assigneeRuleType;

        /** Assignee resolution rule config (userIds, roleCode, expression, etc.) */
        private Map<String, Object> assigneeRuleConfig;

        /** Multi-approver strategy: ANY (first wins) or ALL (unanimous). Default: ANY */
        private String assigneeStrategy;

        /** DSL form reference for the approval form (pageCode or pid) */
        private String formRef;

        /** Approval task title template (supports ${variable} interpolation) */
        private String taskTitle;

        /** Deadline duration in ISO-8601 (e.g., "pt48h" for 48 hours) */
        private String deadline;

        /** Optional rejection callback command config */
        private Map<String, Object> onReject;

        /** Compensation command code. Executed in reverse order if a downstream step fails (SAGA mode only). */
        private String compensationCommand;
    }

    @Data
    public static class ChainEdge {
        private String id;
        private String source;
        private String target;
        private String label;
        private ChainEdgeCondition condition;
    }

    @Data
    public static class ChainEdgeCondition {
        private String type; // "expression"
        private String content; // SpEL expression
    }

    public enum OnFailStrategy {
        /** Abort the entire chain (default). */
        ABORT,
        /** Skip this step and continue with a warning. */
        SKIP_AND_WARN,
        /** Retry the step (up to configured max retries). */
        RETRY
    }
}
