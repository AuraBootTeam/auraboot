package com.auraboot.framework.agent.service;

/**
 * Canonical field path identifiers for the User Soul Profile (see plan §4, §5.3).
 *
 * <p>Used as keys in the {@code edited_fields} JSONB column — where the user
 * may pin, hide, or override a field — and as prompt-section key references
 * for the Reader (§5.5). Centralised as constants to forbid magic strings
 * (project code-quality red-line).
 */
public final class UserSoulProfileFieldPaths {

    private UserSoulProfileFieldPaths() {}

    // ---- Top-level fields in the rendered profile JSON --------------------
    public static final String PERSONA = "persona";
    public static final String BOUNDARIES = "boundaries";
    public static final String LANGUAGE = "language";
    public static final String HABITS_RECURRING = "habits.recurring_actions";
    public static final String EXPERTISE_DOMAINS = "expertise.domains";

    // ---- Preference sub-fields -------------------------------------------
    public static final String PREF_COMMUNICATION = "preferences.communication_style";
    public static final String PREF_DOMAIN_VOCAB = "preferences.domain_vocabulary";
    public static final String PREF_WORKING_HOURS = "preferences.working_hours";

    // ---- edited_fields marker tokens --------------------------------------
    /**
     * Sentinel value stored in {@code edited_fields[path]} when the user has
     * hidden the field entirely. Hide wins over override wins over raw.
     */
    public static final String EDIT_HIDDEN = "hidden";

    /**
     * JSON object key under {@code edited_fields[path]} holding the user's
     * override text. Wins over the derived {@code field.text} but loses to
     * {@link #EDIT_HIDDEN}.
     */
    public static final String EDIT_OVERRIDE_TEXT = "override_text";

    /** JSON object key marking a field as user-pinned (preserved across re-derivations). */
    public static final String EDIT_PINNED = "pinned";
}
