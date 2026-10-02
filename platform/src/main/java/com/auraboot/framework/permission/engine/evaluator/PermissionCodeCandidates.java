package com.auraboot.framework.permission.engine.evaluator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PermissionCodeCandidates {

    private PermissionCodeCandidates() {
    }

    public static List<String> forResourceAction(String resource, String action) {
        if (resource == null || resource.isBlank() || action == null || action.isBlank()) {
            return List.of();
        }

        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(resource + ":" + action);
        candidates.add(resource + "." + action);
        if (!resource.startsWith("model.")) {
            candidates.add("model." + resource + "." + action);
        }
        return new ArrayList<>(candidates);
    }
}
