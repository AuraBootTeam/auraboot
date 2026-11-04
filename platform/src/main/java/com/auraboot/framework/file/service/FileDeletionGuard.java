package com.auraboot.framework.file.service;

/**
 * Domain-owned references can veto deletion of an uploaded platform file.
 *
 * <p>The host calls every guard after tenant, ownership and retention checks while
 * holding the file row lock, before the deletion fence or physical deletion.
 * Attaching domains must acquire that same file lock before creating a reference.
 * Guards must inspect current committed references without locking parent records,
 * performing external I/O or changing business data. Throw on a live reference or
 * failed lookup; an unavailable guard must never silently permit deletion.</p>
 */
@FunctionalInterface
public interface FileDeletionGuard {
    void requireDeletable(Long tenantId, String filePid);
}
