package com.auraboot.framework.plugin.extension;

/**
 * Stable, transport-neutral failure codes exposed by {@link DataAccessor}.
 *
 * <p>Plugin control flow must branch on these codes rather than host exception
 * types or human-readable exception messages.
 */
public enum DataAccessErrorCode {
    PERMISSION_DENIED
}
