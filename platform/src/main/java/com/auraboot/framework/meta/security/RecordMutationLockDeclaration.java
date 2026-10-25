package com.auraboot.framework.meta.security;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Portable shape validation for an owner-claimed aggregate mutation lock. */
public final class RecordMutationLockDeclaration {
    private RecordMutationLockDeclaration() {}
    public static void validate(Object raw) {
        if (raw == null) return;
        if (!(raw instanceof Map<?, ?> value) || !value.keySet().equals(Set.of("field", "commands"))
                || !(value.get("field") instanceof String field) || field.isBlank()
                || !(value.get("commands") instanceof Map<?, ?> commands)
                || !commands.keySet().equals(Set.of("create", "update", "delete"))) throw invalid();
        SqlSafetyUtils.requireIdentifier(field, "mutation lock field");
        for (Object writers : commands.values()) {
            if (!(writers instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String s)
                    || s.isBlank() || !s.equals(s.trim())) || list.size() != Set.copyOf(list).size()) throw invalid();
        }
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid recordMutationLock declaration");
    }
}
