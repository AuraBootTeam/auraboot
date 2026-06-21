package com.auraboot.framework.permission.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.capability.CapabilityGroup;
import com.auraboot.framework.permission.capability.CapabilityViewService;
import com.auraboot.framework.permission.constants.MetaPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Permission v2 capability view: the business-language capability groups for a role (declared +
 * convention-derived), each marked granted per the role's current permissions. Backs the v2 role
 * editor's capability checklist instead of the raw resource x action matrix.
 */
@RestController
@RequestMapping("/api/permission/capabilities")
@RequiredArgsConstructor
public class CapabilityController {

    private final CapabilityViewService capabilityViewService;

    @GetMapping
    @RequirePermission(MetaPermission.ROLE_READ)
    public ApiResponse<List<CapabilityGroup>> getForRole(@RequestParam Long roleId) {
        return ApiResponse.success(capabilityViewService.resolveForRole(roleId));
    }
}
