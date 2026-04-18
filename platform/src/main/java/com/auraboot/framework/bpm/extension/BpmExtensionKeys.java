package com.auraboot.framework.bpm.extension;

/**
 * Canonical key names used inside <smart:properties> for AuraBoot business config.
 * All keys are prefixed with "aura." to avoid collision with SmartEngine's own
 * properties (e.g., task1InParam1).
 */
public final class BpmExtensionKeys {

    private BpmExtensionKeys() {}

    /** Process-level: WithdrawPolicy code (strict | loose | none). */
    public static final String WITHDRAW_POLICY = "aura.withdrawPolicy";

    /** Process-level: CcPolicy code (initiator | assignee | all). */
    public static final String CC_POLICY = "aura.ccPolicy";

    /** Node-level: form key reference (resolved by form repository). */
    public static final String FORM_KEY = "aura.formKey";

    /** Node-level: required permission codes (JSON array string). */
    public static final String REQUIRED_PERMISSIONS = "aura.requiredPermissions";

    /** Node-level: optional override of the process-level CcPolicy. */
    public static final String CC_POLICY_OVERRIDE = "aura.ccPolicyOverride";

    /**
     * Node-level: serialized JSON array of designer hook descriptors (GAP-254).
     *
     * <p>Each entry: {@code {hookType, actionType, executionOrder, failStrategy,
     * async, enabled, hookConfig}}. Persisted into {@code ab_bpm_node_hook} at
     * deploy time; this XML form survives export/import round-trips so audit and
     * cross-environment migration retain designer state.
     */
    public static final String NODE_HOOKS = "aura.hooks";

    /**
     * Node-level (callActivity): serialized JSON object carrying parent↔child
     * variable mapping configuration.
     *
     * <p>Shape:
     * <pre>{@code
     *   {
     *     "inputs":  {"parentVar":"childVar", ...},   // propagate parent → child at child PROCESS_START
     *     "outputs": {"childVar":"parentVar", ...}    // propagate child → parent at callActivity ACTIVITY_END
     *   }
     * }</pre>
     *
     * <p>SmartEngine's {@code CallActivityBehavior} intentionally isolates the
     * parent/child request maps (only {@code tenantId} is forwarded). This
     * key carries the UI-configured mapping into the deployed BPMN, where
     * {@code AuraCallActivityListener} consumes it at runtime to bridge the
     * isolation. Direct {@code <smart:in>/<smart:out>} child elements of
     * {@code <callActivity>} are NOT supported by SmartEngine's BPMN parser
     * (see GAP-250); we piggyback on the generic {@code <smart:properties>}
     * extension mechanism used by other aura.* keys instead.
     */
    public static final String CALL_MAPPINGS = "aura.callMappings";
}
