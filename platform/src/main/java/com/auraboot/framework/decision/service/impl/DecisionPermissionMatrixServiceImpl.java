package com.auraboot.framework.decision.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.decision.dto.DecisionPermissionCapabilityDTO;
import com.auraboot.framework.decision.dto.DecisionPermissionMatrixDTO;
import com.auraboot.framework.decision.dto.DecisionPermissionRoleGrantDTO;
import com.auraboot.framework.decision.service.DecisionPermissionMatrixService;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.rbac.mapper.RolePermissionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects existing RBAC role-permission bindings into the compact F7 matrix shape.
 */
@Service
@RequiredArgsConstructor
public class DecisionPermissionMatrixServiceImpl implements DecisionPermissionMatrixService {

    private static final List<CapabilitySpec> CAPABILITIES = List.of(
            new CapabilitySpec("view", MetaPermission.DRT_DEFINITION_READ),
            new CapabilitySpec("test", MetaPermission.DRT_RUNTIME_EVALUATE),
            new CapabilitySpec("publish", MetaPermission.DRT_DEFINITION_PUBLISH),
            new CapabilitySpec("approve", MetaPermission.DRT_DEFINITION_APPROVE),
            new CapabilitySpec("rolloutManage", MetaPermission.DRT_ROLLOUT_MANAGE),
            new CapabilitySpec("rolloutPromote", MetaPermission.DRT_ROLLOUT_PROMOTE),
            new CapabilitySpec("rolloutRollback", MetaPermission.DRT_ROLLOUT_ROLLBACK),
            new CapabilitySpec("field", MetaPermission.FIELD_READ));

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;

    @Override
    public DecisionPermissionMatrixDTO getMatrix() {
        Long tenantId = requireTenant();
        Map<String, Permission> permissions = loadPermissions();

        List<DecisionPermissionRoleGrantDTO> rows = roleMapper.findByTenantId(tenantId).stream()
                .map(role -> toRoleGrant(role, permissions))
                .toList();

        DecisionPermissionMatrixDTO dto = new DecisionPermissionMatrixDTO();
        dto.setRoles(rows);
        return dto;
    }

    private Map<String, Permission> loadPermissions() {
        Map<String, Permission> permissions = new LinkedHashMap<>();
        for (CapabilitySpec cap : CAPABILITIES) {
            permissions.put(cap.key(), permissionMapper.findByCode(cap.permissionCode()));
        }
        return permissions;
    }

    private DecisionPermissionRoleGrantDTO toRoleGrant(Role role, Map<String, Permission> permissions) {
        Map<String, Boolean> caps = new LinkedHashMap<>();
        Map<String, DecisionPermissionCapabilityDTO> capabilityDetails = new LinkedHashMap<>();

        for (CapabilitySpec cap : CAPABILITIES) {
            Permission permission = permissions.get(cap.key());
            boolean granted = permission != null
                    && permission.getId() != null
                    && role.getId() != null
                    && rolePermissionMapper.hasPermission(role.getId(), permission.getId());

            caps.put(cap.key(), granted);

            DecisionPermissionCapabilityDTO detail = new DecisionPermissionCapabilityDTO();
            detail.setPermissionCode(cap.permissionCode());
            detail.setGranted(granted);
            capabilityDetails.put(cap.key(), detail);
        }

        DecisionPermissionRoleGrantDTO dto = new DecisionPermissionRoleGrantDTO();
        dto.setRole(displayRole(role));
        dto.setRoleCode(role.getCode());
        dto.setRolePid(role.getPid());
        dto.setCaps(caps);
        dto.setCapabilities(capabilityDetails);
        return dto;
    }

    private String displayRole(Role role) {
        if (role.getName() != null && !role.getName().isBlank()) {
            return role.getName();
        }
        if (role.getCode() != null && !role.getCode().isBlank()) {
            return role.getCode();
        }
        return role.getPid();
    }

    private Long requireTenant() {
        Long tenantId = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tenantId == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Decision permission matrix not found");
        }
        return tenantId;
    }

    private record CapabilitySpec(String key, String permissionCode) {
    }
}
