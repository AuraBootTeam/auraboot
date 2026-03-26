package com.auraboot.framework.bpm.engine.exception;

/**
 * Thrown when a BPM engine operation fails.
 * Wraps engine-specific exceptions so callers only depend on the abstraction.
 */
public class BpmEngineException extends RuntimeException {

    private final String engineType;

    public BpmEngineException(String engineType, String message) {
        super(message);
        this.engineType = engineType;
    }

    public BpmEngineException(String engineType, String message, Throwable cause) {
        super(message, cause);
        this.engineType = engineType;
    }

    public String getEngineType() {
        return engineType;
    }
}
