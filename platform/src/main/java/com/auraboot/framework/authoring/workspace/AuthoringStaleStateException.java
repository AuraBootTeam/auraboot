package com.auraboot.framework.authoring.workspace;

import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.CONFLICT;

/** Conflict whose stale-state transition must commit before the response is returned. */
public class AuthoringStaleStateException extends ResponseStatusException {

    public AuthoringStaleStateException(String reason) {
        super(CONFLICT, reason);
    }
}
