package com.auraboot.framework.semantic.exception;

import java.util.List;

/**
 * Raised when a {@code *.semantic.yml} file fails JSON Schema validation.
 *
 * <p>Error code: {@code SEMANTIC_YAML_INVALID}. Maps to HTTP 400 in
 * {@code SemanticController}.
 */
public class SemanticYamlInvalidException extends RuntimeException {

    public static final String ERROR_CODE = "SEMANTIC_YAML_INVALID";

    private final List<String> schemaErrors;

    public SemanticYamlInvalidException(String message, List<String> schemaErrors) {
        super(message);
        this.schemaErrors = schemaErrors;
    }

    public SemanticYamlInvalidException(String message, List<String> schemaErrors, Throwable cause) {
        super(message, cause);
        this.schemaErrors = schemaErrors;
    }

    public List<String> getSchemaErrors() {
        return schemaErrors;
    }

    public String getErrorCode() {
        return ERROR_CODE;
    }
}
