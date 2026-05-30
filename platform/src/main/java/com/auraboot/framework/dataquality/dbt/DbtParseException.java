package com.auraboot.framework.dataquality.dbt;

/**
 * Thrown by {@link DbtManifestParser} when a dbt artifact cannot be parsed.
 *
 * <p>Typical causes: malformed JSON, missing required fields, or a format
 * incompatible with the expected dbt artifact schema version.
 */
public class DbtParseException extends RuntimeException {

    public DbtParseException(String message) {
        super(message);
    }

    public DbtParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
