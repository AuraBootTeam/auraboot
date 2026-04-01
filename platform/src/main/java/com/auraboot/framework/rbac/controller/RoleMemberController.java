package com.auraboot.framework.rbac.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.rbac.dto.RoleMemberDTO;
import com.auraboot.framework.rbac.service.RoleMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for managing role memberships.
 * Allows viewing, adding, and removing members from a role.
 */
@RestController
@RequestMapping("/api/roles/{roleId}/members")
@Tag(name = "Role Members", description = "Role membership management")
@RequiredArgsConstructor
public class RoleMemberController {

    private final RoleMemberService roleMemberService;

    @GetMapping
    @RequirePermission(MetaPermission.ROLE_READ)
    @Operation(summary = "Get members of a role")
    public ApiResponse<PaginationResult<RoleMemberDTO>> getMembers(
            @PathVariable Long roleId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(roleMemberService.getMembers(roleId, pageNum, pageSize));
    }

    @PostMapping
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    @Operation(summary = "Add members to a role")
    public ApiResponse<Void> addMembers(
            @PathVariable Long roleId,
            @RequestBody List<Long> memberIds) {
        roleMemberService.addMembers(roleId, memberIds);
        return ApiResponse.success();
    }

    @DeleteMapping
    @RequirePermission(MetaPermission.USER_ROLE_MANAGE)
    @Operation(summary = "Remove members from a role")
    public ApiResponse<Void> removeMembers(
            @PathVariable Long roleId,
            @RequestBody List<Long> memberIds) {
        roleMemberService.removeMembers(roleId, memberIds);
        return ApiResponse.success();
    }

    @GetMapping("/candidates")
    @RequirePermission(MetaPermission.ROLE_READ)
    @Operation(summary = "Get candidate members not yet assigned to this role")
    public ApiResponse<List<RoleMemberDTO>> getCandidates(
            @PathVariable Long roleId,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(roleMemberService.getCandidates(roleId, keyword));
    }
}
