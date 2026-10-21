package com.auraboot.framework.notification.sourcebinding;

import java.nio.file.Path;
import java.util.Map;

/** Explicit workspace boundary for opt-in native fixtures; no workstation-specific fallback. */
final class NativeProcurementWorkspace {
    private NativeProcurementWorkspace() {}

    static Path root() {
        return root(System.getenv());
    }

    static Path root(Map<String, String> environment) {
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
