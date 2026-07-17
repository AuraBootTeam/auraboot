package com.auraboot.framework.bpm.rule;

/**
 * Message-key convention for Drools rule outcomes.
 *
 * <p>A rule reports a bare reason code in its {@code _ruleResult} (e.g.
 * {@code annual_leave_insufficient}); the user-facing text lives in the plugin i18n
 * catalog under {@code error.<ruleCode>.<reason>}. Callers throw the {@code $i18n:}
 * form so {@code GlobalExceptionHandler} resolves it against the catalog instead of
 * leaking the raw reason code into the UI.
 *
 * @since 7.3.0
 */
public final class RuleReasonMessages {

    private static final String I18N_PREFIX = "$i18n:";
    private static final String REASON_KEY_PREFIX = "error.";

    private RuleReasonMessages() {
    }

    /**
     * Build the i18n message key for a rule-reported reason, falling back to
     * {@code fallbackKey} when the rule reported no reason.
     */
    public static String reasonKey(String ruleCode, Object reason, String fallbackKey) {
        String raw = reason != null ? reason.toString().trim() : "";
        if (raw.isEmpty()) {
            return i18nKey(fallbackKey);
        }
        if (raw.startsWith(I18N_PREFIX)) {
            return raw;
        }
        if (ruleCode == null || ruleCode.isBlank()) {
            return i18nKey(raw);
        }
        return I18N_PREFIX + REASON_KEY_PREFIX + ruleCode + "." + raw;
    }

    /** Prefix a plain catalog key with {@code $i18n:} so the handler localizes it. */
    public static String i18nKey(String key) {
        if (key == null || key.isBlank() || key.startsWith(I18N_PREFIX)) {
            return key;
        }
        return I18N_PREFIX + key;
    }
}
