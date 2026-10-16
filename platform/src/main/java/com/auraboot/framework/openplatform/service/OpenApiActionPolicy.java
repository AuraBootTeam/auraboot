package com.auraboot.framework.openplatform.service;

import java.util.Set;

/** Business transitions explicitly grant their complete controlled field effects, including read-only state. */
public record OpenApiActionPolicy(boolean execute, Set<String> effectFields) {
    public OpenApiActionPolicy { effectFields = Set.copyOf(effectFields == null ? Set.of() : effectFields); }
    public void validate(OpenApiActionContract contract) {
        if (!contract.effectFields().containsAll(effectFields)) throw new IllegalArgumentException("Business grant exceeds declared effects");
    }
    public boolean permits(OpenApiActionContract contract) { return execute && effectFields.containsAll(contract.effectFields()); }
}
