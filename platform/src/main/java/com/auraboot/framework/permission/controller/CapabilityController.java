package com.auraboot.framework.permission.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.capability.CapabilityGroup;
import com.auraboot.framework.permission.capability.CapabilityViewService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

import java.util.List;
import java.util.Set;

/**
 * Permission v2 capability view: the business-language capability groups for a role (declared +
 * convention-derived), each marked granted per the role's current permissions. Backs the v2 role
 * editor's capability checklist instead of the raw resource x action matrix.
 *
 * <p>Keyed on the role PID (string), not the numeric id — role ids are snowflakes that exceed
 * JavaScript's safe-integer range, so a numeric id round-trips lossily through the browser and would
 * resolve to the wrong (non-existent) role. Mirrors {@code PermissionMatrixController}.
 */
@RestController
@RequestMapping("/api/permission/capabilities")
@RequiredArgsConstructor
public class CapabilityController {

    private final CapabilityViewService capabilityViewService;
    private final RoleService roleService;

    @GetMapping
    @RequirePermission(MetaPermission.ROLE_READ)
    public ApiResponse<List<CapabilityGroup>> getForRole(@RequestParam String rolePid) {
        return ApiResponse.success(capabilityViewService.resolveForRole(resolveRoleId(rolePid)));
    }

    /**
     * Apply a capability selection to a role (grant/revoke within the capability universe) and
     * return the refreshed capability view.
     */
    @PutMapping
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    public ApiResponse<List<CapabilityGroup>> applySelection(
            @RequestParam String rolePid,
            @RequestBody Set<String> selectedCapabilityCodes) {
        Long roleId = resolveRoleId(rolePid);
        capabilityViewService.applyCapabilitySelection(roleId, selectedCapabilityCodes);
        return ApiResponse.success(capabilityViewService.resolveForRole(roleId));
    }

    private Long resolveRoleId(String rolePid) {
        Role role = roleService.findByPid(rolePid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam, "Role not found by PID: " + rolePid);
        }
        return role.getId();
    }
}
