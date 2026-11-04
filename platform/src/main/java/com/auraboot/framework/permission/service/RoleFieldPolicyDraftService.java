package com.auraboot.framework.permission.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.meta.mapper.DataPermissionPolicyMapper;
import com.auraboot.framework.meta.mapper.DataPermissionRoleBindingMapper;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.dto.RoleFieldPolicyPreview;
import com.auraboot.framework.permission.dto.RoleFieldPolicyView;
import com.auraboot.framework.rbac.entity.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.*;

/** Field policy bindings participate in the caller's locked role-authoring transaction. */
@Service
@RequiredArgsConstructor
public class RoleFieldPolicyDraftService {
    private final DataPermissionPolicyMapper policies;
    private final DataPermissionRoleBindingMapper bindings;
    private final MetaModelService models;
    private final UserPermissionService permissions;

    public List<RoleFieldPolicyView> snapshot(Role role) {
        return current(role).values().stream().map(RoleFieldPolicyDraftService::view).toList();
    }

    public RoleFieldPolicyPreview preview(Role role, Set<String> selected, Set<String> resultingModels) {
        if (selected == null) return new RoleFieldPolicyPreview(List.of(), List.of());
        if (MetaContext.getCurrentUserId() == null || !permissions.hasPermission(
                MetaContext.getCurrentUserId(), MetaPermission.PERMISSION_MANAGE))
            throw new AccessDeniedException("Field policy authoring requires permission management authority");
        if (selected.size() > 1000 || selected.stream().anyMatch(pid -> pid == null || pid.isBlank() || pid.length() > 64))
            throw invalid();
        var current = current(role);
        List<RoleFieldPolicyView> added = new ArrayList<>();
        for (String pid : selected.stream().sorted().toList()) {
            var policy = policies.findByPid(role.getTenantId(), pid);
            if (policy == null || !Objects.equals(role.getTenantId(), policy.getTenantId())
                    || !isFieldPolicy(policy) || !Boolean.TRUE.equals(policy.getEnabled())
                    || !resultingModels.contains(policy.getModelCode()) || policy.getFieldCode() == null
                    || models.getModelFields(policy.getModelCode()).stream()
                        .noneMatch(field -> policy.getFieldCode().equals(field.getCode()))) throw invalid();
            if ("column".equalsIgnoreCase(policy.getPolicyType())
                    && (policy.getMaskType() == null || !Set.of("hide", "partial", "hash", "custom")
                        .contains(policy.getMaskType().toLowerCase(Locale.ROOT)))) throw invalid();
            if (!current.containsKey(pid)) added.add(view(policy));
        }
        return new RoleFieldPolicyPreview(List.copyOf(added), current.entrySet().stream()
                .filter(entry -> !selected.contains(entry.getKey())).map(entry -> view(entry.getValue())).toList());
    }

    void apply(Role role, RoleFieldPolicyPreview preview) {
        for (var removed : preview.removed()) {
            if (bindings.deleteBinding(role.getTenantId(), removed.policyPid(), role.getPid()) != 1)
                throw new IllegalStateException("Field policy binding changed during role authoring");
        }
        for (var added : preview.added()) {
            if (bindings.insertBinding(role.getTenantId(), added.policyPid(), role.getPid()) != 1)
                throw new IllegalStateException("Field policy binding changed during role authoring");
        }
    }

    private SortedMap<String, DataPermissionPolicy> current(Role role) {
        if (!Objects.equals(role.getTenantId(), MetaContext.getCurrentTenantId())) throw invalid();
        SortedMap<String, DataPermissionPolicy> result = new TreeMap<>();
        for (var binding : bindings.findByRolePid(role.getTenantId(), role.getPid())) {
            var policy = policies.findByPid(role.getTenantId(), binding.getPolicyPid());
            if (policy == null || !Objects.equals(role.getTenantId(), policy.getTenantId())) throw invalid();
            if (isFieldPolicy(policy)) result.put(policy.getPid(), policy);
        }
        return result;
    }

    private static boolean isFieldPolicy(DataPermissionPolicy policy) {
        return "column".equalsIgnoreCase(policy.getPolicyType()) || "field_write".equalsIgnoreCase(policy.getPolicyType());
    }

    private static RoleFieldPolicyView view(DataPermissionPolicy policy) {
        return new RoleFieldPolicyView(policy.getPid(), policy.getName(), policy.getModelCode(), policy.getFieldCode(),
                policy.getPolicyType(), policy.getMaskType(), Boolean.TRUE.equals(policy.getEnabled()));
    }

    private static RootUnCheckedException invalid() {
        return new RootUnCheckedException(ResponseCode.BadParam, "Invalid role field policy selection");
    }
}
