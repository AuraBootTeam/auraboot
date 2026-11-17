package com.auraboot.framework.notification.sourcebinding;

import java.nio.file.Path;
import java.util.Map;

/** Explicit workspace boundary for opt-in native fixtures; no workstation-specific fallback. */
final class NativeProcurementWorkspace {
    private NativeProcurementWorkspace() {}

    static Path root() {
        return root(System.getenv());
    }

    static String runtime() {
        return runtime(System.getenv());
    }

    static String runtime(Map<String, String> environment) {
        String value = environment.getOrDefault("PROCUREMENT_NATIVE_RUNTIME", "procurement-web-stack-20261010");
        if (!java.util.Set.of("procurement-web-stack-20261010", "procurement-main-validation", "procurement-scope-gaps").contains(value)) {
            throw new IllegalStateException("PROCUREMENT_NATIVE_RUNTIME_NOT_OWNED");
        }
        return value;
    }

    record Profile(String database, int redisDatabase) {}

    static Profile profile() { return profile(System.getenv()); }

    static Profile profile(Map<String, String> environment) {
        return switch (runtime(environment)) {
            case "procurement-scope-gaps" -> new Profile("enterprise_1", 7);
            case "procurement-web-stack-20261010", "procurement-main-validation" -> new Profile("enterprise_22", 13);
            default -> throw new IllegalStateException("PROCUREMENT_NATIVE_RUNTIME_NOT_OWNED");
        };
    }

    static Path root(Map<String, String> environment) {
        runtime(environment);
        String value = environment.get("AURA_WORKSPACE_ROOT");
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("AURA_WORKSPACE_ROOT_REQUIRED");
        }
        Path root = Path.of(value);
        if (!root.isAbsolute()) {
            throw new IllegalStateException("AURA_WORKSPACE_ROOT_MUST_BE_ABSOLUTE");
        }
        return root.normalize();
    }
}
