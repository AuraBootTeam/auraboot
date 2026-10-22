package com.auraboot.framework.plugin.service.impl;

import static com.auraboot.framework.plugin.service.PluginResourceRecords.createResourceRecord;
import com.auraboot.framework.application.tenant.MetaContext;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.auraboot.framework.meta.entity.Model;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.decision.service.DecisionUsageIndexService;
import com.auraboot.framework.permission.dto.PermissionCreateRequest;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.permission.service.DataScopeService;
import com.auraboot.framework.permission.service.RolePermissionService;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.PermissionDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.dto.imports.RoleDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.RoleDataScopeDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.RolePermissionPolicyDefinitionDTO;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.common.util.UlidGenerator;
import com.auraboot.framework.common.constant.StatusConstants;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/** Imports roles, permissions, grants and data-scope policies. */
final class PluginAccessPolicyImporter {
    // Preserve virtual calls on the original facade receiver.
    private final PluginResourceImporterImpl facadeReceiver;
    private final PermissionService permissionService;
    private final DataScopeService dataScopeService;
    private final RolePermissionService rolePermissionService;
    private final RoleService roleService;
    private final UserRoleService userRoleService;
    private final RoleMapper roleMapper;
    private final ObjectProvider<DecisionUsageIndexService> usageIndexServiceProvider;
    private final MetaModelMapper metaModelMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;
    private final PluginRecordMapper pluginRecordMapper;
    private final Logger log;
    private final PluginImportSupport support;

    PluginAccessPolicyImporter(
            PluginResourceImporterImpl facadeReceiver,
            PermissionService permissionService,
            DataScopeService dataScopeService,
            RolePermissionService rolePermissionService,
            RoleService roleService,
            UserRoleService userRoleService,
            RoleMapper roleMapper,
            ObjectProvider<DecisionUsageIndexService> usageIndexServiceProvider,
            MetaModelMapper metaModelMapper,
            PermissionMapper permissionMapper,
            RolePermissionMapper rolePermissionMapper,
            PluginRecordMapper pluginRecordMapper,
            Logger log,
            PluginImportSupport support) {
        this.facadeReceiver = facadeReceiver;
        this.permissionService = permissionService;
        this.dataScopeService = dataScopeService;
        this.rolePermissionService = rolePermissionService;
        this.roleService = roleService;
        this.userRoleService = userRoleService;
        this.roleMapper = roleMapper;
        this.usageIndexServiceProvider = usageIndexServiceProvider;
        this.metaModelMapper = metaModelMapper;
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
        this.pluginRecordMapper = pluginRecordMapper;
        this.log = log;
        this.support = support;
    }

    public boolean checkPermissionExists(Long tenantId, String code) {
        try {
            PermissionDTO perm = permissionService.findByCode(code);
            return perm != null;
        } catch (Exception e) {
            log.debug("checkPermissionExists treating exception as 'not exists' for code={}: {}",
                    PluginImportSupport.logSafe(code), PluginImportSupport.logSafe(e.toString()));
            return false;
        }
    }

    public boolean checkRoleExists(Long tenantId, String code) {
        return roleMapper.existsByCode(tenantId, code);
    }

    public PluginResource importPermission(PermissionDefinitionDTO dto, String pluginPid, String importId,
                                            Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        boolean exists = facadeReceiver.checkPermissionExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Permission already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PERMISSION,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (support.shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.PERMISSION, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.PERMISSION, PluginImportSupport.logSafe(dto.getCode()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PERMISSION,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // Update existing permission
            PermissionDTO existingPerm = permissionService.findByCode(dto.getCode());

            String dataScopeConfigJson = dto.getDataScopeConfig() != null ? support.toJson(dto.getDataScopeConfig()) : null;
            String extensionJson = dto.getExtension() != null ? support.toJson(dto.getExtension()) : null;
            String tagsArray = dto.getTags() != null ? "{" + String.join(",", dto.getTags()) + "}" : null;
            String policySchemaJson = dto.getPolicySchema() != null ? support.toJson(dto.getPolicySchema()) : null;

            permissionMapper.updateForPluginImport(
                    dto.getEffectiveName(), dto.getDescription(), dto.getCategory(),
                    dto.getResourceType(), dto.getResourceCode(), dto.getAction(),
                    dto.getDataScopeType(), dataScopeConfigJson, extensionJson, tagsArray,
                    policySchemaJson,
                    pluginPid, tenantId, dto.getCode());

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PERMISSION,
                    existingPerm.getPid(), null, dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, null, null);
        } else {
            // Check for soft-deleted permission and resurrect if found
            String resurrectPid = support.resurrectSoftDeleted("ab_permission", "code", dto.getCode(), tenantId, pluginPid, null);
            if (resurrectPid != null) {
                log.info("Resurrected soft-deleted permission: code={}, pid={}",
                        PluginImportSupport.logSafe(dto.getCode()), PluginImportSupport.logSafe(resurrectPid));
                return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PERMISSION,
                        resurrectPid, null, dto.getCode(), dto.getEffectiveName(),
                        ResourceAction.CREATE, null, null);
            }

            // Create new permission via service with pluginPid
            PermissionCreateRequest request = new PermissionCreateRequest();
            request.setCode(dto.getCode());
            request.setName(dto.getEffectiveName());
            request.setDescription(dto.getDescription());
            request.setResourceType(dto.getResourceType() != null ? dto.getResourceType() : "menu");
            request.setResourceCode(dto.getResourceCode() != null ? dto.getResourceCode() : dto.getCode());
            request.setAction(dto.getAction() != null ? dto.getAction() : "view");
            request.setDataScopeType(dto.getDataScopeType());
            request.setDataScopeConfig(dto.getDataScopeConfig());
            request.setExtension(dto.getExtension());
            request.setTags(dto.getTags() != null ? dto.getTags().toArray(new String[0]) : null);
            request.setPolicySchema(dto.getPolicySchema());
            request.setPluginPid(pluginPid);  // Set plugin_pid via request

            PermissionDTO created = permissionService.create(request);

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.PERMISSION,
                    created.getPid(), created.getId(), dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    public PluginResource importRole(RoleDefinitionDTO dto, String pluginPid, String importId,
                                      Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        // Guard: platform-only role codes cannot be imported into business tenants
        if (com.auraboot.framework.rbac.constant.RoleConstants.isPlatformOnly(dto.getCode())) {
            String scopeType = dto.getScopeType() != null ? dto.getScopeType() : "tenant";
            if (!"global".equals(scopeType)) {
                throw new PluginException(
                    "Role code '" + dto.getCode() + "' is reserved for platform level (scope_type=global). "
                    + "Plugin cannot create it in a business tenant.");
            }
        }

        boolean exists = facadeReceiver.checkRoleExists(tenantId, dto.getCode());

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.ERROR) {
            throw new PluginException("Role already exists: " + dto.getCode());
        }

        if (exists && conflictStrategy == ImportRequest.ConflictStrategy.SKIP) {
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.ROLE,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (support.shouldSkipForOverwriteSafe(tenantId, conflictStrategy, ResourceType.ROLE, dto.getCode())) {
            log.info("Skipping user-modified resource: {} {}", ResourceType.ROLE, PluginImportSupport.logSafe(dto.getCode()));
            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.ROLE,
                    null, null, dto.getCode(), dto.getEffectiveName(), ResourceAction.SKIP, null, null);
        }

        if (exists) {
            // Update existing role
            String scopeContentJson = dto.getScopeContent() != null ? support.toJson(dto.getScopeContent()) : null;

            roleMapper.updateForPluginImport(
                    dto.getEffectiveName(), dto.getDescription(), dto.getType(),
                    dto.getPriority(), dto.getIsDefault(), dto.getIsSystem(),
                    dto.getScopeType(), scopeContentJson, dto.getDefaultDataScopeType(),
                    pluginPid, tenantId, dto.getCode());

            // Reconcile role-permission bindings and data-scope defaults.
            Long roleId = roleMapper.findIdByCode(tenantId, dto.getCode());
            updateRolePermissions(roleId, dto, tenantId, pluginPid);
            applyRoleDataScopes(roleId, dto, tenantId);
            assignSeedMembers(roleId, dto, tenantId);

            String existingPid = roleMapper.findPidByCode(tenantId, dto.getCode());

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.ROLE,
                    existingPid, roleId, dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.UPDATE, null, null);
        } else {
            // Create new role via service
            Role role = new Role();
            role.setPid(UlidGenerator.generate());
            role.setTenantId(tenantId);
            role.setCode(dto.getCode());
            role.setName(dto.getEffectiveName());
            role.setDescription(dto.getDescription());
            role.setType(dto.getType() != null ? dto.getType() : "custom");
            role.setPriority(dto.getPriority() != null ? dto.getPriority() : 100);
            role.setIsDefault(dto.getIsDefault() != null ? dto.getIsDefault() : false);
            role.setIsSystem(dto.getIsSystem() != null ? dto.getIsSystem() : false);
            role.setScopeType(dto.getScopeType() != null ? dto.getScopeType() : "tenant");
            role.setDefaultDataScopeType(dto.getDefaultDataScopeType());
            role.setStatus(StatusConstants.ACTIVE);
            role.setDeletedFlag(false);
            role.setCreatedAt(Instant.now());
            role.setUpdatedAt(Instant.now());

            Role created = roleService.createRole(role);

            // Update plugin_pid
            roleMapper.updatePluginPidById(pluginPid, created.getId());

            // Create role-permission bindings
            updateRolePermissions(created.getId(), dto, tenantId, pluginPid);
            applyRoleDataScopes(created.getId(), dto, tenantId);
            assignSeedMembers(created.getId(), dto, tenantId);

            return createResourceRecord(pluginPid, importId, tenantId, ResourceType.ROLE,
                    created.getPid(), created.getId(), dto.getCode(), dto.getEffectiveName(),
                    ResourceAction.CREATE, null, null);
        }
    }

    private void assignSeedMembers(Long roleId, RoleDefinitionDTO dto, Long tenantId) {
        if (roleId == null || dto.getSeedMembers() == null || dto.getSeedMembers().isEmpty()) {
            return;
        }
        for (String seedMember : dto.getSeedMembers()) {
            if (seedMember == null || seedMember.isBlank()) {
                continue;
            }
            String normalized = seedMember.trim().toUpperCase(java.util.Locale.ROOT);
            if (!"CURRENT_MEMBER".equals(normalized)) {
                throw new PluginException("Unsupported role seedMembers value '" + seedMember
                        + "' for role " + dto.getCode() + ". Supported: CURRENT_MEMBER");
            }
            Long memberId = MetaContext.getCurrentMemberId();
            if (memberId == null) {
                throw new PluginException("Role " + dto.getCode()
                        + " declares seedMembers CURRENT_MEMBER but no current member is available");
            }
            userRoleService.assignRolesToMember(memberId, List.of(roleId), tenantId, MetaContext.getCurrentUserId());
        }
    }

    /**
     * Reconcile declared role permissions for an installed plugin directory: validate
     * the directory and installed version, then strictly check every declared role and
     * permission resolves and is bound for the current tenant. The caller owns the
     * transaction boundary.
     */
    public int reconcileDirectoryRolePermissions(String directoryPath,
                                                 PluginDirectoryLoader directoryLoader,
                                                 PluginRecordMapper pluginRecordMapper) {
        Path directory = Path.of(directoryPath).normalize();
        if (!directory.isAbsolute() || directory.toString().contains("..")) {
            throw new PluginException("Role reconciliation requires an absolute plugin directory");
        }
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new PluginException("Tenant context is required for role reconciliation");
        }
        PluginManifestExtended manifest = directoryLoader.loadFromDirectory(directory);
        PluginRecord installed = pluginRecordMapper.findByTenantAndPluginId(manifest.getPluginId());
        if (installed == null || !tenantId.equals(installed.getTenantId())
                || !Objects.equals(installed.getVersion(), manifest.getVersion())) {
            throw new PluginException("Role reconciliation requires the same installed plugin version");
        }
        return facadeReceiver.reconcileDeclaredRolesStrictly(manifest, tenantId);
    }

    int reconcileDeclaredRolesStrictly(PluginManifestExtended manifest, Long tenantId) {
        if (manifest.getRoles() == null || manifest.getRoles().isEmpty()) return 0;
        Map<String, Role> roles = roleService.findByTenantId(tenantId).stream()
                .collect(Collectors.toMap(Role::getCode, Function.identity()));
        int checked = 0;
        for (RoleDefinitionDTO declaration : manifest.getRoles()) {
            if (declaration == null || !declaration.isValid()) {
                throw new PluginException("Invalid role declaration during batch reconciliation");
            }
            Role role = roles.get(declaration.getCode());
            if (role == null) throw new PluginException("Imported role not found: " + declaration.getCode());
            List<PermissionDTO> permissions = new ArrayList<>();
            for (String code : Optional.ofNullable(declaration.getPermissions()).orElse(List.of())) {
                PermissionDTO permission = permissionService.findByCode(code);
                if (permission == null || permission.getId() == null) {
                    throw new PluginException("Unresolved declared permission: " + declaration.getCode() + ":" + code);
                }
                permissions.add(permission);
            }
            facadeReceiver.reconcileRolePermissions(declaration, tenantId);
            for (PermissionDTO permission : permissions) {
                if (rolePermissionMapper.countByRoleAndPermission(role.getId(), permission.getId(), tenantId) <= 0) {
                    throw new PluginException("Declared permission remains unbound: "
                            + declaration.getCode() + ":" + permission.getCode());
                }
            }
            checked++;
        }
        return checked;
    }

    public boolean reconcileRolePermissions(RoleDefinitionDTO dto, Long tenantId) {
        if (dto == null || dto.getCode() == null || dto.getCode().isBlank()) {
            return false;
        }
        Long roleId = roleMapper.findIdByCode(tenantId, dto.getCode());
        if (roleId == null) {
            // Fail-closed: a declared role that cannot be resolved during strict
            // reconciliation is an inconsistent import, not a skippable gap.
            throw new PluginException("Role permission reconciliation failed: declared role '"
                    + dto.getCode() + "' does not exist in this tenant");
        }
        int before = rolePermissionMapper.findPermissionIdsByRole(roleId).size();
        facadeReceiver.updateRolePermissions(roleId, dto, tenantId, null, true);
        int after = rolePermissionMapper.findPermissionIdsByRole(roleId).size();
        if (after > before) {
            log.info("Role permission reconciliation bound {} additional codes for role {}",
                    after - before, PluginImportSupport.logSafe(dto.getCode()));
        }
        return after > before;
    }

    private void updateRolePermissions(Long roleId, RoleDefinitionDTO dto, Long tenantId, String pluginPid) {
        facadeReceiver.updateRolePermissions(roleId, dto, tenantId, pluginPid, false);
    }

    /**
     * Bind a role's declared permissions.
     *
     * <p>Two strictness modes:</p>
     * <ul>
     *   <li><b>Role-import stage (deferred, {@code strictResolution=false})</b>: permissions
     *       generated by this plugin's own model publication ({@code model.<code>.<action>})
     *       do not exist yet, because the ROLE import stage runs before auto-publish.
     *       Unresolved codes are logged with role and code and left to the strict
     *       post-publication reconcile; binding failures on resolvable codes are
     *       re-attempted there.</li>
     *   <li><b>Post-publication reconcile ({@code strictResolution=true})</b>: every declared
     *       code must resolve to a registered permission and bind, otherwise the plugin
     *       import fails with a locatable error (role + code + missing-provider analysis).
     *       A stack whose composition cannot satisfy the declarations is rejected instead of
     *       silently degrading the role — the historic warn+skip swallowed bad combinations
     *       until they surfaced rounds later as missing-access findings.</li>
     * </ul>
     *
     * <p>Platform-registered codes (source=system built-ins such as {@code meta.command.execute}
     * and {@code dashboard.read}, and data-view permissions with {@code resource_type=data})
     * need no exemption here: they are registered rows in ab_permission, so they resolve and
     * bind like any other code — declared codes are never exempted from binding.</p>
     */
    void updateRolePermissions(Long roleId, RoleDefinitionDTO dto, Long tenantId, String pluginPid,
                               boolean strictResolution) {
        List<String> permissionCodes = dto.getPermissions();
        if (permissionCodes == null || permissionCodes.isEmpty()) {
            return;
        }
        Map<String, RolePermissionPolicyDefinitionDTO> policyByPermission = rolePermissionPoliciesByCode(dto);

        List<String> deferred = new ArrayList<>();
        for (String permCode : permissionCodes) {
            PermissionDTO perm = findRegisteredPermissionOrNull(permCode);
            if (perm == null || perm.getId() == null) {
                if (strictResolution) {
                    throw new PluginException(unresolvedDeclaredPermissionMessage(dto.getCode(), permCode, tenantId));
                }
                deferred.add(permCode);
                continue;
            }

            try {
                // Check if binding exists
                int count = rolePermissionMapper.countByRoleAndPermission(roleId, perm.getId(), tenantId);

                if (count == 0) {
                    // Use role-permission service so role default data scope is inherited.
                    rolePermissionService.assignPermissionsToRole(roleId, List.of(perm.getId()));
                } else {
                    rolePermissionService.inheritDefaultDataScope(roleId, List.of(perm.getId()));
                }
                applyRolePermissionPolicy(roleId, perm, policyByPermission.get(permCode));
            } catch (Exception e) {
                if (strictResolution) {
                    throw new PluginException("Failed to bind declared permission '" + permCode
                            + "' to role '" + dto.getCode() + "': " + e.getMessage(), e);
                }
                // Role-import stage stays best-effort for already-resolvable codes: the
                // strict post-publication reconcile re-binds every declaration and fails
                // the import if anything remains unbound, so a transient race with another
                // import gets a second chance instead of aborting the whole plugin here.
                log.warn("Failed to bind permission {} to role {} at role-import stage; strict reconcile will re-attempt: {}",
                        PluginImportSupport.logSafe(permCode), PluginImportSupport.logSafe(dto.getCode()), PluginImportSupport.logSafe(e.getMessage()), e);
            }
        }
        if (!deferred.isEmpty()) {
            log.info("Role {} defers {} declared permission code(s) that are not registered yet "
                            + "(own-plugin model actions are generated after the role stage); the strict "
                            + "post-publication reconcile must resolve them: {}",
                    PluginImportSupport.logSafe(dto.getCode()), deferred.size(), String.join(", ", deferred));
        }
    }

    /**
     * Resolve a declared permission code to its registered row, or null when the code is
     * not registered for the tenant. The permission service signals an unregistered code
     * by throwing a not-found exception rather than returning null — both shapes mean
     * "not registered" here; any other failure inside the service is treated the same
     * way, so the strict reconcile reports the declaration as unresolvable (with the
     * provider analysis) instead of aborting with a bare service error.
     */
    private PermissionDTO findRegisteredPermissionOrNull(String permCode) {
        try {
            return permissionService.findByCode(permCode);
        } catch (Exception absent) {
            return null;
        }
    }

    /**
     * Build a locatable error for a declared role permission that does not resolve.
     * Names the role and code, and for the dominant {@code model.<code>.<action>} family
     * checks the backing model to separate "provider plugin missing from the stack" from
     * "model present but its permission never registered" — the former names the missing
     * provider gap, the latter points at the owning plugin.
     */
    private String unresolvedDeclaredPermissionMessage(String roleCode, String permCode, Long tenantId) {
        StringBuilder message = new StringBuilder("Role '").append(roleCode)
                .append("' declares permission '").append(permCode)
                .append("' which does not resolve to a registered permission in this tenant");
        String modelCode = PluginResourceImporterImpl.modelCodeOfGeneratedActionPermission(permCode);
        if (modelCode != null) {
            List<Model> models = metaModelMapper.selectList(new QueryWrapper<Model>()
                    .eq("tenant_id", tenantId)
                    .eq("code", modelCode)
                    .eq("is_current", true)
                    .eq("deleted_flag", false));
            Model model = models.isEmpty() ? null : models.get(0);
            if (model == null) {
                message.append(". The referenced model '").append(modelCode)
                        .append("' does not exist either, so the provider plugin that publishes it is missing "
                        + "from this stack; import that provider plugin and declare it in plugin.json "
                        + "dependencies, or fix the declared permission code");
            } else {
                String ownerPluginId = null;
                if (model.getPluginPid() != null && pluginRecordMapper.findByPid(model.getPluginPid()) != null) {
                    ownerPluginId = pluginRecordMapper.findByPid(model.getPluginPid()).getPluginId();
                }
                message.append(". The referenced model '").append(modelCode).append("' exists");
                if (ownerPluginId != null) {
                    message.append(" (owned by plugin '").append(ownerPluginId).append("')");
                }
                message.append(" but its permission was never registered; re-import the owning plugin "
                        + "or fix the declared permission code");
            }
        } else {
            message.append("; the provider plugin that registers it is missing from this stack or the "
                    + "declared code is wrong; import the provider plugin and declare it in plugin.json "
                    + "dependencies, or fix the declared permission code");
        }
        return message.toString();
    }

    /**
     * Extract the model code from a generated model-action permission code
     * ({@code model.<modelCode>.<action>}); returns null for other code shapes.
     */
    static String modelCodeOfGeneratedActionPermission(String permCode) {
        if (permCode == null || !permCode.startsWith("model.")) {
            return null;
        }
        String rest = permCode.substring("model.".length());
        int lastDot = rest.lastIndexOf('.');
        if (lastDot <= 0 || lastDot >= rest.length() - 1) {
            return null;
        }
        return rest.substring(0, lastDot);
    }

    /**
     * Materialize security-sensitive per-permission scope declarations after role bindings exist.
     * Missing permissions or incomplete permission metadata fail the import instead of silently
     * widening the role to its default scope.
     */
    private void applyRoleDataScopes(Long roleId, RoleDefinitionDTO dto, Long tenantId) {
        List<RoleDataScopeDefinitionDTO> definitions = dto.getDataScopes();
        if (definitions == null || definitions.isEmpty()) {
            return;
        }
        if (roleId == null) {
            throw new PluginException("Cannot apply data scopes for unresolved role " + dto.getCode());
        }
        for (RoleDataScopeDefinitionDTO definition : definitions) {
            if (definition == null || !definition.isValid()) {
                throw new PluginException("Invalid data scope declaration for role " + dto.getCode());
            }
            PermissionDTO permission = permissionService.findByCode(definition.getPermissionCode());
            if (permission == null || permission.getId() == null
                    || permission.getResourceCode() == null || permission.getAction() == null) {
                throw new PluginException("Role " + dto.getCode()
                        + " declares data scope for an unresolved permission: "
                        + definition.getPermissionCode());
            }
            if (dto.getPermissions() == null
                    || !dto.getPermissions().contains(definition.getPermissionCode())
                    || rolePermissionMapper.countByRoleAndPermission(
                            roleId, permission.getId(), tenantId) <= 0) {
                throw new PluginException("Role " + dto.getCode()
                        + " declares data scope for a permission that is not bound to the role: "
                        + definition.getPermissionCode());
            }
            dataScopeService.setScope(
                    tenantId,
                    roleId,
                    permission.getResourceCode(),
                    permission.getAction(),
                    definition.getScopeType(),
                    definition.effectiveMergeStrategy());
        }
    }

    private Map<String, RolePermissionPolicyDefinitionDTO> rolePermissionPoliciesByCode(RoleDefinitionDTO dto) {
        if (dto.getPermissionPolicies() == null || dto.getPermissionPolicies().isEmpty()) {
            return Map.of();
        }
        return dto.getPermissionPolicies().stream()
                .filter(Objects::nonNull)
                .filter(RolePermissionPolicyDefinitionDTO::isValid)
                .collect(Collectors.toMap(
                        RolePermissionPolicyDefinitionDTO::getPermissionCode,
                        Function.identity(),
                        (left, right) -> right,
                        LinkedHashMap::new));
    }

    private void applyRolePermissionPolicy(
            Long roleId,
            PermissionDTO permission,
            RolePermissionPolicyDefinitionDTO policy) {
        if (policy == null || permission == null || permission.getId() == null) {
            return;
        }
        RolePermission binding = rolePermissionMapper.findByRoleAndPermission(roleId, permission.getId());
        if (binding == null || binding.getId() == null) {
            log.warn("Role-permission binding not found for seeded policy: roleId={}, permissionCode={}",
                    roleId, PluginImportSupport.logSafe(policy.getPermissionCode()));
            return;
        }
        binding.setConditions(policy.getConditions());
        rolePermissionMapper.updateById(binding);
        refreshPermissionPolicyUsageIndex(binding);
    }

    private void refreshPermissionPolicyUsageIndex(RolePermission binding) {
        if (binding == null || binding.getPid() == null || binding.getPid().isBlank()) {
            return;
        }
        DecisionUsageIndexService usageIndexService = usageIndexServiceProvider.getIfAvailable();
        if (usageIndexService != null) {
            usageIndexService.refreshSource("PERMISSION_POLICY", binding.getPid());
        }
    }

}
