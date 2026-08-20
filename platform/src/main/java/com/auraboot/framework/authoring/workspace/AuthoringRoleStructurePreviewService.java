package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RolePreviewTargetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RoleStructureDecisionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RoleStructurePreviewView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Computes a role's page structure without impersonation or business-data access.
 *
 * <p>The effective authority is always {@code actor permissions ∩ target-role permissions}. Only
 * permissions referenced by the current page/menu structure are emitted. The service reads the
 * authoring snapshot, role bindings, permission catalog, and menu metadata; it has no dependency on
 * query, command, record, export, or workflow execution services.
 */
@Service
public class AuthoringRoleStructurePreviewService {

    private static final String MODE = "STRUCTURE";

    private final AuthoringWorkspaceService workspaceService;
    private final RoleService roleService;
    private final UserPermissionService userPermissionService;
    private final PermissionSnapshotCache permissionSnapshotCache;
    private final MenuMapper menuMapper;

    public AuthoringRoleStructurePreviewService(
            AuthoringWorkspaceService workspaceService,
            RoleService roleService,
            UserPermissionService userPermissionService,
            PermissionSnapshotCache permissionSnapshotCache,
            MenuMapper menuMapper) {
        this.workspaceService = workspaceService;
        this.roleService = roleService;
        this.userPermissionService = userPermissionService;
        this.permissionSnapshotCache = permissionSnapshotCache;
        this.menuMapper = menuMapper;
    }

    @Transactional(readOnly = true)
    public List<RolePreviewTargetView> targets(String sessionPid) {
        workspaceService.get(sessionPid);
        Long tenantId = MetaContext.getCurrentTenantId();
        return roleService.findByTenantId(tenantId).stream()
                .filter(AuthoringRoleStructurePreviewService::isActive)
                .sorted(Comparator.comparing(Role::getPriority, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Role::getName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(Role::getPid))
                .map(AuthoringRoleStructurePreviewService::toTarget)
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleStructurePreviewView preview(String sessionPid, String rolePid) {
        SessionView session = workspaceService.get(sessionPid);
        Role role = roleService.findByPid(rolePid);
        if (role == null || !isActive(role)) {
            throw new ResponseStatusException(NOT_FOUND, "authoring.role-preview.role-not-found");
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Set<String> actorPermissions = normalize(userPermissionService.getUserPermissionCodes(
                MetaContext.getCurrentUserId()));
        Set<String> targetPermissions = normalize(permissionSnapshotCache.resolvePermissionCodes(
                tenantId,
                permissionSnapshotCache.getEffectiveRolePermissionIds(tenantId, role.getId())));

        List<RoleStructureDecisionView> decisions = new ArrayList<>();
        collectBlocks(session.snapshot(), actorPermissions, targetPermissions, decisions);
        List<Menu> menus = menuMapper.findActivePathByPage(
                tenantId, session.pagePid(), text(session.snapshot(), "pageKey"));
        if (menus != null) {
            for (Menu menu : menus) {
                decisions.add(decide(
                        "MENU",
                        firstNonBlank(menu.getPid(), menu.getCode(), "menu"),
                        firstNonBlank(menu.getName(), menu.getCode(), menu.getPid()),
                        menu.getPermissionCode(),
                        actorPermissions,
                        targetPermissions,
                        !Boolean.FALSE.equals(menu.getVisible()),
                        false));
            }
        }

        return new RoleStructurePreviewView(
                MODE,
                session.pagePid(),
                toTarget(role),
                true,
                false,
                false,
                false,
                List.copyOf(decisions));
    }

    private static void collectBlocks(
            JsonNode node,
            Set<String> actorPermissions,
            Set<String> targetPermissions,
            List<RoleStructureDecisionView> decisions) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(child -> collectBlocks(child, actorPermissions, targetPermissions, decisions));
            return;
        }
        if (!node.isObject()) {
            return;
        }

        String blockType = text(node, "blockType");
        if (blockType != null) {
            JsonNode props = node.path("props");
            String permissionCode = firstNonBlank(
                    text(props, "permissionCode"), text(props, "permission"));
            String nodeType = classify(blockType, text(node, "actionType"), text(node, "field"));
            String nodeId = firstNonBlank(
                    text(node, "id"), text(node, "field"), text(node, "actionType"), blockType);
            String label = firstNonBlank(
                    localizedText(node.get("title")),
                    text(props, "label"),
                    text(props, "title"),
                    text(node, "field"),
                    nodeId);
            boolean structurallyVisible = !Boolean.FALSE.equals(booleanValue(props, "visible"))
                    && !Boolean.TRUE.equals(booleanValue(props, "hidden"));
            boolean structurallyWritable = ("FIELD".equals(nodeType) || "ACTION".equals(nodeType))
                    && !Boolean.TRUE.equals(booleanValue(props, "readOnly"))
                    && !Boolean.TRUE.equals(booleanValue(props, "disabled"));
            decisions.add(decide(
                    nodeType,
                    nodeId,
                    label,
                    permissionCode,
                    actorPermissions,
                    targetPermissions,
                    structurallyVisible,
                    structurallyWritable));
        }

        node.properties().forEach(entry -> {
            if (entry.getValue().isContainerNode()) {
                collectBlocks(entry.getValue(), actorPermissions, targetPermissions, decisions);
            }
        });
    }

    private static RoleStructureDecisionView decide(
            String nodeType,
            String nodeId,
            String label,
            String permissionCode,
            Set<String> actorPermissions,
            Set<String> targetPermissions,
            boolean structurallyVisible,
            boolean structurallyWritable) {
        String normalizedPermission = normalizeCode(permissionCode);
        boolean targetAllowed = normalizedPermission == null
                || targetPermissions.contains(normalizedPermission);
        boolean actorAllowed = normalizedPermission == null
                || actorPermissions.contains(normalizedPermission);
        boolean allowed = targetAllowed && actorAllowed;
        String reason = normalizedPermission == null
                ? "UNRESTRICTED"
                : !targetAllowed
                        ? "TARGET_ROLE_DENY"
                        : !actorAllowed ? "ACTOR_SCOPE_LIMIT" : "ALLOW";
        return new RoleStructureDecisionView(
                nodeType,
                nodeId,
                label,
                permissionCode,
                allowed,
                allowed && structurallyVisible,
                allowed && structurallyVisible && structurallyWritable,
                reason);
    }

    private static String classify(String blockType, String actionType, String field) {
        String normalized = blockType.toLowerCase(Locale.ROOT);
        if (actionType != null || normalized.contains("action") || normalized.contains("button")) {
            return "ACTION";
        }
        if (field != null || normalized.contains("field") || normalized.contains("column")) {
            return "FIELD";
        }
        return "BLOCK";
    }

    private static Set<String> normalize(Set<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String code : codes) {
            String value = normalizeCode(code);
            if (value != null) {
                normalized.add(value);
            }
        }
        return Set.copyOf(normalized);
    }

    private static String normalizeCode(String code) {
        return code == null || code.isBlank() ? null : code.trim().toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank()
                ? value.asText()
                : null;
    }

    private static Boolean booleanValue(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isBoolean() ? value.asBoolean() : null;
    }

    private static String localizedText(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isObject()) {
            return firstNonBlank(
                    text(value, "zh-CN"), text(value, "en-US"), text(value, "en"));
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static boolean isActive(Role role) {
        return role != null
                && !Boolean.TRUE.equals(role.getDeletedFlag())
                && (role.getStatus() == null || "ACTIVE".equalsIgnoreCase(role.getStatus()));
    }

    private static RolePreviewTargetView toTarget(Role role) {
        return new RolePreviewTargetView(role.getPid(), role.getCode(), role.getName());
    }
}
