package com.auraboot.framework.agent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Unified Capability View — merges Human View + Agent View for any Command/Query.
 * This is the "business capability contract" that both humans and agents consume.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityView {

    /** Capability code (e.g., "crm:create_lead" or "nq:crm_pipeline_summary"). */
    private String code;

    /** Capability type: COMMAND or QUERY. */
    private String type;

    /** Source model code. */
    private String modelCode;

    // ==================== Human View ====================

    /** Display name for UI. */
    private String displayName;

    /** Command type: CREATE, UPDATE, DELETE, STATE_TRANSITION, QUERY, etc. */
    private String commandType;

    /** Required permission codes. */
    private List<String> permissions;

    // ==================== Agent View ====================

    /** Purpose: what this capability does (from agent_hint or derived). */
    private String purpose;

    /** When to use this capability. */
    private String whenToUse;

    /** When NOT to use this capability. */
    private String whenNotToUse;

    /** Input contract: JSON Schema for parameters. */
    private Map<String, Object> inputContract;

    /** Output contract: expected return structure. */
    private Map<String, Object> outputContract;

    /** Preconditions that must be met before execution. */
    private List<String> preconditions;

    /** Side effects that will occur. */
    private List<String> sideEffects;

    /** Risk level: L0 (safe read) through L4 (irreversible). */
    private String riskLevel;

    /** Confirmation policy derived from risk level. */
    private String confirmationPolicy;

    /** Whether the operation is idempotent. */
    private Boolean idempotent;

    /** Whether the operation is reversible. */
    private Boolean reversible;

    /** Example input for reference. */
    private Map<String, Object> exampleInput;

    /** Capabilities that compose well with this one (upstream/downstream). */
    private List<String> composableWith;

    // ==================== Interaction Layer ====================

    /** Supported interaction modes for this capability. */
    private List<InteractionMode> interactionModes;

    /**
     * Describes how a capability can be consumed through a specific channel.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InteractionMode {
        /** Channel: UI, API, AGENT, WORKFLOW, AUDIT. */
        private String channel;
        /** Whether this channel is available for this capability. */
        private boolean available;
        /** Channel-specific detail (e.g., API path, page key, tool code). */
        private String reference;
    }
}
