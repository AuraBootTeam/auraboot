package com.auraboot.framework.meta.exception;

/** Raised when an append-only model would be mutated after its allowed create boundary. */
public final class ImmutableModelMutationException extends MetaServiceException {

    public ImmutableModelMutationException(String message) {
        super(message);
    }
}
