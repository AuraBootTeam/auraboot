package com.auraboot.framework.plugin.validation;

import java.util.Set;

/**
 * Host-registered import contract for a render profile. A plugin manifest cannot
 * register its own vocabulary: the host must supply this contract together with
 * the matching frontend renderers. Import validation does not prove rendering.
 */
public record PageSchemaRenderProfile(String name, Set<String> kinds, Set<String> blockTypes) {
    public PageSchemaRenderProfile {
        if (name == null || name.isBlank() || "admin".equals(name)) {
            throw new IllegalArgumentException("An extension profile must have a non-admin name");
        }
        kinds = Set.copyOf(kinds);
        blockTypes = Set.copyOf(blockTypes);
        if (kinds.isEmpty() || blockTypes.isEmpty()
                || kinds.stream().anyMatch(String::isBlank)
                || blockTypes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("A render profile must declare non-empty kinds and block types");
        }
    }
}
