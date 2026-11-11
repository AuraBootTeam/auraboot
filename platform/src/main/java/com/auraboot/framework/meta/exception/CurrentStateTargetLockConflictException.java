package com.auraboot.framework.meta.exception;

import org.springframework.dao.CannotSerializeTransactionException;

/** A current-state intent lost its snapshot while locking its target, before any handler ran. */
public final class CurrentStateTargetLockConflictException extends CannotSerializeTransactionException {
    public CurrentStateTargetLockConflictException(CannotSerializeTransactionException cause) {
        super("Current-state target lock requires a fresh transaction snapshot", cause);
    }
}
