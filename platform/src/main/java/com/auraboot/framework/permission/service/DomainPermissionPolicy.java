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
        commandEffects(permission);
        return models;
    }
    public static boolean permits(com.auraboot.framework.permission.dto.PermissionDTO definition, String model, String action) {
        if (definition == null) return false;
        var permission = new Permission();
        permission.setCode(definition.getCode());
        permission.setResourceType(definition.getResourceType()); permission.setAction(definition.getAction());
        permission.setExtension(definition.getExtension());
        return permits(permission, model, action);
    }
    public static boolean owns(Permission permission, String model) {
        return models(permission).containsKey(model) || currentCommandEffect(permission, model, null);
    }
    public static boolean permits(Permission permission, String model, String action) {
        Object actions = models(permission).get(model);
        return actions instanceof List<?> values && (action == null || values.contains(action))
                || currentCommandEffect(permission, model, action);
    }
    /** Internal effects consume the admitted use case's duty, not the standalone CRUD minimum.
     * Object/action scopes and field policies are still evaluated for the actual affected row.
     */
    public static boolean isCurrentCommandMutation(Permission permission, String model, String action) {
        return Set.of("create", "update", "delete").contains(action == null ? "" : action)
                && currentCommandAuthority(permission)
                && (models(permission).containsKey(model) || currentCommandEffect(permission, model, action));
    }

    /** Published technical effects lend no independent model authority outside the admitted command. */
    private static boolean currentCommandEffect(Permission permission, String model, String action) {
        Object actions = commandEffects(permission).get(model);
        return actions instanceof List<?> values && (action == null || values.contains(action))
                && currentCommandAuthority(permission);
    }

    private static boolean currentCommandAuthority(Permission permission) {
        return permission != null && Set.of("write", "manage").contains(permission.getAction())
                && com.auraboot.framework.application.tenant.MetaContext.hasCommandPermitScope()
                && com.auraboot.framework.application.tenant.MetaContext.getAuthorizedCommandCode() != null
                && Objects.equals(permission.getCode(),
                    com.auraboot.framework.application.tenant.MetaContext.getCommandAuthority());
    }

    private static Map<?, ?> commandEffects(Permission permission) {
        if (permission == null || !(permission.getExtension() instanceof Map<?, ?> extension)
                || !extension.containsKey("commandEffects")) return Map.of();
        if (!(extension.get("commandEffects") instanceof Map<?, ?> effects) || effects.isEmpty() || effects.size() > 64
                || !(extension.get("domainModels") instanceof Map<?, ?> roots) || roots.isEmpty()
                || !Set.of("write", "manage").contains(permission.getAction())
                || !"operation".equals(permission.getResourceType())) throw denied();
        for (var entry : effects.entrySet()) {
            if (!(entry.getKey() instanceof String model) || !model.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")
                    || !(entry.getValue() instanceof List<?> actions) || actions.isEmpty()
                    || new HashSet<>(actions).size() != actions.size()
                    || !Set.of("read", "create", "update", "delete").containsAll(actions)) throw denied();
        }
        return effects;
    }
    private static AccessDeniedException denied() { return new AccessDeniedException("Invalid published business domain policy"); }
}
