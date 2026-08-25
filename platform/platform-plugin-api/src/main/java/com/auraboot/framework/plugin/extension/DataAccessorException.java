package com.auraboot.framework.plugin.extension;

import java.util.Objects;

/**
 * Coded exception raised at the public plugin data-access boundary.
 */
public final class DataAccessorException extends RuntimeException {

    private final DataAccessErrorCode code;

    public DataAccessorException(DataAccessErrorCode code, Throwable cause) {
        super("Plugin data access failed: " + Objects.requireNonNull(code, "code"), cause);
        this.code = code;
    }

    public DataAccessErrorCode code() {
        return code;
    }
}
