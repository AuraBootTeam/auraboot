package com.auraboot.framework.plugin.extension;

import java.util.Set;

/** A fixed command and its public input and result contract. */
public record RestPublicationCommand(RestPublication publication, String code, String permission,
                                     Set<String> inputFields, String keyHeader, Set<String> resultFields) {
    public RestPublicationCommand(RestPublication publication, String code, String permission, Set<String> inputFields) {
        this(publication, code, permission, inputFields, null, Set.of("recordPid"));
    }

    public RestPublicationCommand {
        inputFields = Set.copyOf(inputFields);
        resultFields = Set.copyOf(resultFields);
        if (publication == null || code == null || !code.matches("[a-z][a-z0-9_]*:[a-z][a-z0-9_]*")
                || permission == null || permission.isBlank() || inputFields.isEmpty() || resultFields.isEmpty()
                || (keyHeader != null && !keyHeader.matches("X-[a-zA-Z0-9-]{1,62}"))) {
            throw new IllegalArgumentException("Invalid publication command");
        }
    }
}
