package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.PluginManifest;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.util.SemverMatcher;
import com.auraboot.framework.plugin.validation.PluginValidationContext;
import com.auraboot.framework.plugin.validation.PluginValidationPipeline;
import com.auraboot.framework.plugin.validation.PluginValidationResult;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.rbac.entity.Role;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.common.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.util.*;
import java.util.function.Function;

/** Assesses manifest validation, reference integrity, resource conflicts and dependencies. */
@Slf4j
@RequiredArgsConstructor
final class PluginImportAssessment {
    private final PluginRecordMapper pluginRecordMapper;

    private final PluginResourceMapper pluginResourceMapper;

    private final PluginResourceImporter resourceImporter;

    private final PluginValidationPipeline validationPipeline;

    private final com.auraboot.framework.meta.mapper.CommandDefinitionMapper commandDefinitionMapper;

    private final ObjectMapper objectMapper;

    private final FindDanglingCommandModelRefsOperation findDanglingCommandModelRefsOperation;

    private final FindDanglingMenuParentRefsOperation findDanglingMenuParentRefsOperation;

    private final FindDanglingPermissionRefsOperation findDanglingPermissionRefsOperation;

    @FunctionalInterface
    interface FindDanglingCommandModelRefsOperation { List<String> execute(List<CommandDefinitionDTO> commands, Set<String> providedModels); }

    @FunctionalInterface
    interface FindDanglingMenuParentRefsOperation { List<String> execute(List<MenuDefinitionDTO> menus, Set<String> providedMenus); }

    @FunctionalInterface
    interface FindDanglingPermissionRefsOperation { List<String> execute(List<MenuDefinitionDTO> menus,
            List<RoleDefinitionDTO> roles, Set<String> providedPermissions); }

    private List<String> findDanglingCommandModelRefs(List<CommandDefinitionDTO> commands, Set<String> providedModels) { return findDanglingCommandModelRefsOperation.execute(commands,providedModels); }

    private List<String> findDanglingMenuParentRefs(List<MenuDefinitionDTO> menus, Set<String> providedMenus) { return findDanglingMenuParentRefsOperation.execute(menus,providedMenus); }

    private List<String> findDanglingPermissionRefs(List<MenuDefinitionDTO> menus,
            List<RoleDefinitionDTO> roles, Set<String> providedPermissions) { return findDanglingPermissionRefsOperation.execute(menus,roles,providedPermissions); }

    <T> List<T> loadImportedResourceSnapshots(Long tenantId, ResourceType resourceType, Class<T> dtoClass){
        List<PluginResource> resources = pluginResourceMapper.findByTenantAndType(tenantId, resourceType.code());
        Map<String, T> byCode = new LinkedHashMap<>();
        for (PluginResource resource : resources) {
            if (resource == null || resource.getImportSnapshot() == null || isBlank(resource.getResourceCode())) {
                continue;
            }
            try {
                T dto = objectMapper.convertValue(resource.getImportSnapshot(), dtoClass);
                byCode.put(resource.getResourceCode(), dto);
            } catch (Exception e) {
                log.warn("Failed to reconstruct {} snapshot for code={}: {}",
                        resourceType.code(), logSafe(resource.getResourceCode()), logSafe(e.getMessage()));
            }
        }
        return new ArrayList<>(byCode.values());
    }

    List<String> verifyImportReferenceIntegrity(){
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new PluginException("Tenant context is required for reference-integrity verification");
        }

        List<com.auraboot.framework.meta.entity.CommandDefinition> commands = commandDefinitionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.auraboot.framework.meta.entity.CommandDefinition>()
                        .eq("tenant_id", tenantId)
                        .eq("is_current", true)
                        .eq("deleted_flag", false));

        List<CommandDefinitionDTO> commandDtos = new ArrayList<>();
        Set<String> providedModels = new HashSet<>();
        for (com.auraboot.framework.meta.entity.CommandDefinition cmd : commands) {
            CommandDefinitionDTO dto = new CommandDefinitionDTO();
            dto.setCode(cmd.getCode());
            dto.setModelCode(cmd.getModelCode());
            commandDtos.add(dto);
            if (!isBlank(cmd.getModelCode()) && resourceImporter.checkModelExists(tenantId, cmd.getModelCode())) {
                providedModels.add(cmd.getModelCode());
            }
        }

        List<String> dangling = new ArrayList<>(findDanglingCommandModelRefs(commandDtos, providedModels));

        List<MenuDefinitionDTO> menuDtos = loadImportedResourceSnapshots(tenantId, ResourceType.MENU, MenuDefinitionDTO.class);
        List<RoleDefinitionDTO> roleDtos = loadImportedResourceSnapshots(tenantId, ResourceType.ROLE, RoleDefinitionDTO.class);

        Set<String> providedMenus = new HashSet<>();
        for (MenuDefinitionDTO menu : menuDtos) {
            if (menu != null && !isBlank(menu.getCode())
                    && resourceImporter.checkMenuExists(tenantId, menu.getCode())) {
                providedMenus.add(menu.getCode());
            }
        }

        Set<String> permissionCodesToCheck = new HashSet<>();
        for (MenuDefinitionDTO menu : menuDtos) {
            if (menu != null && !isBlank(menu.getPermissionCode())) {
                permissionCodesToCheck.add(menu.getPermissionCode());
            }
        }
        for (RoleDefinitionDTO role : roleDtos) {
            if (role == null) {
                continue;
            }
            if (role.getPermissions() != null) {
                permissionCodesToCheck.addAll(role.getPermissions());
            }
            if (role.getPermissionPolicies() != null) {
                for (RolePermissionPolicyDefinitionDTO policy : role.getPermissionPolicies()) {
                    if (policy != null && !isBlank(policy.getPermissionCode())) {
                        permissionCodesToCheck.add(policy.getPermissionCode());
                    }
                }
            }
        }
        Set<String> providedPermissions = new HashSet<>();
        for (String code : permissionCodesToCheck) {
            if (!isBlank(code) && resourceImporter.checkPermissionExists(tenantId, code)) {
                providedPermissions.add(code);
            }
        }

        dangling.addAll(findDanglingMenuParentRefs(menuDtos, providedMenus));
        dangling.addAll(findDanglingPermissionRefs(menuDtos, roleDtos, providedPermissions));

        return dangling;
    }

    String normalizeCode(String value){
        return isBlank(value) ? null : value.trim();
    }

    boolean isBlank(String value){
        return value == null || value.isBlank();
    }

    PluginValidationResult runValidationPipeline(PluginManifestExtended manifest, boolean validateReferences){
        return runValidationPipeline(manifest, validateReferences, false);
    }

    PluginValidationResult runValidationPipeline(PluginManifestExtended manifest, boolean validateReferences,
                                                         boolean deferReferenceValidation){
        Long tenantId = MetaContext.getCurrentTenantId();

        // Collect installed plugin dependencies for cycle detection
        Map<String, List<String>> installedPluginDeps = new HashMap<>();
        Set<String> installedPluginIds = new HashSet<>();
        try {
            List<PluginRecord> allPlugins = pluginRecordMapper.selectList(
                    new LambdaQueryWrapper<PluginRecord>().eq(PluginRecord::getTenantId, tenantId));
            for (PluginRecord p : allPlugins) {
                installedPluginIds.add(p.getPluginId());
                if (p.getManifest() != null && p.getManifest().getDependencies() != null) {
                    installedPluginDeps.put(p.getPluginId(), p.getManifest().getDependencies());
                }
            }
        } catch (Exception e) {
            log.debug("Could not load installed plugins for validation: {}", logSafe(e.getMessage()));
        }

        // Load installed resource codes from DB for cross-plugin reference validation
        Set<String> installedModelCodes = new HashSet<>();
        Set<String> installedFieldCodes = new HashSet<>();
        Set<String> installedPermissionCodes = new HashSet<>();
        Set<String> installedCommandCodes = new HashSet<>();
        Set<String> installedNamedQueryCodes = new HashSet<>();
        try {
            // Collect all model codes and field codes from the manifest's referenced models
            // that exist in the tenant (checking via resourceImporter)
            Set<String> referencedModels = new HashSet<>();
            Set<String> referencedFields = new HashSet<>();
            Set<String> referencedPermissions = new HashSet<>();
            if (manifest.getCommands() != null) {
                manifest.getCommands().forEach(cmd -> {
                    if (cmd != null && cmd.getModelCode() != null) referencedModels.add(cmd.getModelCode());
                });
            }
            if (manifest.getModelFieldBindings() != null) {
                manifest.getModelFieldBindings().forEach(b -> {
                    if (b != null) {
                        if (b.getModelCode() != null) referencedModels.add(b.getModelCode());
                        if (b.getFieldCode() != null) referencedFields.add(b.getFieldCode());
                    }
                });
            }
            if (manifest.getMenus() != null) {
                manifest.getMenus().forEach(menu -> {
                    if (menu != null && !isBlank(menu.getPermissionCode())) {
                        referencedPermissions.add(menu.getPermissionCode());
                    }
                });
            }
            // Only check external references (not in the manifest's own resources)
            Set<String> manifestModelCodes = new HashSet<>();
            if (manifest.getModels() != null) {
                manifest.getModels().forEach(m -> { if (m != null && m.getCode() != null) manifestModelCodes.add(m.getCode()); });
            }
            Set<String> manifestFieldCodes = new HashSet<>();
            if (manifest.getFields() != null) {
                manifest.getFields().forEach(f -> { if (f != null && f.getCode() != null) manifestFieldCodes.add(f.getCode()); });
            }
            Set<String> manifestPermissionCodes = new HashSet<>();
            if (manifest.getPermissions() != null) {
                manifest.getPermissions().forEach(p -> {
                    if (p != null && !isBlank(p.getCode())) manifestPermissionCodes.add(p.getCode());
                });
            }
            for (String modelCode : referencedModels) {
                if (!manifestModelCodes.contains(modelCode) && resourceImporter.checkModelExists(tenantId, modelCode)) {
                    installedModelCodes.add(modelCode);
                }
            }
            for (String fieldCode : referencedFields) {
                if (!manifestFieldCodes.contains(fieldCode) && resourceImporter.checkFieldExists(tenantId, fieldCode)) {
                    installedFieldCodes.add(fieldCode);
                }
            }
            for (String permissionCode : referencedPermissions) {
                if (!manifestPermissionCodes.contains(permissionCode)
                        && resourceImporter.checkPermissionExists(tenantId, permissionCode)) {
                    installedPermissionCodes.add(permissionCode);
                }
            }
            // Collect installed command/NQ codes for capability dependency validation
            if (manifest.getRequires() != null) {
                for (var req : manifest.getRequires()) {
                    if (req == null || req.getCode() == null) continue;
                    if ("model".equals(req.getType()) && resourceImporter.checkModelExists(tenantId, req.getCode())) {
                        installedModelCodes.add(req.getCode());
                    } else if ("command".equals(req.getType()) && resourceImporter.checkCommandExists(tenantId, req.getCode())) {
                        installedCommandCodes.add(req.getCode());
                    } else if ("query".equals(req.getType()) && resourceImporter.checkNamedQueryExists(tenantId, req.getCode())) {
                        installedNamedQueryCodes.add(req.getCode());
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not load installed resources for validation: {}", logSafe(e.getMessage()));
        }

        PluginValidationContext ctx = PluginValidationContext.builder()
                .pluginId(manifest.getPluginId())
                .namespace(manifest.getNamespace())
                .manifest(manifest)
                .validateReferences(validateReferences)
                .deferReferenceValidation(deferReferenceValidation)
                .installedModelCodes(installedModelCodes)
                .installedFieldCodes(installedFieldCodes)
                .installedPermissionCodes(installedPermissionCodes)
                .installedCommandCodes(installedCommandCodes)
                .installedNamedQueryCodes(installedNamedQueryCodes)
                .installedPluginIds(installedPluginIds)
                .installedPluginDependencies(installedPluginDeps)
                .build();

        return validationPipeline.validate(ctx);
    }

    List<ImportPreviewResult.ResourceConflict> checkConflicts(PluginManifestExtended manifest){
        List<ImportPreviewResult.ResourceConflict> conflicts = new ArrayList<>();
        if (!MetaContext.exists()) {
            return conflicts;
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null || manifest == null) {
            return conflicts;
        }

        String importingPluginId = manifest.getPluginId();
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.MODEL, manifest.getModels(),
                ModelDefinitionDTO::getCode, "Model");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.FIELD, manifest.getFields(),
                FieldDefinitionDTO::getCode, "Field");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.COMMAND, manifest.getCommands(),
                CommandDefinitionDTO::getCode, "Command");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.PERMISSION, manifest.getPermissions(),
                PermissionDefinitionDTO::getCode, "Permission");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.ROLE, manifest.getRoles(),
                RoleDefinitionDTO::getCode, "Role");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.MENU, manifest.getMenus(),
                MenuDefinitionDTO::getCode, "Menu");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.PAGE, manifest.getPages(),
                PageSchemaDTO::getPageKey, "Page");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.DICT, manifest.getDicts(),
                DictDefinitionDTO::getCode, "Dictionary");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.AGENT_DEFINITION,
                manifest.getAgentDefinitions(), AgentDefinitionDTO::getAgentCode, "AgentDefinition");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.MODEL_FIELD_BINDING,
                manifest.getModelFieldBindings(), b -> b.getModelCode() + "." + b.getFieldCode(), "ModelFieldBinding");
        collectConflicts(conflicts, importingPluginId, tenantId, ResourceType.I18N, manifest.getI18nResources(),
                I18nDefinitionDTO::getKey, "I18n");

        return conflicts;
    }

    <T> void collectConflicts(
            List<ImportPreviewResult.ResourceConflict> conflicts,
            String importingPluginId,
            Long tenantId,
            ResourceType resourceType,
            List<T> resources,
            Function<T, String> codeExtractor,
            String label){
        if (resources == null || resources.isEmpty()) {
            return;
        }

        for (T resource : resources) {
            String code = codeExtractor.apply(resource);
            if (code == null || code.isBlank()) {
                continue;
            }

            PluginResource existing;
            try {
                existing = pluginResourceMapper.findByTypeAndCode(
                        tenantId, resourceType.name(), code);
            } catch (Exception ex) {
                // Conflict preview must be best-effort; duplicated historical rows should not block import.
                log.warn("Skip conflict check for {} {} due to lookup error: {}",
                        resourceType, logSafe(code), logSafe(ex.getMessage()));
                continue;
            }
            if (existing == null) {
                continue;
            }

            String ownerPluginId = resolveOwnerPluginId(existing.getPluginPid());
            if (ownerPluginId != null && ownerPluginId.equals(importingPluginId)) {
                continue;
            }

            conflicts.add(ImportPreviewResult.ResourceConflict.builder()
                    .resourceType(resourceType)
                    .resourceCode(code)
                    .conflictType("different_plugin")
                    .ownerPluginId(ownerPluginId != null ? ownerPluginId : existing.getPluginPid())
                    .description(label + " owned by different plugin")
                    .build());
        }
    }

    String resolveOwnerPluginId(String pluginPid){
        if (pluginPid == null || pluginPid.isBlank()) {
            return null;
        }
        PluginRecord ownerRecord = pluginRecordMapper.findByPid(pluginPid);
        if (ownerRecord != null && ownerRecord.getPluginId() != null && !ownerRecord.getPluginId().isBlank()) {
            return ownerRecord.getPluginId();
        }
        return pluginPid;
    }

    ImportPreviewResult.DependencyAnalysis analyzeDependencies(PluginManifestExtended manifest){
        List<String> missingDependencies = new ArrayList<>();
        List<ImportPreviewResult.PluginDependency> pluginDeps = new ArrayList<>();

        Long tenantId = MetaContext.getCurrentTenantId();

        // Use structured dependency specs (supports version constraints)
        List<PluginManifest.PluginDependencySpec> specs = manifest.getEffectiveDependencySpecs();
        for (PluginManifest.PluginDependencySpec spec : specs) {
            String depPluginId = spec.getPluginId();
            String requiredRange = spec.getVersionRange();

            PluginRecord dep = pluginRecordMapper.findByTenantAndPluginId(depPluginId);
            if (dep == null) {
                missingDependencies.add("Plugin: " + depPluginId
                        + (!"*".equals(requiredRange) ? " " + requiredRange : ""));
                pluginDeps.add(ImportPreviewResult.PluginDependency.builder()
                        .pluginId(depPluginId)
                        .requiredVersion(requiredRange)
                        .satisfied(false)
                        .build());
            } else {
                boolean versionSatisfied = SemverMatcher.matches(dep.getVersion(), requiredRange);
                if (!versionSatisfied) {
                    missingDependencies.add("Plugin: " + depPluginId
                            + " requires " + requiredRange + ", installed: " + dep.getVersion());
                }
                pluginDeps.add(ImportPreviewResult.PluginDependency.builder()
                        .pluginId(depPluginId)
                        .requiredVersion(requiredRange)
                        .installedVersion(dep.getVersion())
                        .satisfied(versionSatisfied)
                        .build());
            }
        }

        return ImportPreviewResult.DependencyAnalysis.builder()
                .pluginDependencies(pluginDeps)
                .missingDependencies(missingDependencies)
                .satisfied(missingDependencies.isEmpty())
                .build();
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}
