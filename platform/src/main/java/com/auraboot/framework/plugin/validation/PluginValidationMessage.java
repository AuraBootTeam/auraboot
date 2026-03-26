package com.auraboot.framework.plugin.validation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single validation message from plugin pre-flight checks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginValidationMessage {

    /** Rule code, e.g., "S-REF-MODEL", "G-CYCLE". */
    private String code;

    /** Category: "structural", "semantic", "governance". */
    private String category;

    /** Severity: "error", "warning", "info". */
    private String severity;

    /** Human-readable message. */
    private String message;

    /** JSON-path-like location, e.g., "commands[3].modelCode". */
    private String path;

    /** Optional fix suggestion. */
    private String suggestion;

    // ==================== Factory methods ====================

    public static PluginValidationMessage error(String code, String category, String message) {
        return PluginValidationMessage.builder()
                .code(code).category(category).severity("error").message(message).build();
    }

    public static PluginValidationMessage error(String code, String category, String path, String message) {
        return PluginValidationMessage.builder()
                .code(code).category(category).severity("error").path(path).message(message).build();
    }

    public static PluginValidationMessage warning(String code, String category, String message) {
        return PluginValidationMessage.builder()
                .code(code).category(category).severity("warning").message(message).build();
    }

    public static PluginValidationMessage warning(String code, String category, String path, String message) {
        return PluginValidationMessage.builder()
                .code(code).category(category).severity("warning").path(path).message(message).build();
    }

    public static PluginValidationMessage info(String code, String category, String message) {
        return PluginValidationMessage.builder()
                .code(code).category(category).severity("info").message(message).build();
    }

    public static PluginValidationMessage info(String code, String category, String message, String path) {
        return PluginValidationMessage.builder()
                .code(code).category(category).severity("info").message(message).path(path).build();
    }

    public boolean isError() {
        return "error".equals(severity);
    }

    public boolean isWarning() {
        return "warning".equals(severity);
    }
}
