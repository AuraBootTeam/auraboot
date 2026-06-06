package com.auraboot.framework.plugin.validation;

import java.util.List;

/**
 * Thrown by {@link PageSchemaImportGate} when a plugin declares one or more page
 * schemas that violate the v4 import contract (blocking-severity findings).
 * <p>
 * This is a hard-fail: the import is aborted before any resource is persisted
 * (DSL V4 Phase B). Advisory findings (label/i18n/field-ref/...) do NOT raise this.
 */
public class PageSchemaImportException extends RuntimeException {

    private final transient List<PluginValidationMessage> findings;

    public PageSchemaImportException(String message, List<PluginValidationMessage> findings) {
        super(message);
        this.findings = findings;
    }

    public List<PluginValidationMessage> getFindings() {
        return findings;
    }
}
