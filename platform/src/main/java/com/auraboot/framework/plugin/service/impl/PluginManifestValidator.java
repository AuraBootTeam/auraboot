package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.view.entity.SavedView;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.util.SemverMatcher;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.rbac.entity.Role;
import java.io.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.auraboot.framework.plugin.service.PlatformVersionChecker;
import com.auraboot.framework.permission.service.CommandActionDeriver;

import com.auraboot.framework.common.util.LogSanitizer;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

/** Validates manifest declarations and deferred cross-plugin references. */
@Slf4j
@RequiredArgsConstructor
final class PluginManifestValidator {
    private final PluginResourceImporter resourceImporter;
    private final PlatformVersionChecker platformVersionChecker;
    private final CommandActionDeriver commandActionDeriver;

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    public List<String> validateManifest(PluginManifestExtended manifest) {
        return validateManifest(manifest, false);
    }

    /**
     * Validate a manifest, optionally deferring cross-plugin command/binding → model references.
     *
     * <p>When {@code deferReferenceValidation} is {@code true}, a command or binding that
     * references a model which is neither declared in this manifest nor already installed in the
     * tenant is downgraded from a hard error to a {@code [WARN] }-prefixed soft warning instead of
     * failing the import. This lets a batch cold-reset of a cyclic plugin set (e.g. crm↔sales)
     * proceed even though, per-plugin, one side references a model owned by another plugin not yet
     * imported in the same pass. The truly-dangling case (a model provided by no plugin at all) is
     * re-enforced afterwards by {@link #verifyImportReferenceIntegrity()} via
     * {@link #findDanglingCommandModelRefs(List, Set)}.
     *
     * <p>Default ({@code false}) keeps the original strict behaviour for single-plugin imports,
     * the UI, and {@code aura plugin validate}.
     */
    public List<String> validateManifest(PluginManifestExtended manifest, boolean deferReferenceValidation) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (manifest == null) {
            errors.add("Manifest is null");
            return errors;
        }

        // Sanitize: remove JSON comment objects before validation
        manifest.sanitize();

        if (manifest.getValidationErrors() != null) {
            errors.addAll(manifest.getValidationErrors());
        }

        if (isBlank(manifest.getPluginId())) {
            errors.add("pluginId is required");
        }
        if (isBlank(manifest.getNamespace())) {
            errors.add("namespace is required");
        }
        if (isBlank(manifest.getVersion())) {
            errors.add("version is required");
        }

        // Validate version is valid semver
        if (!isBlank(manifest.getVersion()) && !SemverMatcher.isValid(manifest.getVersion())) {
            errors.add("version '" + manifest.getVersion() + "' is not valid semver format");
        }

        // Validate platform version compatibility (min + max)
        if (!isBlank(manifest.getMinPlatformVersion()) || !isBlank(manifest.getMaxPlatformVersion())) {
            com.auraboot.framework.plugin.service.PlatformVersionChecker.CompatibilityResult versionCheck =
                    platformVersionChecker.check(manifest.getMinPlatformVersion(), manifest.getMaxPlatformVersion());
            switch (versionCheck.status()) {
                case INCOMPATIBLE:
                    errors.add(versionCheck.message());
                    break;
                case WARN_OLDER:
                    // Hard check: treat as validation error (platform too old)
                    errors.add(versionCheck.message());
                    break;
                case WARN_NEWER:
                    // Soft warning: prefix with [WARN] so callers can distinguish from errors
                    errors.add("[WARN] " + versionCheck.message());
                    break;
                case COMPATIBLE:
                    // No action needed
                    break;
            }
        }

        // Validate pluginType
        String pluginType = manifest.getEffectivePluginType();
        if (!List.of("config", "hybrid", "solution").contains(pluginType)) {
            errors.add("Invalid pluginType '" + pluginType + "', must be one of: config, hybrid, solution");
        }

        // Validate dslVersion
        int dslVersion = manifest.getEffectiveDslVersion();
        if (dslVersion < 1) {
            errors.add("dslVersion must be >= 1, got " + dslVersion);
        }

        Set<String> manifestModelCodes = validateCodeList(manifest.getModels(), ModelDefinitionDTO::getCode,
                "Model", errors, true);
        Set<String> manifestFieldCodes = validateCodeList(manifest.getFields(), FieldDefinitionDTO::getCode,
                "Field", errors, true);
        Set<String> manifestDictCodes = validateCodeList(manifest.getDicts(), DictDefinitionDTO::getCode,
                "Dictionary", errors, true);
        Set<String> manifestCommandCodes = validateCodeList(manifest.getCommands(), CommandDefinitionDTO::getCode,
                "Command", errors, true);
        Set<String> manifestPermissionCodes = validateCodeList(manifest.getPermissions(), PermissionDefinitionDTO::getCode,
                "Permission", errors, true);
        Set<String> manifestRoleCodes = validateCodeList(manifest.getRoles(), RoleDefinitionDTO::getCode,
                "Role", errors, true);
        Set<String> manifestMenuCodes = validateCodeList(manifest.getMenus(), MenuDefinitionDTO::getCode,
                "Menu", errors, true);
        validateCodeList(manifest.getPages(), PageSchemaDTO::getPageKey, "Page", errors, false);
        validateCodeList(manifest.getNamedQueries(), NamedQueryDefinitionDTO::getCode,
                "NamedQuery", errors, true);

        validateBasicFields(manifest, errors);

        Long tenantId = MetaContext.getCurrentTenantId();
        Map<String, Boolean> modelExistsCache = new HashMap<>();
        Map<String, Boolean> fieldExistsCache = new HashMap<>();
        Map<String, Boolean> dictExistsCache = new HashMap<>();
        Map<String, Boolean> permissionExistsCache = new HashMap<>();
        Map<String, Boolean> menuExistsCache = new HashMap<>();

        // Validate field->dict references. Dict can come from current plugin dicts or existing tenant dicts.
        if (manifest.getFields() != null) {
            for (FieldDefinitionDTO field : manifest.getFields()) {
                if (field == null || field.getCode() == null || field.getCode().isBlank()) {
                    continue;
                }
                String dictCode = field.getDictCode();
                if (dictCode == null || dictCode.isBlank()) {
                    continue;
                }
                if (manifestDictCodes.contains(dictCode)) {
                    continue;
                }

                boolean dictExistsInTenant = existsInTenant(tenantId, dictCode, dictExistsCache,
                        code -> resourceImporter.checkDictExists(tenantId, code));
                if (!dictExistsInTenant) {
                    errors.add("Field '" + field.getCode() + "' references missing dictionary: " + dictCode);
                }
            }
        }

        if (manifest.getModelFieldBindings() != null) {
            for (ModelFieldBindingDTO binding : manifest.getModelFieldBindings()) {
                if (binding == null) {
                    continue;
                }
                if (isBlank(binding.getModelCode())) {
                    errors.add("Binding has missing modelCode");
                } else if (!manifestModelCodes.contains(binding.getModelCode())
                        && !existsInTenant(tenantId, binding.getModelCode(), modelExistsCache,
                        code -> resourceImporter.checkModelExists(tenantId, code))) {
                    String msg = "Binding references missing model: " + binding.getModelCode();
                    errors.add(deferReferenceValidation ? "[WARN] " + msg : msg);
                }

                if (isBlank(binding.getFieldCode())) {
                    errors.add("Binding has missing fieldCode");
                } else if (!manifestFieldCodes.contains(binding.getFieldCode())
                        && !existsInTenant(tenantId, binding.getFieldCode(), fieldExistsCache,
                        code -> resourceImporter.checkFieldExists(tenantId, code))) {
                    errors.add("Binding references missing field: " + binding.getFieldCode());
                }
            }
        }

        if (manifest.getCommands() != null) {
            for (CommandDefinitionDTO command : manifest.getCommands()) {
                if (command == null || isBlank(command.getCode())) {
                    continue;
                }
                String modelCode = command.getModelCode();
                if (isBlank(modelCode)) {
                    errors.add("Command '" + command.getCode() + "' has missing modelCode");
                } else if (!manifestModelCodes.contains(modelCode)
                        && !existsInTenant(tenantId, modelCode, modelExistsCache,
                        code -> resourceImporter.checkModelExists(tenantId, code))) {
                    String msg = "Command '" + command.getCode() + "' references missing model: " + modelCode;
                    errors.add(deferReferenceValidation ? "[WARN] " + msg : msg);
                }
            }
        }

        if (manifest.getRoles() != null) {
            for (RoleDefinitionDTO role : manifest.getRoles()) {
                if (role == null || isBlank(role.getCode())) {
                    continue;
                }
                Set<String> rolePermissionCodes = role.getPermissions() == null
                        ? Set.of()
                        : role.getPermissions().stream()
                        .filter(code -> !isBlank(code))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
                for (String permissionCode : rolePermissionCodes) {
                    if (isBlank(permissionCode)) {
                        continue;
                    }
                    if (manifestPermissionCodes.contains(permissionCode)
                            || existsInTenant(tenantId, permissionCode, permissionExistsCache,
                            code -> resourceImporter.checkPermissionExists(tenantId, code))
                            // Model actions this plugin generates for its own models
                            // (baseline CRUD/import/export + command verbs) do not have
                            // permission rows yet at validation time; roles legitimately
                            // reference them and the import binds them post-generation.
                            || isGeneratedModelActionReference(permissionCode, manifestModelCodes,
                            manifest.getCommands())) {
                        continue;
                    }
                    String msg = "Role '" + role.getCode() + "' references missing permission: " + permissionCode;
                    errors.add(deferReferenceValidation ? "[WARN] " + msg : msg);
                }
                if (role.getPermissionPolicies() != null) {
                    for (RolePermissionPolicyDefinitionDTO policy : role.getPermissionPolicies()) {
                        if (policy == null || isBlank(policy.getPermissionCode())) {
                            continue;
                        }
                        String permissionCode = policy.getPermissionCode();
                        if (!rolePermissionCodes.contains(permissionCode)) {
                            errors.add("Role '" + role.getCode()
                                    + "' permissionPolicies references permission not assigned to role: "
                                    + permissionCode);
                        }
                        if (!manifestPermissionCodes.contains(permissionCode)
                                && !existsInTenant(tenantId, permissionCode, permissionExistsCache,
                                code -> resourceImporter.checkPermissionExists(tenantId, code))) {
                            String msg = "Role '" + role.getCode()
                                    + "' permissionPolicies references missing permission: " + permissionCode;
                            errors.add(deferReferenceValidation ? "[WARN] " + msg : msg);
                        }
                    }
                }
            }
        }

        if (manifest.getMenus() != null) {
            for (MenuDefinitionDTO menu : manifest.getMenus()) {
                if (menu == null || isBlank(menu.getCode())) {
                    continue;
                }

                if (!isBlank(menu.getParentCode())
                        && !manifestMenuCodes.contains(menu.getParentCode())
                        && !existsInTenant(tenantId, menu.getParentCode(), menuExistsCache,
                        code -> resourceImporter.checkMenuExists(tenantId, code))) {
                    String msg = "Menu '" + menu.getCode() + "' references missing parent menu: " + menu.getParentCode();
                    errors.add(deferReferenceValidation ? "[WARN] " + msg : msg);
                }

                if (!isBlank(menu.getPermissionCode())
                        && !manifestPermissionCodes.contains(menu.getPermissionCode())
                        && !existsInTenant(tenantId, menu.getPermissionCode(), permissionExistsCache,
                        code -> resourceImporter.checkPermissionExists(tenantId, code))) {
                    String msg = "Menu '" + menu.getCode() + "' references missing permission: " + menu.getPermissionCode();
                    errors.add(deferReferenceValidation ? "[WARN] " + msg : msg);
                }

                if (!isBlank(menu.getModelCode())
                        && !manifestModelCodes.contains(menu.getModelCode())
                        && !existsInTenant(tenantId, menu.getModelCode(), modelExistsCache,
                        code -> resourceImporter.checkModelExists(tenantId, code))) {
                    String msg = "Menu '" + menu.getCode() + "' references missing model: " + menu.getModelCode();
                    errors.add(deferReferenceValidation ? "[WARN] " + msg : msg);
                }
            }
        }

        if (manifest.getPages() != null) {
            // Keys that must live under "extension": silently dropping them has bitten us before.
            Set<String> mustBeInExtension = Set.of(
                    "options", "views", "kanbanConfig", "galleryConfig", "calendarConfig",
                    "enableMultiView", "defaultFilters", "columns", "dataSource", "relatedPages");
            for (PageSchemaDTO page : manifest.getPages()) {
                if (page == null || isBlank(page.getPageKey())) {
                    continue;
                }
                if (isBlank(page.getKind())) {
                    errors.add("Page '" + page.getPageKey() + "' has missing kind");
                }
                Map<String, Object> unknown = page.getUnknownFields();
                if (unknown != null) {
                    for (String key : unknown.keySet()) {
                        if (mustBeInExtension.contains(key)) {
                            errors.add("Page '" + page.getPageKey() + "' has top-level '" + key
                                    + "' which is not recognized — move it under \"extension\": { \"" + key + "\": ... }");
                        }
                    }
                }
            }
        }

        return errors;
    }

    /**
     * Closing reference-integrity sweep (decision core): given every command imported across the
     * full batch and the set of model codes provided by any plugin (or already installed in the
     * tenant), return a human-readable message for each command whose {@code modelCode} is provided
     * by nobody.
     *
     * <p>This re-enforces the references that
     * {@link #validateManifest(PluginManifestExtended, boolean)} deferred during a cyclic
     * cold-reset: a cross-plugin reference that resolves once the whole batch is imported is fine,
     * but a genuine typo / removed model still surfaces as a dangling reference so the reset fails
     * loudly instead of leaving a command bound to a non-existent model.
     *
     * @param commands       all commands across the imported batch (null / blank-model entries ignored)
     * @param providedModels model codes provided by any plugin in the batch or already installed
     * @return messages for commands referencing a model no plugin provides (empty if all resolve)
     */
    public List<String> findDanglingCommandModelRefs(List<CommandDefinitionDTO> commands,
                                                     Set<String> providedModels) {
        List<String> dangling = new ArrayList<>();
        if (commands == null) {
            return dangling;
        }
        Set<String> provided = providedModels != null ? providedModels : Set.of();
        for (CommandDefinitionDTO command : commands) {
            if (command == null || isBlank(command.getModelCode())) {
                continue;
            }
            if (!provided.contains(command.getModelCode())) {
                dangling.add("Command '" + command.getCode() + "' references missing model: "
                        + command.getModelCode() + " (no plugin provides it)");
            }
        }
        return dangling;
    }

    /**
     * Closing sweep counterpart for menu→parent-menu references deferred during a cyclic
     * cold-reset. Mirrors {@link #findDanglingCommandModelRefs(List, Set)}: a parent menu code
     * that resolves once the whole batch is imported is fine; one that resolves to nobody is a
     * genuinely dangling reference (typo / removed menu).
     *
     * @param menus         every currently-imported menu's manifest DTO (reconstructed from the
     *                      plugin-resource import snapshot), null / blank-parentCode entries ignored
     * @param providedMenus menu codes that currently exist for the tenant
     * @return messages for menus whose parentCode no menu provides (empty if all resolve)
     */
    public List<String> findDanglingMenuParentRefs(List<MenuDefinitionDTO> menus, Set<String> providedMenus) {
        List<String> dangling = new ArrayList<>();
        if (menus == null) {
            return dangling;
        }
        Set<String> provided = providedMenus != null ? providedMenus : Set.of();
        for (MenuDefinitionDTO menu : menus) {
            if (menu == null || isBlank(menu.getParentCode())) {
                continue;
            }
            if (!provided.contains(menu.getParentCode())) {
                dangling.add("Menu '" + menu.getCode() + "' references missing parent menu: "
                        + menu.getParentCode() + " (no plugin provides it)");
            }
        }
        return dangling;
    }

    /**
     * Closing sweep counterpart for menu→permission and role→permission references deferred
     * during a cyclic cold-reset. Mirrors {@link #findDanglingCommandModelRefs(List, Set)}.
     *
     * @param menus               every currently-imported menu's manifest DTO, null / blank
     *                            permissionCode entries ignored
     * @param roles               every currently-imported role's manifest DTO, null entries ignored
     * @param providedPermissions permission codes that currently exist for the tenant
     * @return messages for menus/roles whose permissionCode no plugin provides (empty if all resolve)
     */
    public List<String> findDanglingPermissionRefs(List<MenuDefinitionDTO> menus,
                                                    List<RoleDefinitionDTO> roles,
                                                    Set<String> providedPermissions) {
        List<String> dangling = new ArrayList<>();
        Set<String> provided = providedPermissions != null ? providedPermissions : Set.of();
        if (menus != null) {
            for (MenuDefinitionDTO menu : menus) {
                if (menu == null || isBlank(menu.getPermissionCode())) {
                    continue;
                }
                if (!provided.contains(menu.getPermissionCode())) {
                    dangling.add("Menu '" + menu.getCode() + "' references missing permission: "
                            + menu.getPermissionCode() + " (no plugin provides it)");
                }
            }
        }
        if (roles != null) {
            for (RoleDefinitionDTO role : roles) {
                if (role == null) {
                    continue;
                }
                if (role.getPermissions() != null) {
                    for (String permissionCode : role.getPermissions()) {
                        if (isBlank(permissionCode)) {
                            continue;
                        }
                        if (!provided.contains(permissionCode)) {
                            dangling.add("Role '" + role.getCode() + "' references missing permission: "
                                    + permissionCode + " (no plugin provides it)");
                        }
                    }
                }
                if (role.getPermissionPolicies() != null) {
                    for (RolePermissionPolicyDefinitionDTO policy : role.getPermissionPolicies()) {
                        if (policy == null || isBlank(policy.getPermissionCode())) {
                            continue;
                        }
                        if (!provided.contains(policy.getPermissionCode())) {
                            dangling.add("Role '" + role.getCode()
                                    + "' permissionPolicies references missing permission: "
                                    + policy.getPermissionCode() + " (no plugin provides it)");
                        }
                    }
                }
            }
        }
        return dangling;
    }

    private void validateBasicFields(PluginManifestExtended manifest, List<String> errors) {
        // Fields: require dataType
        if (manifest.getFields() != null) {
            for (FieldDefinitionDTO field : manifest.getFields()) {
                if (field == null || isBlank(field.getCode())) continue;
                if (isBlank(field.getDataType())) {
                    errors.add("Field '" + field.getCode() + "' has missing dataType");
                }
            }
        }

        // Menus: require at least one name source (localized, legacy, or code fallback already handled,
        // but type must be valid)
        if (manifest.getMenus() != null) {
            for (MenuDefinitionDTO menu : manifest.getMenus()) {
                if (menu == null || isBlank(menu.getCode())) continue;
                if (menu.getType() != null && menu.getType() < 0 || menu.getType() != null && menu.getType() > 2) {
                    errors.add("Menu '" + menu.getCode() + "' has invalid type: " + menu.getType() + " (must be 0=directory, 1=menu, 2=button)");
                }
                // Validate parentCode references within manifest
                if (!isBlank(menu.getParentCode())) {
                    boolean parentInManifest = manifest.getMenus().stream()
                            .anyMatch(m -> menu.getParentCode().equals(m.getCode()));
                    if (!parentInManifest) {
                        // Parent might exist in DB — just a warning, not an error
                        // (will be resolved at import time)
                    }
                }
            }
        }

        // Pages: require pageKey (already covered by validateCodeList) + kind for DSL pages
        if (manifest.getPages() != null) {
            for (PageSchemaDTO page : manifest.getPages()) {
                if (page == null || isBlank(page.getPageKey())) continue;
                // kind is required in V2 format
            }
        }

        // Models: require modelType
        if (manifest.getModels() != null) {
            for (ModelDefinitionDTO model : manifest.getModels()) {
                if (model == null || isBlank(model.getCode())) continue;
                if (isBlank(model.getModelType())) {
                    errors.add("Model '" + model.getCode() + "' has missing modelType");
                }
            }
        }

        // Commands: require modelCode (already covered above) + type
        if (manifest.getCommands() != null) {
            for (CommandDefinitionDTO command : manifest.getCommands()) {
                if (command == null || isBlank(command.getCode())) continue;
                if (isBlank(command.getType())) {
                    errors.add("Command '" + command.getCode() + "' has missing type");
                }
            }
        }

        // NamedQueries: require fromSql
        if (manifest.getNamedQueries() != null) {
            for (NamedQueryDefinitionDTO nq : manifest.getNamedQueries()) {
                if (nq == null || isBlank(nq.getCode())) continue;
                if (isBlank(nq.getFromSql())) {
                    errors.add("NamedQuery '" + nq.getCode() + "' has missing fromSql");
                }
            }
        }

        // Dicts: require at least code (name defaults to code via getEffectiveName)
        // Items validation: each item should have value
        if (manifest.getDicts() != null) {
            for (DictDefinitionDTO dict : manifest.getDicts()) {
                if (dict == null || isBlank(dict.getCode())) continue;
                if (dict.getItems() != null) {
                    for (int i = 0; i < dict.getItems().size(); i++) {
                        var item = dict.getItems().get(i);
                        if (item == null || isBlank(item.getValue())) {
                            errors.add("Dict '" + dict.getCode() + "' item[" + i + "] has missing value");
                        }
                    }
                }
            }
        }

        // SavedViews: require modelCode + viewType + name
        if (manifest.getSavedViews() != null) {
            for (SavedViewDefinitionDTO sv : manifest.getSavedViews()) {
                if (sv == null) continue;
                if (isBlank(sv.getModelCode())) {
                    errors.add("SavedView '" + sv.getName() + "' has missing modelCode");
                }
                if (isBlank(sv.getViewType())) {
                    errors.add("SavedView '" + sv.getName() + "' has missing viewType");
                }
                if (isBlank(sv.getName())) {
                    errors.add("SavedView has missing name");
                }
            }
        }

        // NotificationTemplates: require code + name + channel + bodyTemplate
        if (manifest.getNotificationTemplates() != null) {
            for (NotificationTemplateDefinitionDTO tpl : manifest.getNotificationTemplates()) {
                if (tpl == null) continue;
                if (isBlank(tpl.getCode())) {
                    errors.add("NotificationTemplate '" + tpl.getName() + "' has missing code");
                }
                if (isBlank(tpl.getName())) {
                    errors.add("NotificationTemplate '" + tpl.getCode() + "' has missing name");
                }
                if (isBlank(tpl.getChannel())) {
                    errors.add("NotificationTemplate '" + tpl.getCode() + "' has missing channel");
                }
                if (isBlank(tpl.getBodyTemplate())) {
                    errors.add("NotificationTemplate '" + tpl.getCode() + "' has missing bodyTemplate");
                }
            }
        }
    }

    private <T> Set<String> validateCodeList(List<T> list, Function<T, String> codeExtractor,
                                             String resourceName, List<String> errors, boolean useCodeLabel) {
        if (list == null || list.isEmpty()) {
            return Set.of();
        }
        Set<String> seen = new HashSet<>();
        Set<String> duplicated = new TreeSet<>();
        for (T item : list) {
            if (item == null) {
                continue;
            }
            String code = normalizeCode(codeExtractor.apply(item));
            if (code == null) {
                errors.add(resourceName + " has missing " + (useCodeLabel ? "code" : "pageKey"));
                continue;
            }
            if (!seen.add(code)) {
                duplicated.add(code);
            }
        }
        for (String dup : duplicated) {
            errors.add("Duplicate " + resourceName + " " + (useCodeLabel ? "code" : "pageKey") + ": " + dup);
        }
        return seen;
    }

    private boolean existsInTenant(Long tenantId, String key, Map<String, Boolean> cache, Function<String, Boolean> loader) {
        if (tenantId == null || isBlank(key)) {
            return false;
        }
        return cache.computeIfAbsent(key, k -> {
            try {
                return Boolean.TRUE.equals(loader.apply(k));
            } catch (Exception ex) {
                log.debug("Tenant existence check failed: key={}, message={}", logSafe(k), logSafe(ex.getMessage()));
                return false;
            }
        });
    }

    private String normalizeCode(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * True when {@code permissionCode} names a model action (model.&lt;modelCode&gt;.&lt;action&gt;)
     * that this import will itself generate for one of the models it provides: the action
     * set of {@code autoAssignPermissions} is baseline CRUD/import/export plus command
     * verbs. Restricting the check to manifest-provided models keeps the escape hatch from
     * whitelisting references to other plugins' models.
     */
    private boolean isGeneratedModelActionReference(String permissionCode, Set<String> manifestModelCodes,
                                                    List<CommandDefinitionDTO> manifestCommands) {
        if (!permissionCode.startsWith("model.")) {
            return false;
        }
        String withoutPrefix = permissionCode.substring("model.".length());
        int lastDot = withoutPrefix.lastIndexOf('.');
        if (lastDot <= 0 || lastDot == withoutPrefix.length() - 1) {
            return false;
        }
        String modelCode = withoutPrefix.substring(0, lastDot);
        String action = withoutPrefix.substring(lastDot + 1);
        if (!manifestModelCodes.contains(modelCode) || manifestCommands == null) {
            return false;
        }
        Map<String, String> execTypeByCommandCode = new LinkedHashMap<>();
        for (CommandDefinitionDTO cmd : manifestCommands) {
            if (cmd == null || !modelCode.equals(cmd.getModelCode()) || isBlank(cmd.getCode())) {
                continue;
            }
            String execType = !isBlank(cmd.getType()) ? cmd.getType().toLowerCase() : null;
            execTypeByCommandCode.putIfAbsent(cmd.getCode(), execType);
        }
        return commandActionDeriver.deriveActionsFromCommandExecTypes(modelCode, execTypeByCommandCode)
                .contains(action);
    }

}
