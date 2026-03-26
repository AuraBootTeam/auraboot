package com.auraboot.framework.exception;

/**
 * Artifact验证异常
 *
 * 当Release Artifact验证失败时抛出此异常
 *
 * @author AuraBoot Team
 * @since 3.3.1
 */
public class ArtifactValidationException extends RuntimeException {

    public ArtifactValidationException(String message) {
        super(message);
    }

    public ArtifactValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
