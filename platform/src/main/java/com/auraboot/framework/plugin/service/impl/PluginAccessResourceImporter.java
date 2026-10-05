package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.common.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/** Imports permission, role, menu, field-mask and capability resources. */
@Slf4j
@RequiredArgsConstructor
final class PluginAccessResourceImporter {
    private final PluginResourceImporter resourceImporter;

    private final com.auraboot.framework.meta.service.FieldMaskService fieldMaskService;

    private final com.auraboot.framework.permission.capability.CapabilityRegistryService capabilityRegistryService;

    private final PermissionService permissionService;

    private final UserPermissionService userPermissionService;

    private final RoleService roleService;

    private final RolePermissionMapper rolePermissionMapper;

    private final GenerateMenuI18nRecordsOperation0 generateMenuI18nRecordsOperation0;

    private final GeneratePermissionI18nRecordsOperation1 generatePermissionI18nRecordsOperation1;

    private final SaveOrUpdatePluginResourceOperation saveOrUpdatePluginResourceOperation;

    private final CaptureImportSnapshotOperation captureImportSnapshotOperation;

    @FunctionalInterface
    interface GenerateMenuI18nRecordsOperation0 { void execute(List<MenuDefinitionDTO> menus, Long tenantId); }

    @FunctionalInterface
    interface GeneratePermissionI18nRecordsOperation1 { void execute(List<PermissionDefinitionDTO> permissions, Long tenantId); }

    @FunctionalInterface
    interface SaveOrUpdatePluginResourceOperation { void execute(PluginResource resource, Long tenantId); }

    @FunctionalInterface
    interface CaptureImportSnapshotOperation { void execute(PluginResource resource, Object manifestDto); }

    private void generateMenuI18nRecords(List<MenuDefinitionDTO> menus, Long tenantId) { generateMenuI18nRecordsOperation0.execute(menus,tenantId); }

    private void generatePermissionI18nRecords(List<PermissionDefinitionDTO> permissions, Long tenantId) { generatePermissionI18nRecordsOperation1.execute(permissions,tenantId); }

    private void saveOrUpdatePluginResource(PluginResource resource, Long tenantId) { saveOrUpdatePluginResourceOperation.execute(resource,tenantId); }

    private void captureImportSnapshot(PluginResource resource, Object manifestDto) { captureImportSnapshotOperation.execute(resource,manifestDto); }

    void reconcileRolePermissionBindings(PluginManifestExtended manifest, Long tenantId){
        if (manifest.getRoles() == null || manifest.getRoles().isEmpty()) {
            return;
        }
        for (RoleDefinitionDTO role : manifest.getRoles()) {
            if (role == null || !role.isValid()) {
                continue;
            }
            try {
                resourceImporter.reconcileRolePermissions(role, tenantId);
            } catch (Exception e) {
                // Per-role best-effort, mirroring updateRolePermissions: one broken role
                // must not abort the import; the warning keeps the gap visible in logs.
                log.warn("Role permission reconciliation failed for role {}: {}",
                        logSafe(role.getCode()), logSafe(e.getMessage()), e);
            }
        }
    }

    void importPermissions(PluginManifestExtended manifest, ImportRequest request,
                                   ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        if (manifest.getPermissions() == null) return;

        for (PermissionDefinitionDTO permission : manifest.getPermissions()) {
            if (!permission.isValid()) {
                log.warn("Skipping invalid permission entry (missing code): index={}", manifest.getPermissions().indexOf(permission));
                continue;
            }
            PluginResource resource = resourceImporter.importPermission(permission, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, permission);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.PERMISSION, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.PERMISSION, resource.getResourcePid());
                }
            }
        }

        generatePermissionI18nRecords(manifest.getPermissions(), tenantId);
        bindImportedPermissionsToTenantAdmin(manifest.getPermissions(), tenantId);
    }

    void bindImportedPermissionsToTenantAdmin(List<PermissionDefinitionDTO> permissions, Long tenantId){
        if (permissions == null || permissions.isEmpty()) return;
        if (tenantId == null) return;

        Role tenantAdminRole = roleService.findByTenantId(tenantId).stream()
                .filter(role -> "tenant_admin".equals(role.getCode()))
                .findFirst()
                .orElse(null);
        if (tenantAdminRole == null) {
            log.warn("tenant_admin role not found, skip binding imported permissions: tenantId={}", tenantId);
            return;
        }

        Set<Long> boundPermissionIds = permissionService.findRolePermissions(tenantAdminRole.getId()).stream()
                .map(PermissionDTO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        for (PermissionDefinitionDTO permission : permissions) {
            try {
                PermissionDTO permissionDTO = permissionService.findByCode(permission.getCode());
                if (permissionDTO == null) {
                    log.warn("Imported permission not found after import: code={}", logSafe(permission.getCode()));
                    continue;
                }

                //todo check logic
                if (boundPermissionIds.contains(permissionDTO.getId())) {
                    log.warn("Duplicated permission found: code={}", logSafe(permission.getCode()));

                    continue;
                }
                RolePermission binding = new RolePermission();
                binding.setPid(UniqueIdGenerator.generate());
                binding.setTenantId(tenantId);
                binding.setRoleId(tenantAdminRole.getId());
                binding.setPermissionId(permissionDTO.getId());
                binding.setGrantType(StatusConstants.GRANT);
                binding.setPriority(0);
                binding.setStatus(StatusConstants.ACTIVE);
                binding.setDeletedFlag(false);
                binding.setCreatedAt(Instant.now());
                binding.setUpdatedAt(Instant.now());
                rolePermissionMapper.insert(binding);
                boundPermissionIds.add(permissionDTO.getId());
            } catch (Exception e) {
                // Duplicate bind and stale edge cases should not fail plugin import.
                log.debug("Skip binding permission to tenant_admin: code={}, reason={}",
                        logSafe(permission.getCode()), logSafe(e.getMessage()));
            }
        }
        userPermissionService.evictPermissionDefinitions(tenantId);
        userPermissionService.evictRoleUsers(tenantId, tenantAdminRole.getId());
    }

    void importRoles(PluginManifestExtended manifest, ImportRequest request,
                             ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        if (manifest.getRoles() == null) return;

        for (RoleDefinitionDTO role : manifest.getRoles()) {
            if (!role.isValid()) {
                log.warn("Skipping invalid role entry (missing code): index={}", manifest.getRoles().indexOf(role));
                continue;
            }
            PluginResource resource = resourceImporter.importRole(role, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, role);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.ROLE, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.ROLE, resource.getResourcePid());
                }
            }
        }
    }

    void importRolePermissions(PluginManifestExtended manifest, ImportRequest request,
                                       ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        // Role-permission bindings are handled within importRoles based on role.permissions list
    }

    void importMenus(PluginManifestExtended manifest, ImportRequest request,
                             ImportExecuteResult result, String pluginPid, String importId, Long tenantId){
        if (manifest.getMenus() == null) return;

        // Clear menu code→id map before processing this batch
        if (resourceImporter instanceof PluginResourceImporterImpl impl) {
            impl.clearMenuCodeMap();
        }

        // Topological sort: ensure parent menus are imported before children
        List<MenuDefinitionDTO> sorted = topologicalSortMenus(manifest.getMenus());

        for (MenuDefinitionDTO menu : sorted) {
            if (!menu.isValid()) {
                log.warn("Skipping invalid menu entry (missing code): index={}", sorted.indexOf(menu));
                continue;
            }
            PluginResource resource = resourceImporter.importMenu(menu, pluginPid, importId, tenantId, request.getConflictStrategy());
            if (resource != null) {
                captureImportSnapshot(resource, menu);
                saveOrUpdatePluginResource(resource, tenantId);
                result.incrementResourceCount(ResourceType.MENU, resource.getActionEnum());
                if (resource.getResourcePid() != null) {
                    result.addCreatedResource(ResourceType.MENU, resource.getResourcePid());
                }
            }
        }

        // Auto-generate menu i18n records from name:zh-CN / name:en fields
        generateMenuI18nRecords(sorted, tenantId);
    }

    List<MenuDefinitionDTO> topologicalSortMenus(List<MenuDefinitionDTO> menus){
        Set<String> codesInList = new HashSet<>();
        for (MenuDefinitionDTO m : menus) {
            codesInList.add(m.getCode());
        }

        // Build adjacency: parentCode → children codes (only for in-list references)
        Map<String, List<MenuDefinitionDTO>> childrenOf = new LinkedHashMap<>();
        List<MenuDefinitionDTO> roots = new ArrayList<>();
        for (MenuDefinitionDTO m : menus) {
            String pc = m.getParentCode();
            if (pc != null && codesInList.contains(pc)) {
                childrenOf.computeIfAbsent(pc, k -> new ArrayList<>()).add(m);
            } else {
                roots.add(m);
            }
        }

        // BFS from roots
        List<MenuDefinitionDTO> sorted = new ArrayList<>(menus.size());
        Deque<MenuDefinitionDTO> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            MenuDefinitionDTO current = queue.poll();
            sorted.add(current);
            List<MenuDefinitionDTO> children = childrenOf.get(current.getCode());
            if (children != null) {
                queue.addAll(children);
            }
        }

        // Safety: if any menus were missed (circular refs), append them
        if (sorted.size() < menus.size()) {
            Set<String> sortedCodes = new HashSet<>();
            for (MenuDefinitionDTO m : sorted) {
                sortedCodes.add(m.getCode());
            }
            for (MenuDefinitionDTO m : menus) {
                if (!sortedCodes.contains(m.getCode())) {
                    sorted.add(m);
                }
            }
        }

        return sorted;
    }

    void importFieldMasks(PluginManifestExtended manifest){
        if (manifest.getFieldMasks() == null || manifest.getFieldMasks().isEmpty()) return;
        int created = 0;
        for (FieldMaskDefinitionDTO dto : manifest.getFieldMasks()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid field-mask config (missing modelCode/fieldCode/maskType): index={}",
                        manifest.getFieldMasks().indexOf(dto));
                continue;
            }
            com.auraboot.framework.meta.entity.FieldMaskConfig config =
                    new com.auraboot.framework.meta.entity.FieldMaskConfig();
            config.setModelCode(dto.getModelCode());
            config.setFieldCode(dto.getFieldCode());
            config.setMaskType(dto.getMaskType());
            config.setMaskPattern(dto.getMaskPattern());
            if (dto.getReplacementChar() != null) {
                config.setReplacementChar(dto.getReplacementChar());
            }
            config.setApplyToList(dto.getApplyToList());
            config.setApplyToDetail(dto.getApplyToDetail());
            config.setApplyToExport(dto.getApplyToExport());
            config.setEnabled(dto.getEnabled());
            config.setExemptRoles(dto.getExemptRoles());
            config.setExemptPermissionCodes(dto.getExemptPermissionCodes());
            fieldMaskService.saveConfig(config);
            created++;
        }
        if (created > 0) {
            log.info("Imported {} field-mask config(s) for plugin {}", created, logSafe(manifest.getPluginId()));
        }
    }

    void importCapabilities(PluginManifestExtended manifest){
        if (manifest.getCapabilities() == null || manifest.getCapabilities().isEmpty()) return;
        int created = 0;
        for (CapabilityDefinitionDTO dto : manifest.getCapabilities()) {
            if (!dto.isValid()) {
                log.warn("Skipping invalid capability (missing code/includes): index={}",
                        manifest.getCapabilities().indexOf(dto));
                continue;
            }
            capabilityRegistryService.saveDefinition(dto);
            created++;
        }
        if (created > 0) {
            log.info("Imported {} capability declaration(s) for plugin {}", created, logSafe(manifest.getPluginId()));
        }
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}
