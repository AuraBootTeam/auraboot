package com.auraboot.framework.meta.exception;

import org.springframework.security.access.AccessDeniedException;

/**
 * The model's mutation policy refuses this write: the model accepts the operation only through
 * its authorized command, and this request is not one. A refusal of the request, not a server
 * fault, so it reaches the client as a 403 that names the policy instead of an opaque 500.
 */
public final class ModelMutationDeniedException extends AccessDeniedException {

    public ModelMutationDeniedException(String message) {
        super("MODEL_MUTATION_DENIED: " + message);
    }
}
