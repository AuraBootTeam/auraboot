package com.auraboot.framework.permission.service;

import com.auraboot.framework.permission.entity.Permission;
import org.springframework.security.access.AccessDeniedException;
import java.util.*;

/** Finite published model/action ownership for a domain level; never an expansion into child grants. */
public final class DomainPermissionPolicy {
    private DomainPermissionPolicy() {}

    public static Map<?, ?> models(Permission permission) {
        if (permission == null || !(permission.getExtension() instanceof Map<?, ?> extension)
                || !extension.containsKey("domainModels")) return Map.of();
        if (!(extension.get("domainModels") instanceof Map<?, ?> models) || models.isEmpty()
                || !Set.of("read", "write", "manage").contains(permission.getAction())
                || !"operation".equals(permission.getResourceType())) throw denied();
        for (var entry : models.entrySet()) {
            if (!(entry.getKey() instanceof String model) || !model.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                    || !(entry.getValue() instanceof List<?> actions)
                    || !Set.of("read", "create", "update", "delete").containsAll(actions)) throw denied();
        }
        return models;
    }
    public static boolean permits(com.auraboot.framework.permission.dto.PermissionDTO definition, String model, String action) {
        if (definition == null) return false;
        var permission = new Permission();
        permission.setResourceType(definition.getResourceType()); permission.setAction(definition.getAction());
        permission.setExtension(definition.getExtension());
        return permits(permission, model, action);
    }
    public static boolean owns(Permission permission, String model) { return models(permission).containsKey(model); }
    public static boolean permits(Permission permission, String model, String action) {
        Object actions = models(permission).get(model);
        return actions instanceof List<?> values && (action == null || values.contains(action));
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Invalid published business domain policy"); }
}
