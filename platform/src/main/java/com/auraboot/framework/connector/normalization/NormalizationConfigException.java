package com.auraboot.framework.connector.normalization;

/**
 * Thrown by {@link NormalizeYamlParser} when a {@code *.normalize.yml} document
 * is structurally invalid, contains an unrecognised rule type, or is missing
 * required fields.
 *
 * <p>Pattern mirrors {@code com.auraboot.framework.chatbi.v2.compiler.TokenCompileException}:
 * a stable {@code code} for programmatic handling plus a human-readable message.
 * Callers should translate the code to an appropriate HTTP 400 or configuration-error
 * response rather than surfacing the raw exception message.
 *
 * <p>Well-known codes:
 * <ul>
 *   <li>{@code MISSING_FIELD} — a required top-level or rule-level field is absent</li>
 *   <li>{@code UNKNOWN_RULE_TYPE} — the {@code type} value is not a valid
 *       {@link NormalizationRuleType}</li>
 *   <li>{@code PARSE_ERROR} — the YAML document is syntactically invalid</li>
 *   <li>{@code MISSING_RULE_PARAMS} — a rule type that requires params has none</li>
 * </ul>
 *
 * @since 5.3.0
 */
public class NormalizationConfigException extends RuntimeException {

    private final String code;

    public NormalizationConfigException(String code, String message) {
        super(message);
        this.code = code;
    }

    public NormalizationConfigException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** Stable identifier for programmatic handling (e.g. mapping to i18n key). */
    public String code() {
        return code;
    }
}
