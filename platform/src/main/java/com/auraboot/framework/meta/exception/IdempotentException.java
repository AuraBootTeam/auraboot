package com.auraboot.framework.meta.exception;

/**
 * Exception thrown when a duplicate idempotent request is detected.
 *
 * <p>This occurs when:
 * <ul>
 *   <li>A request with the same idempotent key is already being processed (status=PROCESSING)</li>
 *   <li>No cached response is available for replay</li>
 * </ul>
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
public class IdempotentException extends RuntimeException {

    public IdempotentException(String message) {
        super(message);
    }

    public IdempotentException(String message, Throwable cause) {
        super(message, cause);
    }
}
