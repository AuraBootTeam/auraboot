package com.auraboot.framework.agent.service;

/**
 * Thrown by {@link UserSoulProfileEditor} when the caller attempts to mutate
 * a profile whose only remaining rows are {@code archived} (GDPR-forgotten).
 *
 * <p>Distinct from the plain {@link IllegalStateException} surface because the
 * controller layer maps archived state to HTTP 410 Gone (the resource is
 * permanently tombstoned) whereas SUPERSEDED-only state maps to HTTP 409
 * Conflict (the resource exists but its current state forbids mutation).
 */
public class UserSoulProfileArchivedException extends IllegalStateException {

    public UserSoulProfileArchivedException(String message) {
        super(message);
    }
}
