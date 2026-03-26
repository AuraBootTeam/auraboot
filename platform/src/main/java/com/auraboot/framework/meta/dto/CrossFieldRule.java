package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Top-level cross-field validation rule.
 * Defined in model JSON (rules array) and command JSON (ruleOverrides).
 *
 * Structure: { id, when?, assert, message, severity?, targetField?, dependsOn? }
 */
@Data
public class CrossFieldRule {

    /** Unique rule ID — used for command-level override matching */
    private String id;

    /** Optional condition — rule is skipped if when evaluates to false */
    private RuleCondition when;

    /** The validation assertion (declarative or expression mode) */
    @JsonProperty("assert")
    private RuleAssert ruleAssert;

    /** Error/warning message — supports $i18n:key and {fieldCode} placeholders */
    private String message;

    /** "error" (default, blocks submit) or "warning" (shows warning, allows submit) */
    private String severity;

    /** For expression mode: which form field to display the error under */
    private String targetField;

    /** Required for expression mode: explicit field dependencies */
    private List<String> dependsOn;

    public String getSeverity() {
        return severity != null ? severity : "error";
    }
}
