package com.auraboot.framework.meta.constant;

/**
 * Constants for the 20-stage command execution pipeline.
 * Each constant represents a sequential phase in {@code CommandExecutorImpl}.
 *
 * <p>Stages are numbered 1-20 in execution order. After-commit phases
 * (DOMAIN_EVENT, API_CALL, WEBHOOK) use numbers 21-23 to indicate they
 * run outside the main transaction boundary.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 * @see com.auraboot.framework.meta.annotation.CommandPhase
 */
public final class CommandStage {

    private CommandStage() {
        // prevent instantiation
    }

    /** Stage 1: Load command definition from database. */
    public static final int LOAD = 1;

    /** Stage 2: Validate payload schema (basic structure check). */
    public static final int SCHEMA_VALIDATE = 2;

    /** Stage 3: Idempotency check — return cached result for duplicate requests. */
    public static final int IDEMPOTENCY_CHECK = 3;

    /** Stage 4: Entitlement check — verify plugin/feature license. */
    public static final int ENTITLEMENT_CHECK = 4;

    /** Stage 5: Separation of Duties (SoD) enforcement. */
    public static final int SOD_CHECK = 5;

    /** Stage 6: State check — validate state transitions via state graph. */
    public static final int STATE_CHECK = 6;

    /** Stage 7: Assert — evaluate preconditions, SpEL assertions, and field validation rules. */
    public static final int ASSERT = 7;

    /** Stage 8: Pre-invariant — evaluate business invariants before mutation. */
    public static final int PRE_INVARIANT = 8;

    /** Stage 9: Cross-field validation — evaluate cross-field dependency rules. */
    public static final int CROSS_FIELD_VALIDATION = 9;

    /** Stage 10: Auto-set — inject auto-generated values (codes, timestamps, user IDs). */
    public static final int AUTO_SET = 10;

    /** Stage 11: Field map — map payload fields to database columns, execute cascade delete. */
    public static final int FIELD_MAP = 11;

    /** Stage 12: Computed fields — calculate SpEL formula fields. */
    public static final int COMPUTED_FIELDS = 12;

    /** Stage 13: Change tracking — record field-level changes for audit trail. */
    public static final int CHANGE_TRACKING = 13;

    /** Stage 14: Handler — execute Spring bean handlers and plugin command handlers. */
    public static final int HANDLER = 14;

    /** Stage 15: Consistency check — validate cross-document constraints. */
    public static final int CONSISTENCY_CHECK = 15;

    /** Stage 16: Side effect — create/update related records based on conditions. */
    public static final int SIDE_EFFECT = 16;

    /** Stage 17: Roll-up — recalculate parent summary fields when child records change. */
    public static final int ROLL_UP = 17;

    /** Stage 18: Post-action — create child records or other post-processing. */
    public static final int POST_ACTION = 18;

    /** Stage 19: Effect — execute effect binding rules, audit log, event sourcing. */
    public static final int EFFECT = 19;

    /** Stage 20: Post-invariant — evaluate business invariants after mutation. */
    public static final int POST_INVARIANT = 20;

    // === After-commit phases (outside transaction boundary) ===

    /** After-commit: Publish domain events for in-process listeners. */
    public static final int DOMAIN_EVENT = 21;

    /** After-commit: Execute external API calls. */
    public static final int API_CALL = 22;

    /** After-commit: Dispatch webhooks to external systems. */
    public static final int WEBHOOK = 23;

    /** After-commit: Governance snapshot capture. */
    public static final int GOVERNANCE_SNAPSHOT = 24;

    /** Total number of in-transaction stages. */
    public static final int TOTAL_TRANSACTIONAL_STAGES = 20;

    /**
     * Returns a human-readable name for the given stage number.
     */
    public static String nameOf(int stage) {
        return switch (stage) {
            case LOAD -> "load";
            case SCHEMA_VALIDATE -> "schema_validate";
            case IDEMPOTENCY_CHECK -> "idempotency_check";
            case ENTITLEMENT_CHECK -> "entitlement_check";
            case SOD_CHECK -> "sod_check";
            case STATE_CHECK -> "state_check";
            case ASSERT -> "assert";
            case PRE_INVARIANT -> "pre_invariant";
            case CROSS_FIELD_VALIDATION -> "cross_field_validation";
            case AUTO_SET -> "auto_set";
            case FIELD_MAP -> "field_map";
            case COMPUTED_FIELDS -> "computed_fields";
            case CHANGE_TRACKING -> "change_tracking";
            case HANDLER -> "handler";
            case CONSISTENCY_CHECK -> "consistency_check";
            case SIDE_EFFECT -> "side_effect";
            case ROLL_UP -> "roll_up";
            case POST_ACTION -> "post_action";
            case EFFECT -> "effect";
            case POST_INVARIANT -> "post_invariant";
            case DOMAIN_EVENT -> "domain_event";
            case API_CALL -> "api_call";
            case WEBHOOK -> "webhook";
            case GOVERNANCE_SNAPSHOT -> "governance_snapshot";
            default -> "UNKNOWN(" + stage + ")";
        };
    }
}
