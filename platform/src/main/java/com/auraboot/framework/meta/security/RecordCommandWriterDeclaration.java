package com.auraboot.framework.meta.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates the portable record ownership declaration before model persistence. */
public final class RecordCommandWriterDeclaration {
    private RecordCommandWriterDeclaration() {}

    public static void validate(Object raw) {
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?> declaration)
                || !declaration.keySet().equals(Set.of("field", "commands"))
                || !(declaration.get("field") instanceof String code) || code.isBlank()
                || !(declaration.get("commands") instanceof Map<?, ?> commands)
                || !commands.keySet().equals(Set.of("create", "update", "delete"))) {
            throw new IllegalArgumentException("Invalid recordCommandWriters declaration");
        }
        SqlSafetyUtils.requireIdentifier(code, "record writer marker field");
        for (Object writers : commands.values()) {
            if (!(writers instanceof List<?> list)
                    || list.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank() || !s.equals(s.trim()))) {
                throw new IllegalArgumentException("Invalid recordCommandWriters command list");
            }
        }
    }
}
