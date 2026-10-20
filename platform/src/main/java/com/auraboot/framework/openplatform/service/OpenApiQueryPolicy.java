package com.auraboot.framework.openplatform.service;

import java.util.Set;

/** Query output authority supplements, and never replaces, each source resource's own grant. */
public record OpenApiQueryPolicy(Set<String> readableFields) {
    public OpenApiQueryPolicy { readableFields = Set.copyOf(readableFields == null ? Set.of() : readableFields); }
    public void validate(OpenApiQueryContract contract) {
        if (readableFields.isEmpty() || !contract.fields().keySet().containsAll(readableFields))
            throw new IllegalArgumentException("Query grant must narrow published output fields");
    }
}
