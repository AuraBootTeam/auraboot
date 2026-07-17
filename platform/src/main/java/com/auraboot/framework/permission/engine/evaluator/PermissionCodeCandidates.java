package com.auraboot.framework.permission.engine.evaluator;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PermissionCodeCandidates {

    private PermissionCodeCandidates() {
    }

    static List<String> forResourceAction(String resource, String action) {
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
