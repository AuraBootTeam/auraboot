package com.auraboot.framework.permission.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.dto.RoleAuthorizationDraftPreview;
import com.auraboot.framework.permission.dto.RoleAuthorizationDraftRequest;
import com.auraboot.framework.permission.dto.RoleAuthorizationDraftSnapshot;
import com.auraboot.framework.permission.service.RoleAuthorizationDraftService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Role configuration authoring only; it never simulates a member's effective access. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/permission/roles/{rolePid}/draft")
@Tag(name = "Role Authorization Draft", description = "Atomic role authorization authoring")
public class RoleAuthorizationDraftController {
    private final RoleAuthorizationDraftService drafts;

    @GetMapping
    @RequirePermission(MetaPermission.ROLE_READ)
    @Operation(operationId = "readRoleAuthorizationDraft", summary = "Read a consistent role authorization draft baseline")
    public ApiResponse<RoleAuthorizationDraftSnapshot> snapshot(@PathVariable String rolePid) {
        return ApiResponse.success(drafts.snapshot(rolePid));
    }

    @GetMapping("/migration")
    @RequirePermission(MetaPermission.PERMISSION_MANAGE)
    @Operation(operationId = "inspectRoleBoundaryMigration", summary = "Inspect role bindings and explicit scope decisions before migration")
    public ApiResponse<com.auraboot.framework.permission.dto.RoleBoundaryMigrationPreview> migration(@PathVariable String rolePid) {
        return ApiResponse.success(drafts.migrationPreview(rolePid));
    }

    @PostMapping("/preview")
    @RequirePermission(MetaPermission.ROLE_READ)
    @Operation(operationId = "previewRoleAuthorizationDraft", summary = "Preview role duties and record scopes against an exact revision")
    public ApiResponse<RoleAuthorizationDraftPreview> preview(@PathVariable String rolePid,
            @Valid @RequestBody RoleAuthorizationDraftRequest request) {
        return ApiResponse.success(drafts.preview(rolePid, request));
    }

    @PutMapping
    @RequirePermission(MetaPermission.ROLE_MANAGE)
    @Operation(operationId = "applyRoleAuthorizationDraft", summary = "Atomically commit role duties and record scopes with conflict detection")
    public ApiResponse<RoleAuthorizationDraftSnapshot> apply(@PathVariable String rolePid,
            @Valid @RequestBody RoleAuthorizationDraftRequest request) {
        return ApiResponse.success(drafts.apply(rolePid, request));
    }
}
