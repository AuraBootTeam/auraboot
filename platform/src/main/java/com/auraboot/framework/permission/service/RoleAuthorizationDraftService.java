package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.capability.CapabilityRegistryService;
import com.auraboot.framework.permission.capability.CapabilitySelectionPreview;
import com.auraboot.framework.permission.capability.CapabilityViewService;
import com.auraboot.framework.permission.dto.DataScopeUpdateRequest;
import com.auraboot.framework.permission.dto.RoleAuthorizationDraftPreview;
import com.auraboot.framework.permission.dto.RoleAuthorizationDraftRequest;
import com.auraboot.framework.permission.dto.RoleAuthorizationDraftSnapshot;
import com.auraboot.framework.permission.dto.RoleBoundaryMigrationPreview;
import com.auraboot.framework.permission.dto.PermissionDTO;
import com.auraboot.framework.permission.entity.RoleDataScope;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.auraboot.framework.permission.enums.DataScopeType;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.entity.RolePermission;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import com.auraboot.framework.rbac.service.RoleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** A single database transaction for role duties, defaults and per-action record scopes. */
@Service
@RequiredArgsConstructor
public class RoleAuthorizationDraftService {
    private final RoleAuthorizationWriteLock writes;
    private final RoleAuthorizationAuthoringAccess authoringAccess;
    private final RoleFieldPolicyDraftService fieldPolicies;
    @org.springframework.beans.factory.annotation.Autowired
    private com.auraboot.framework.menu.service.RoleNavigationService roleNavigation;
    @org.springframework.beans.factory.annotation.Autowired
    private RoleFieldAccessService fieldAccess;
    @org.springframework.beans.factory.annotation.Autowired
    private RoleGrantFieldScopeService grantFields;
    private final RoleService roles;
    private final RolePermissionMapper grants;
    private final PermissionService permissions;
    private final CapabilityRegistryService catalog;
    private final CapabilityViewService capabilities;
    private final PermissionMatrixService matrix;
    private final DataScopeService scopes;
    private final ObjectMapper json;
    private final com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper policyBindings;
    private final com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper dataPolicies;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.auraboot.framework.plugin.pf4j.ExtensionRegistry extensions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataPermissionAuthoringAccess migrationAccess;

    @org.springframework.beans.factory.annotation.Autowired
    private NativeBusinessBoundaryCatalog nativeBoundaries;

    @Transactional
    public RoleAuthorizationDraftSnapshot snapshot(String rolePid) {
        writes.lock();
        return snapshot(resolve(rolePid), businessFieldRoots());
    }

    /** Inspect complete source bindings; explicit migration edits still use apply and its revision. */
    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public RoleBoundaryMigrationPreview migrationPreview(String rolePid) {
        writes.lock();
        authoringAccess.requireWrite();
        if (migrationAccess == null || !TransactionSynchronizationManager.isActualTransactionActive()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(), java.sql.Connection.TRANSACTION_REPEATABLE_READ)) {
            throw new AccessDeniedException("Migration inspection requires permission management and a consistent transaction");
        }
        migrationAccess.requireRead();
        Role role = resolve(rolePid);
        authoringAccess.requireTarget(role.getId());
        var definitions = permissions.findAllActive().stream()
                .filter(permission -> permission.getTenantId() == null || Objects.equals(role.getTenantId(), permission.getTenantId()))
                .collect(java.util.stream.Collectors.toMap(
                permission -> permission.getId(), java.util.function.Function.identity()));
        var source = grants.findByRole(role.getId());
        if (source.stream().anyMatch(binding -> !Objects.equals(role.getTenantId(), binding.getTenantId())
                || !Objects.equals(role.getId(), binding.getRoleId()) || binding.getPid() == null || binding.getPid().isBlank())) throw invalid();
        var scoped = scopes.getScopesByRole(role.getTenantId(), role.getId());
        if (scoped.stream().anyMatch(scope -> !Objects.equals(scope.getTenantId(), role.getTenantId())
                || !Objects.equals(scope.getRoleId(), role.getId()))) throw invalid();
        var roots = businessFieldRoots();
        var views = source.stream().sorted(Comparator.comparing(RolePermission::getPid)).map(binding -> {
            var definition = definitions.get(binding.getPermissionId());
            return new RoleBoundaryMigrationPreview.Binding(
                    binding.getPid(), definition == null ? null : definition.getPid(),
                    definition == null ? null : definition.getCode(), binding.getGrantType(), binding.getPriority(),
                    binding.getEffectiveDate(), binding.getExpiryDate(), binding.getStatus(),
                    json.valueToTree(binding.getConditions()), json.valueToTree(binding.getConditionAst()),
                    json.valueToTree(binding.getDataScopeAst()), json.valueToTree(binding.getFieldScope()), binding.getSourceRef(), binding.getSnapshotId(),
                    RoleGrantSourceOwnership.of(binding).name());
        }).toList();
        var boundaries = roots.stream().map(root -> new RoleBoundaryMigrationPreview.Boundary(
                root.command(), root.model(), root.action(), root.permissions().stream().map(code -> {
                    var definition = migrationDefinition(definitions.values(), role.getTenantId(), code);
                    var owners = views.stream().filter(binding -> code.equals(binding.permissionCode())
                            || "*".equals(binding.permissionCode())).map(binding -> binding.bindingPid()).toList();
                    var matching = definition == null ? List.<RoleDataScope>of()
                            : scoped.stream().filter(scope -> Objects.equals(definition.getResourceCode(), scope.getResourceCode())
                                && Objects.equals(definition.getAction(), scope.getActionCode())).toList();
                    String type = matching.isEmpty() ? role.getDefaultDataScopeType() : matching.getFirst().getScopeType();
                    String status = "SCOPE_CONFIGURED_REVIEW_REQUIRED";
                    if (definition == null || definition.getResourceCode() == null || definition.getAction() == null
                            || definition.getResourceCode().isBlank() || definition.getAction().isBlank()
                            || !("operation".equalsIgnoreCase(definition.getResourceType()) || "data".equalsIgnoreCase(definition.getResourceType())))
                        status = "PERMISSION_MAPPING_UNAVAILABLE";
                    else if ("view".equals(definition.getAction()) && code.equals(definition.getResourceCode()))
                        status = "ENTRY_FALLBACK_REQUIRES_MAPPING";
                    else if (root.definitionSource() != null && (!root.model().equals(definition.getResourceCode())
                            || !(root.action().equals(definition.getAction()) || "manage".equals(definition.getAction()))))
                        status = "BOUNDARY_MAPPING_REQUIRES_REVIEW";
                    else if (owners.isEmpty()) status = "MISSING_BUSINESS_GRANT";
                    else if (matching.size() > 1 || matching.stream().anyMatch(scope -> scope.getScopeConfig() != null
                            && !(scope.getScopeConfig() instanceof Map<?, ?> config && config.isEmpty())))
                        status = "UNSUPPORTED_SCOPE_CONFIGURATION";
                    else if (type == null || type.isBlank()) status = "REQUIRES_EXPLICIT_SCOPE_DECISION";
                    else if (!Set.of("all", "none", "self", "dept", "dept_and_sub", "team").contains(type.toLowerCase(java.util.Locale.ROOT)))
                        status = "UNSUPPORTED_SCOPE_CONFIGURATION";
                    return new RoleBoundaryMigrationPreview.Operation(code,
                            definition == null ? null : definition.getPid(), definition == null ? null : definition.getResourceCode(),
                            definition == null ? null : definition.getAction(), type, matching.isEmpty() ? (role.getDefaultDataScopeType() == null ? "UNCONFIGURED" : "ROLE_DEFAULT") : "EXPLICIT_ACTION",
                            status, owners);
                }).toList())).toList();
        var policies = policyBindings.findByRolePid(role.getTenantId(), role.getPid()).stream()
                .map(binding -> dataPolicies.findByPid(role.getTenantId(), binding.getPolicyPid()))
                .map(policy -> {
                    if (policy == null || !Objects.equals(policy.getTenantId(), role.getTenantId())) throw invalid();
                    return new RoleBoundaryMigrationPreview.Policy(policy.getPid(), policy.getModelCode(), policy.getPolicyType(),
                            policy.getScopeType(), policy.getScopeExpression(), json.valueToTree(policy.getConditionAst()),
                            policy.getFieldCode(), policy.getMaskType(), policy.getMaskExpression(), policy.getPriority(), policy.getEnabled());
                }).sorted(Comparator.comparing(RoleBoundaryMigrationPreview.Policy::policyPid)).toList();
        var referenced = roots.stream().flatMap(root -> root.permissions().stream()).collect(java.util.stream.Collectors.toSet());
        var consumers = nativeBoundaries.consumers(consumerPermissions(role, roots)).stream().map(consumer -> {
            var root = roots.stream().filter(candidate -> candidate.command().equals(consumer.commandCode())).findFirst();
            String status = root.isEmpty() ? "LEGACY_OR_UNREGISTERED_REVIEW_REQUIRED"
                    : "REGISTERED_BOUNDARY_REVIEW_REQUIRED";
            if (!"published".equals(consumer.publicationStatus()) || (root.isPresent()
                    && (!consumer.businessDeclared() || !Objects.equals(root.get().model(), consumer.modelCode())
                        || !Objects.equals(root.get().action(), consumer.targetAction())
                        || !root.get().permissions().equals(consumer.permissions())))) status = "ENTRY_DEFINITION_REQUIRES_REVIEW";
            return new RoleBoundaryMigrationPreview.Consumer(consumer.commandCode(), consumer.modelCode(),
                    consumer.commandType(), consumer.publicationStatus(), consumer.permissions(), status);
        }).toList();
        return new RoleBoundaryMigrationPreview(role.getPid(), revision(role, roots),
                role.getDefaultDataScopeType(), boundaries, views, scoped.stream()
                    .sorted(Comparator.comparing(scope -> scope.getResourceCode() + ":" + scope.getActionCode()))
                    .map(scope -> new RoleBoundaryMigrationPreview.Scope(scope.getPid(),
                            scope.getResourceCode(), scope.getActionCode(), scope.getScopeType(), scope.getMergeStrategy(),
                            json.valueToTree(scope.getScopeConfig()))).toList(), fieldPolicies.snapshot(role), policies, views.stream()
                    .map(binding -> binding.permissionCode()).filter(Objects::nonNull)
                    .filter(code -> !referenced.contains(code) && !"meta.command.execute".equals(code)).distinct().sorted().toList(), views.stream().filter(binding -> binding.permissionCode() == null)
                    .map(RoleBoundaryMigrationPreview.Binding::bindingPid).sorted().toList(), consumers, false);
    }

    private PermissionDTO migrationDefinition(java.util.Collection<PermissionDTO> definitions, Long tenant, String code) {
        var local = definitions.stream().filter(permission -> code.equals(permission.getCode())
                && Objects.equals(tenant, permission.getTenantId())).toList();
        var candidates = local.isEmpty() ? definitions.stream().filter(permission -> code.equals(permission.getCode())
                && permission.getTenantId() == null).toList() : local;
        if (candidates.size() > 1) throw invalid();
        return candidates.isEmpty() ? null : candidates.getFirst();
    }

    @Transactional
    public RoleAuthorizationDraftPreview preview(String rolePid, RoleAuthorizationDraftRequest request) {
        writes.lock();
        Role role = resolve(rolePid);
        return validate(role, request, businessFieldRoots());
    }

    @Transactional
    public RoleAuthorizationDraftSnapshot apply(String rolePid, RoleAuthorizationDraftRequest request) {
        writes.lock();
        authoringAccess.requireWrite();
        Role role = resolve(rolePid);
        var roots = businessFieldRoots();
        var reviewed = validate(role, request, roots);
        var auditChanges=authorizationChanges(role,reviewed);
        writes.mutate();
        if (request.defaultScopeChanged()) roles.setDefaultDataScopeType(role.getId(), request.defaultScopeType());
        capabilities.applyCapabilitySelection(role.getId(), request.selectedCapabilityCodes(), revoked(request));
        Long tenant = MetaContext.getCurrentTenantId();
        if (request.applyDefaultToCurrentGrants()) {
            matrix.applyDefaultScopeToCurrentGrants(tenant, role.getId(), request.defaultScopeType());
        }
        for (DataScopeUpdateRequest change : changes(request)) {
            matrix.setRoleScope(tenant,role.getId(),change);
        }
        fieldPolicies.apply(role, reviewed.fieldPolicies());
        fieldAccess.apply(role,reviewed.fieldAccessChanges());
        grantFields.apply(role,reviewed.grantFieldChanges(),grantFieldBoundaries(roots));
        if(request.menuPids()!=null)roleNavigation.apply(role,request.menuPids());
        roleNavigation.auditAuthorization(role,auditChanges);
        // A failed write or readback propagates and rolls back the entire authoring operation.
        return snapshot(resolve(rolePid), roots);
    }

    private RoleAuthorizationDraftPreview validate(Role role, RoleAuthorizationDraftRequest request, List<BusinessFieldRoot> roots) {
        if (request == null || request.expectedRevision() == null
                || !request.expectedRevision().matches("[a-f0-9]{64}")
                || request.selectedCapabilityCodes() == null || request.selectedCapabilityCodes().size() > 5000
                || revoked(request).size() > 5000 || changes(request).size() > 1000) throw invalid();
        String revision = revision(role, roots);
        if (!revision.equals(request.expectedRevision())) throw new BusinessException(ResponseCode.CasVersionConflict);
        if(request.menuPids()!=null)roleNavigation.validate(role,request.menuPids());
        if (request.defaultScopeChanged() && request.defaultScopeType() != null) scopeType(request.defaultScopeType());
        if (!request.defaultScopeChanged() && request.defaultScopeType() != null) throw invalid();
        if (request.applyDefaultToCurrentGrants()
                && (!request.defaultScopeChanged() || request.defaultScopeType() == null)) throw invalid();
        CapabilitySelectionPreview selection = capabilities.previewCapabilitySelection(
                role.getId(), request.selectedCapabilityCodes(), revoked(request));
        Set<String> resulting = new HashSet<>(permissions.findRoleConfiguredPermissions(role.getId()).stream()
                .map(permission -> permission.getCode()).filter(Objects::nonNull).toList());
        resulting.removeAll(selection.revokedCodes());
        resulting.addAll(selection.grantedCodes());
        Set<String> permittedPairs = new HashSet<>();
        Set<String> permittedModels = new HashSet<>();
        for (var permission : permissions.findAllActive()) {
            if (resulting.contains(permission.getCode()) && permission.getResourceCode() != null
                    && permission.getAction() != null) {
                permittedPairs.add(permission.getResourceCode() + ":" + permission.getAction());
                permittedModels.add(permission.getResourceCode());
            }
        }
        // Restrictive policies may cover the root and declared public sources without model CRUD.
        roots.stream().filter(root -> resulting.contains("*")
                        || root.permissions().stream().anyMatch(resulting::contains))
                .forEach(root -> {
                    permittedModels.add(root.model());
                    permittedModels.addAll(root.publicFields().keySet());
                });
        Set<String> seen = new HashSet<>();
        for (DataScopeUpdateRequest change : changes(request)) {
            if (change == null || change.resourceCode() == null || change.actionCode() == null
                    || !permittedPairs.contains(change.resourceCode() + ":" + change.actionCode())
                    || !seen.add(change.resourceCode() + ":" + change.actionCode())
                    || (change.mergeStrategy() != null && !Set.of("MAX", "MIN").contains(change.mergeStrategy()))) {
                throw invalid();
            }
            scopeType(change.scopeType());
        }
        return new RoleAuthorizationDraftPreview(revision, selection, request.defaultScopeChanged(),
                request.defaultScopeType(), request.applyDefaultToCurrentGrants(), changes(request),
                fieldPolicies.preview(role, request.fieldPolicyPids(), permittedModels),
                request.menuPids()==null?null:roleNavigation.preview(role,request.menuPids(),resulting),
                fieldAccess.preview(role,request.fieldChanges(),permittedModels),
                grantFields.preview(role,request.grantFieldChanges(),resulting,Set.copyOf(selection.grantedCodes()),grantFieldBoundaries(roots)),
                grantFields.candidates(role,resulting,Set.copyOf(selection.grantedCodes()),grantFieldBoundaries(roots)));
    }

    private RoleAuthorizationDraftSnapshot snapshot(Role role, List<BusinessFieldRoot> roots) {
        return new RoleAuthorizationDraftSnapshot(role.getPid(), revision(role, roots), role.getDefaultDataScopeType(),
                capabilities.resolveForRole(role.getId()), matrix.getMatrixForRole(role.getTenantId(), role.getId()),
                scopes.getScopesByRole(role.getTenantId(), role.getId()).stream()
                        .map(scope -> new DataScopeUpdateRequest(scope.getResourceCode(), scope.getActionCode(),
                                scope.getScopeType(), scope.getMergeStrategy())).toList(), fieldPolicies.snapshot(role),roleNavigation.selection(role),
                fieldAccess.catalog(role,fieldCandidates(role, roots)), grantFields.catalog(role,grantFieldBoundaries(roots)));
    }

    private Role resolve(String rolePid) {
        Role role = roles.findByPid(rolePid);
        if (role == null || !Objects.equals(role.getTenantId(), MetaContext.getCurrentTenantId())) throw invalid();
        return role;
    }

    private String revision(Role role, List<BusinessFieldRoot> roots) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("role", role);
        state.put("navigation",roleNavigation.selection(role));
        state.put("fieldAccess",fieldAccess.catalog(role,fieldCandidates(role, roots)));
        state.put("grantFieldCatalog", grantFields.catalog(role,grantFieldBoundaries(roots)));
        state.put("grants", grants.findByRole(role.getId()).stream()
                .sorted(Comparator.comparing(RolePermission::getId, Comparator.nullsLast(Long::compareTo))).toList());
        state.put("scopes", scopes.getScopesByRole(role.getTenantId(), role.getId()).stream()
                .sorted(Comparator.comparing(scope -> scope.getResourceCode() + ":" + scope.getActionCode())).toList());
        var bindings = policyBindings.findByRolePid(role.getTenantId(), role.getPid());
        state.put("policyBindings", bindings.stream()
                .sorted(Comparator.comparing(binding -> binding.getPolicyPid())).toList());
        Map<String, Object> policies = new java.util.TreeMap<>();
        bindings.forEach(binding -> policies.put(binding.getPolicyPid(),
                dataPolicies.findByPid(role.getTenantId(), binding.getPolicyPid())));
        state.put("dataAndFieldPolicies", policies);
        // An unbound candidate must not change between preview and commit without a conflict.
        state.put("fieldPolicyCatalog", dataPolicies.findAllEnabled(role.getTenantId()).stream()
                .filter(policy -> "column".equalsIgnoreCase(policy.getPolicyType())
                        || "field_write".equalsIgnoreCase(policy.getPolicyType()))
                .sorted(Comparator.comparing(policy -> policy.getPid())).toList());
        state.put("catalog", catalog.listDeclarations(role.getTenantId()).stream()
                .sorted(Comparator.comparing(declaration -> declaration.getCode())).toList());
        state.put("businessFieldRoots", roots);
        state.put("commandConsumers", nativeBoundaries.consumers(consumerPermissions(role, roots)));
        state.put("businessOwnerEntrySources", nativeBoundaries.ownerSources(roots.stream()
                .filter(root -> root.definitionSource() == null).map(BusinessFieldRoot::command).toList()));
        state.put("permissions", permissions.findAllActive().stream()
                .sorted(Comparator.comparing(permission -> permission.getCode())).toList());
        return digest(state);
    }

    private Set<String> consumerPermissions(Role role, List<BusinessFieldRoot> roots) {
        Set<String> result = roots.stream().flatMap(root -> root.permissions().stream())
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        var ids = grants.findByRole(role.getId()).stream().map(RolePermission::getPermissionId).collect(java.util.stream.Collectors.toSet());
        permissions.findAllActive().stream().filter(permission -> ids.contains(permission.getId())
                && (permission.getTenantId() == null || Objects.equals(role.getTenantId(), permission.getTenantId())))
                .map(PermissionDTO::getCode).filter(Objects::nonNull).forEach(result::add);
        return result;
    }

    private List<BusinessFieldRoot> businessFieldRoots() {
        List<BusinessFieldRoot> roots = new java.util.ArrayList<>();
        for (var handler : extensions == null ? List.<com.auraboot.framework.plugin.extension.CommandHandlerExtension>of() : extensions.getAllCommandHandlers()) {
            if (!(handler instanceof com.auraboot.framework.plugin.extension.BusinessResourceAuthorization owner)) continue;
            var boundary = owner.businessBoundary();
            if (extensions.getBusinessResourceOwner(boundary.commandCode()).orElse(null) != handler) continue;
            if (boundary.permissions().isEmpty() || boundary.permissions().size() > 128
                    || boundary.permissions().stream().anyMatch(code -> code == null || code.isBlank()
                        || !code.equals(code.strip()) || code.length() > 255 || "*".equals(code)
                        || "meta.command.execute".equals(code))) throw invalid();
            roots.add(new BusinessFieldRoot(boundary.commandCode(), boundary.rootModel(), boundary.targetAction(),
                    boundary.permissions().stream().sorted().toList(), null, publicFieldSources(owner), owner.businessInputContract(), owner.businessDeletableResourceModels().stream().sorted().toList(), Map.copyOf(owner.businessResultProjection()), Map.copyOf(owner.businessSourceFileFields())));
        }
        if (nativeBoundaries != null) nativeBoundaries.snapshot().forEach(definition -> {
            var contract = definition.contract();
            roots.add(new BusinessFieldRoot(definition.commandCode(), contract.model(), contract.action(),
                    contract.permissions().stream().sorted().toList(), definition.source(), Map.of(), null, List.of(), Map.of(), Map.of()));
        });
        return roots.stream().distinct().sorted(Comparator.comparing(BusinessFieldRoot::command)).toList();
    }

    private List<RoleGrantFieldScopeService.BusinessBoundary> grantFieldBoundaries(List<BusinessFieldRoot> roots) {
        return roots.stream().flatMap(root -> root.permissions().stream().map(code ->
                new RoleGrantFieldScopeService.BusinessBoundary(code, root.model(), root.action()))).distinct().toList();
    }

    private Set<String> fieldCandidates(Role role, List<BusinessFieldRoot> roots) {
        Set<String> result=new HashSet<>();
        roots.forEach(root->{result.add(root.model());result.addAll(root.publicFields().keySet());});
        permissions.findRoleConfiguredPermissions(role.getId()).stream().map(permission->permission.getResourceCode())
                .filter(Objects::nonNull).forEach(result::add);
        return result;
    }

    private List<com.auraboot.framework.menu.dto.RoleNavigation.AuthorizationChange> authorizationChanges(Role role,RoleAuthorizationDraftPreview reviewed) {
        var result=new java.util.ArrayList<com.auraboot.framework.menu.dto.RoleNavigation.AuthorizationChange>();
        var before=new java.util.HashMap<String,String>();
        capabilities.resolveForRole(role.getId()).forEach(group->group.getCapabilities().forEach(cap->before.put(cap.getCode(),cap.getAuthorizationState())));
        for(var cap:reviewed.capabilities().resultingCapabilities()) {
            if(!cap.isConventionDerived()&&!Objects.equals(before.getOrDefault(cap.getCode(),"none"),cap.getAuthorizationState()))
                result.add(new com.auraboot.framework.menu.dto.RoleNavigation.AuthorizationChange("duty",cap.getLabel(),before.getOrDefault(cap.getCode(),"none"),cap.getAuthorizationState()));
        }
        for (var change : reviewed.grantFieldChanges()) {
            result.add(new com.auraboot.framework.menu.dto.RoleNavigation.AuthorizationChange("grant_fields",
                    change.before().modelLabel(), change.before().bindingPid() == null ? "not_granted" : change.before().legacy() ? "legacy" : digest(Map.of("readFields",change.before().readFields(),"writeFields",change.before().writeFields())),
                    digest(Map.of("readFields",change.readFields(),"writeFields",change.writeFields()))));
        }
        if(reviewed.defaultScopeChanged())result.add(new com.auraboot.framework.menu.dto.RoleNavigation.AuthorizationChange("default_scope","",role.getDefaultDataScopeType(),reviewed.defaultScopeType()));
        if(reviewed.applyDefaultToCurrentGrants())result.add(new com.auraboot.framework.menu.dto.RoleNavigation.AuthorizationChange("apply_default","",null,reviewed.defaultScopeType()));
        var previousScopes=scopes.getScopesByRole(role.getTenantId(),role.getId());
        for(var change:reviewed.scopeChanges()) {
            var prior=previousScopes.stream().filter(scope->Objects.equals(scope.getResourceCode(),change.resourceCode())&&Objects.equals(scope.getActionCode(),change.actionCode())).findFirst();
            String name=permissions.findAllActive().stream().filter(permission->Objects.equals(permission.getResourceCode(),change.resourceCode())&&Objects.equals(permission.getAction(),change.actionCode())).map(PermissionDTO::getName).filter(Objects::nonNull).findFirst().orElse("");
            result.add(new com.auraboot.framework.menu.dto.RoleNavigation.AuthorizationChange("scope",name,prior.map(RoleDataScope::getScopeType).orElse(null),change.scopeType()));
            String merge=change.mergeStrategy()==null?"MAX":change.mergeStrategy();
            if(!Objects.equals(prior.map(RoleDataScope::getMergeStrategy).orElse("MAX"),merge))result.add(new com.auraboot.framework.menu.dto.RoleNavigation.AuthorizationChange("scope_merge",name,prior.map(RoleDataScope::getMergeStrategy).orElse("MAX"),merge));
        }
        reviewed.fieldPolicies().added().forEach(policy->result.add(new com.auraboot.framework.menu.dto.RoleNavigation.AuthorizationChange("shared_field",policy.name(),"not_applied","applied")));
        reviewed.fieldPolicies().removed().forEach(policy->result.add(new com.auraboot.framework.menu.dto.RoleNavigation.AuthorizationChange("shared_field",policy.name(),"applied","not_applied")));
        reviewed.fieldAccessChanges().forEach(change->result.add(new com.auraboot.framework.menu.dto.RoleNavigation.AuthorizationChange("field",change.modelLabel()+" · "+change.fieldLabel(),change.beforeAccess(),change.access())));
        return List.copyOf(result);
    }

    private Map<String, List<String>> publicFieldSources(
            com.auraboot.framework.plugin.extension.BusinessResourceAuthorization owner) {
        var declared = owner.publicBusinessResourceFields();
        if (declared == null || declared.size() > 16) throw invalid();
        if (declared.isEmpty()) return Map.of();
        var resources = owner.businessResourceModels();
        if (resources == null || !resources.containsAll(declared.keySet())) throw invalid();
        Map<String, List<String>> sources = new TreeMap<>();
        declared.forEach((model, fields) -> {
            com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.identifier(model);
            if (fields == null || fields.isEmpty() || fields.size() > 128) throw invalid();
            fields.forEach(com.auraboot.framework.plugin.extension.BusinessResourceAuthorization::identifier);
            sources.put(model, fields.stream().sorted().toList());
        });
        return Map.copyOf(sources);
    }

    private record BusinessFieldRoot(String command, String model, String action, List<String> permissions,
                                     String definitionSource, Map<String, List<String>> publicFields,
                                     com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.InputContract inputContract, List<String> deletableModels,
                                     Map<String, com.auraboot.framework.plugin.extension.BusinessResultField> resultProjection, Map<String, Integer> sourceFileFields) {}

    private String digest(Map<String, Object> state) {
        try {
            byte[] serialized = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(state);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(serialized));
        } catch (JsonProcessingException | NoSuchAlgorithmException failure) {
            // CATCH: serialization/digest is a local precondition; never continue with a missing revision.
            throw new IllegalStateException("Cannot compute the authorization revision", failure);
        }
    }

    private static Set<String> revoked(RoleAuthorizationDraftRequest request) {
        return request.revokedPartialCapabilityCodes() == null ? Set.of() : request.revokedPartialCapabilityCodes();
    }

    private static List<DataScopeUpdateRequest> changes(RoleAuthorizationDraftRequest request) {
        return request.scopeChanges() == null ? List.of() : request.scopeChanges();
    }

    private static void scopeType(String scope) {
        if (scope == null) throw invalid();
        try { DataScopeType.fromCode(scope); }
        catch (IllegalArgumentException invalid) {
            // CATCH: expected input validation, before any write is attempted.
            throw invalid();
        }
    }

    private static RootUnCheckedException invalid() {
        return new RootUnCheckedException(ResponseCode.BadParam, "Invalid role authorization draft");
    }
}
