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

/** A single database transaction for role duties, defaults and per-action record scopes. */
@Service
@RequiredArgsConstructor
public class RoleAuthorizationDraftService {
    private final RoleAuthorizationWriteLock writes;
    private final RoleFieldPolicyDraftService fieldPolicies;
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

    @Transactional
    public RoleAuthorizationDraftSnapshot snapshot(String rolePid) {
        writes.lock();
        return snapshot(resolve(rolePid));
    }

    @Transactional
    public RoleAuthorizationDraftPreview preview(String rolePid, RoleAuthorizationDraftRequest request) {
        writes.lock();
        Role role = resolve(rolePid);
        return validate(role, request);
    }

    @Transactional
    public RoleAuthorizationDraftSnapshot apply(String rolePid, RoleAuthorizationDraftRequest request) {
        writes.lock();
        Role role = resolve(rolePid);
        var reviewed = validate(role, request);
        writes.mutate();
        if (request.defaultScopeChanged()) roles.setDefaultDataScopeType(role.getId(), request.defaultScopeType());
        capabilities.applyCapabilitySelection(role.getId(), request.selectedCapabilityCodes(), revoked(request));
        Long tenant = MetaContext.getCurrentTenantId();
        if (request.applyDefaultToCurrentGrants()) {
            matrix.applyDefaultScopeToCurrentGrants(tenant, role.getId(), request.defaultScopeType());
        }
        for (DataScopeUpdateRequest change : changes(request)) {
            scopes.setScope(tenant, role.getId(), change.resourceCode(), change.actionCode(),
                    change.scopeType(), change.mergeStrategy());
        }
        fieldPolicies.apply(role, reviewed.fieldPolicies());
        // A failed write or readback propagates and rolls back the entire authoring operation.
        return snapshot(resolve(rolePid));
    }

    private RoleAuthorizationDraftPreview validate(Role role, RoleAuthorizationDraftRequest request) {
        if (request == null || request.expectedRevision() == null
                || !request.expectedRevision().matches("[a-f0-9]{64}")
                || request.selectedCapabilityCodes() == null || request.selectedCapabilityCodes().size() > 5000
                || revoked(request).size() > 5000 || changes(request).size() > 1000) throw invalid();
        String revision = revision(role);
        if (!revision.equals(request.expectedRevision())) throw new BusinessException(ResponseCode.CasVersionConflict);
        if (request.defaultScopeChanged() && request.defaultScopeType() != null) scopeType(request.defaultScopeType());
        if (!request.defaultScopeChanged() && request.defaultScopeType() != null) throw invalid();
        if (request.applyDefaultToCurrentGrants()
                && (!request.defaultScopeChanged() || request.defaultScopeType() == null)) throw invalid();
        CapabilitySelectionPreview selection = capabilities.previewCapabilitySelection(
                role.getId(), request.selectedCapabilityCodes(), revoked(request));
        Set<String> resulting = new HashSet<>(permissions.findRolePermissions(role.getId()).stream()
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
                fieldPolicies.preview(role, request.fieldPolicyPids(), permittedModels));
    }

    private RoleAuthorizationDraftSnapshot snapshot(Role role) {
        return new RoleAuthorizationDraftSnapshot(role.getPid(), revision(role), role.getDefaultDataScopeType(),
                capabilities.resolveForRole(role.getId()), matrix.getMatrixForRole(role.getTenantId(), role.getId()),
                scopes.getScopesByRole(role.getTenantId(), role.getId()).stream()
                        .map(scope -> new DataScopeUpdateRequest(scope.getResourceCode(), scope.getActionCode(),
                                scope.getScopeType(), scope.getMergeStrategy())).toList(), fieldPolicies.snapshot(role));
    }

    private Role resolve(String rolePid) {
        Role role = roles.findByPid(rolePid);
        if (role == null || !Objects.equals(role.getTenantId(), MetaContext.getCurrentTenantId())) throw invalid();
        return role;
    }

    private String revision(Role role) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("role", role);
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
        state.put("permissions", permissions.findAllActive().stream()
                .sorted(Comparator.comparing(permission -> permission.getCode())).toList());
        return digest(state);
    }

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
