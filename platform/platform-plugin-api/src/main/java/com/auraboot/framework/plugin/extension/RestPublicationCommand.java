package com.auraboot.framework.plugin.extension;

import java.util.Set;

/** One native create command allowed through a published, credential-free POST. */
public record RestPublicationCommand(RestPublication publication, String code, String permission,
                                     Set<String> inputFields) {
    public RestPublicationCommand {
        inputFields = Set.copyOf(inputFields);
        if (publication == null || code == null || !code.matches("[a-z][a-z0-9_]*:[a-z][a-z0-9_]*")
                || permission == null || permission.isBlank() || inputFields.isEmpty()) {
            throw new IllegalArgumentException("Invalid publication command");
        }
    }
}
