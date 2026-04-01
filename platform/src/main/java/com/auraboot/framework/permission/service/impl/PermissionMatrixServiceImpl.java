package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.permission.dto.*;
import com.auraboot.framework.permission.entity.RoleDataScope;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.PermissionMatrixService;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Permission Matrix Service Implementation
 *
 * <p>Transforms the flat permission list into a 3-level hierarchy:
 * Module (level=1) -> Resource (level=2) -> Action (level=3/leaf).
 *
 * <p>If permissions lack a clean 3-level hierarchy, falls back to
 * grouping by resourceType + resourceCode.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PermissionMatrixServiceImpl implements PermissionMatrixService {

    private final PermissionService permissionService;
    private final RolePermissionService rolePermissionService;
    private final DataScopeService dataScopeService;
    private final PermissionPolicyService policyService;
    private final ObjectMapper objectMapper;

    /**
     * Standard action ordering. Actions in this list appear first in fixed order;
     * any custom actions are appended alphabetically.
     */
    private static final List<String> STANDARD_ACTION_ORDER = List.of(
        "read", "create", "update", "delete", "import", "export"
    );

    @Override
    public PermissionMatrixDTO getMatrix(Long tenantId) {
        List<PermissionDTO> allActive = permissionService.findAllActive();
        return buildMatrix(allActive, Collections.emptySet(), Collections.emptyMap(), null);
    }

    @Override
    public PermissionMatrixDTO getMatrixForRole(Long tenantId, Long roleId) {
        List<PermissionDTO> allActive = permissionService.findAllActive();
        Set<Long> grantedIds = rolePermissionService.getPermissionIdsByRoleId(roleId);

        // Build a lookup map: "resourceCode:actionCode" -> RoleDataScope
        List<RoleDataScope> scopes = dataScopeService.getScopesByRole(tenantId, roleId);
        Map<String, RoleDataScope> scopeMap = new HashMap<>();
        for (RoleDataScope scope : scopes) {
            String key = scope.getResourceCode() + ":" + scope.getActionCode();
            scopeMap.put(key, scope);
        }

        return buildMatrix(allActive, grantedIds, scopeMap, roleId);
    }

    @Override
    @Transactional
    public void batchUpdateRolePermissions(Long roleId, List<PermissionGrantRequest> grants) {
        if (grants == null || grants.isEmpty()) {
            return;
        }

        List<Long> toGrant = new ArrayList<>();
        List<Long> toRevoke = new ArrayList<>();

        for (PermissionGrantRequest grant : grants) {
            if (Boolean.TRUE.equals(grant.granted())) {
                toGrant.add(grant.permissionId());
            } else {
                toRevoke.add(grant.permissionId());
            }
        }

        if (!toGrant.isEmpty()) {
            rolePermissionService.assignPermissionsToRole(roleId, toGrant);
        }

        for (Long permissionId : toRevoke) {
            rolePermissionService.removePermission(roleId, permissionId);
        }

        log.info("Batch updated role permissions: roleId={}, granted={}, revoked={}",
            roleId, toGrant.size(), toRevoke.size());
    }

    // ========================================================================
    // Matrix Building
    // ========================================================================

    private PermissionMatrixDTO buildMatrix(List<PermissionDTO> allPermissions,
                                             Set<Long> grantedIds,
                                             Map<String, RoleDataScope> scopeMap,
                                             Long roleId) {
        // Build parent lookup: id -> PermissionDTO
        Map<Long, PermissionDTO> byId = new LinkedHashMap<>();
        for (PermissionDTO p : allPermissions) {
            byId.put(p.getId(), p);
        }

        // Separate into levels
        List<PermissionDTO> level1 = new ArrayList<>(); // modules
        List<PermissionDTO> level2 = new ArrayList<>(); // resources
        List<PermissionDTO> leaves = new ArrayList<>();  // actions (level 3+, or leaf nodes)

        for (PermissionDTO p : allPermissions) {
            Integer lvl = p.getLevel();
            if (lvl != null && lvl == 1) {
                level1.add(p);
            } else if (lvl != null && lvl == 2) {
                level2.add(p);
            } else {
                // level 3+ or null-level are potential leaf actions
                leaves.add(p);
            }
        }

        // If we have a clean hierarchy (level 1 + level 2 + leaves), use it
        if (!level1.isEmpty() && !level2.isEmpty()) {
            return buildHierarchicalMatrix(level1, level2, leaves, byId, grantedIds, scopeMap, roleId);
        }

        // Fallback: group by resourceType + resourceCode
        return buildFlatMatrix(allPermissions, grantedIds, scopeMap, roleId);
    }

    private PermissionMatrixDTO buildHierarchicalMatrix(
            List<PermissionDTO> modules,
            List<PermissionDTO> resources,
            List<PermissionDTO> actions,
            Map<Long, PermissionDTO> byId,
            Set<Long> grantedIds,
            Map<String, RoleDataScope> scopeMap,
            Long roleId) {

        // Group resources by parent (module) ID
        Map<Long, List<PermissionDTO>> resourcesByModule = resources.stream()
            .filter(r -> r.getParentId() != null)
            .collect(Collectors.groupingBy(PermissionDTO::getParentId, LinkedHashMap::new, Collectors.toList()));

        // Group actions by parent (resource) ID
        Map<Long, List<PermissionDTO>> actionsByResource = actions.stream()
            .filter(a -> a.getParentId() != null)
            .collect(Collectors.groupingBy(PermissionDTO::getParentId, LinkedHashMap::new, Collectors.toList()));

        List<PermissionMatrixModuleDTO> moduleDTOs = new ArrayList<>();

        for (PermissionDTO module : modules) {
            List<PermissionDTO> moduleResources = resourcesByModule.getOrDefault(module.getId(), Collections.emptyList());

            List<PermissionMatrixResourceDTO> resourceDTOs = new ArrayList<>();
            for (PermissionDTO resource : moduleResources) {
                List<PermissionDTO> resourceActions = actionsByResource.getOrDefault(resource.getId(), Collections.emptyList());
                List<PermissionMatrixActionDTO> actionDTOs = buildActionDTOs(resourceActions, grantedIds, scopeMap, roleId);
                resourceDTOs.add(new PermissionMatrixResourceDTO(
                    resource.getResourceCode() != null ? resource.getResourceCode() : resource.getCode(),
                    resource.getName() != null ? resource.getName() : resource.getCode(),
                    actionDTOs
                ));
            }

            // Only include modules that have resources
            if (!resourceDTOs.isEmpty()) {
                String moduleCode = module.getCode() != null ? module.getCode() : "module-" + module.getId();
                moduleDTOs.add(new PermissionMatrixModuleDTO(
                    moduleCode,
                    module.getName() != null ? module.getName() : moduleCode,
                    resourceDTOs
                ));
            }
        }

        return new PermissionMatrixDTO(moduleDTOs);
    }

    private PermissionMatrixDTO buildFlatMatrix(List<PermissionDTO> allPermissions,
                                                 Set<Long> grantedIds,
                                                 Map<String, RoleDataScope> scopeMap,
                                                 Long roleId) {
        // Group by resourceType as module, then by resourceCode as resource
        Map<String, Map<String, List<PermissionDTO>>> grouped = new LinkedHashMap<>();

        for (PermissionDTO p : allPermissions) {
            String moduleKey = p.getResourceType() != null ? p.getResourceType() : "other";
            String resourceKey = p.getResourceCode() != null ? p.getResourceCode() : "default";

            grouped
                .computeIfAbsent(moduleKey, k -> new LinkedHashMap<>())
                .computeIfAbsent(resourceKey, k -> new ArrayList<>())
                .add(p);
        }

        List<PermissionMatrixModuleDTO> moduleDTOs = new ArrayList<>();

        for (Map.Entry<String, Map<String, List<PermissionDTO>>> moduleEntry : grouped.entrySet()) {
            List<PermissionMatrixResourceDTO> resourceDTOs = new ArrayList<>();

            for (Map.Entry<String, List<PermissionDTO>> resourceEntry : moduleEntry.getValue().entrySet()) {
                List<PermissionMatrixActionDTO> actionDTOs = buildActionDTOs(resourceEntry.getValue(), grantedIds, scopeMap, roleId);
                resourceDTOs.add(new PermissionMatrixResourceDTO(
                    resourceEntry.getKey(),
                    resourceEntry.getKey(),
                    actionDTOs
                ));
            }

            moduleDTOs.add(new PermissionMatrixModuleDTO(
                moduleEntry.getKey(),
                moduleEntry.getKey(),
                resourceDTOs
            ));
        }

        return new PermissionMatrixDTO(moduleDTOs);
    }

    private List<PermissionMatrixActionDTO> buildActionDTOs(
            List<PermissionDTO> actions,
            Set<Long> grantedIds,
            Map<String, RoleDataScope> scopeMap,
            Long roleId) {

        // Sort: standard actions first in fixed order, custom actions alphabetically after
        List<PermissionDTO> sorted = new ArrayList<>(actions);
        sorted.sort((a, b) -> {
            String actionA = a.getAction();
            String actionB = b.getAction();
            // ImmutableCollections$ListN.indexOf(null) throws NPE, so guard against null action
            int idxA = actionA != null ? STANDARD_ACTION_ORDER.indexOf(actionA) : -1;
            int idxB = actionB != null ? STANDARD_ACTION_ORDER.indexOf(actionB) : -1;
            if (idxA >= 0 && idxB >= 0) return Integer.compare(idxA, idxB);
            if (idxA >= 0) return -1;
            if (idxB >= 0) return 1;
            return (actionA != null ? actionA : "").compareTo(actionB != null ? actionB : "");
        });

        return sorted.stream()
            .map(p -> {
                String resourceCode = p.getResourceCode() != null ? p.getResourceCode() : "";
                String actionCode = p.getAction() != null ? p.getAction() : "";
                RoleDataScope scope = scopeMap.get(resourceCode + ":" + actionCode);

                // Policy schema from permission definition
                String policySchemaJson = serializePolicySchema(p.getPolicySchema());

                // Policy values for this role+permission (only when roleId is available)
                Map<String, Object> policyValues = null;
                if (roleId != null && p.getId() != null) {
                    policyValues = policyService.getPolicy(roleId, p.getId());
                }

                return new PermissionMatrixActionDTO(
                    p.getId(),
                    p.getPid(),
                    p.getCode() != null ? p.getCode() : "unknown",
                    actionCode.isEmpty() ? "unknown" : actionCode,
                    p.getName() != null ? p.getName() : (actionCode.isEmpty() ? p.getCode() : actionCode),
                    grantedIds.contains(p.getId()),
                    true,
                    scope != null ? scope.getScopeType() : null,
                    scope != null ? scope.getMergeStrategy() : null,
                    policySchemaJson,
                    policyValues
                );
            })
            .toList();
    }

    /**
     * Serialize policy schema object to JSON string for DTO transport.
     */
    private String serializePolicySchema(Object policySchema) {
        if (policySchema == null) {
            return null;
        }
        if (policySchema instanceof String str) {
            return str.isBlank() ? null : str;
        }
        // CATCH: non-transactional, safe to handle — JSON serialization for DTO
        try {
            return objectMapper.writeValueAsString(policySchema);
        } catch (Exception e) {
            return null;
        }
    }
}
