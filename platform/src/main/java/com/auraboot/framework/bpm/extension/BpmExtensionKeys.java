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
}
