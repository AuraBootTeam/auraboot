package com.auraboot.framework.rbac.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.rbac.dto.RoleMemberDTO;
import com.auraboot.framework.rbac.entity.Role;
import com.auraboot.framework.rbac.service.RoleMemberService;
import com.auraboot.framework.rbac.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.auraboot.framework.common.constant.ResponseCode.BadParam;

/**
 * REST controller for managing role memberships.
 * Allows viewing, adding, and removing members from a role.
 * Uses role PID (string) in URL path to avoid JavaScript BigInt precision loss.
 */
@RestController
@RequestMapping("/api/roles/{rolePid}/members")
@Tag(name = "Role Members", description = "Role membership management")
@RequiredArgsConstructor
public class RoleMemberController {

    private final RoleMemberService roleMemberService;
    private final RoleService roleService;

    private Long resolveRoleId(String rolePid) {
        Role role = roleService.findByPid(rolePid);
        if (role == null) {
            throw new RootUnCheckedException(BadParam, "Role not found by PID: " + rolePid);
        }
        return role.getId();
    }

    @GetMapping
    @RequirePermission(MetaPermission.ROLE_READ)
    @Operation(summary = "Get members of a role")
    public ApiResponse<PaginationResult<RoleMemberDTO>> getMembers(
            @PathVariable String rolePid,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(roleMemberService.getMembers(resolveRoleId(rolePid), pageNum, pageSize));
    }

    @PostMapping
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    @Operation(summary = "Add members to a role")
    public ApiResponse<Void> addMembers(
            @PathVariable String rolePid,
            @RequestBody List<Long> memberIds) {
        roleMemberService.addMembers(resolveRoleId(rolePid), memberIds);
        return ApiResponse.success();
    }

    @DeleteMapping
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    @Operation(summary = "Remove members from a role")
    public ApiResponse<Void> removeMembers(
            @PathVariable String rolePid,
            @RequestBody List<Long> memberIds) {
        roleMemberService.removeMembers(resolveRoleId(rolePid), memberIds);
        return ApiResponse.success();
    }

    @GetMapping("/candidates")
    @RequirePermission(MetaPermission.ROLE_READ)
    @Operation(summary = "Get candidate members not yet assigned to this role")
    public ApiResponse<List<RoleMemberDTO>> getCandidates(
            @PathVariable String rolePid,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(roleMemberService.getCandidates(resolveRoleId(rolePid), keyword));
    }
}
