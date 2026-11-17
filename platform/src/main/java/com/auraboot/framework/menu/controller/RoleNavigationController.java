package com.auraboot.framework.menu.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.menu.dto.RoleNavigation.*;
import com.auraboot.framework.menu.service.RoleNavigationService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/navigation")
@AuthenticatedAccess("Navigation authoring checks fresh human manager authority inside its tenant lock")
public class RoleNavigationController {
    private final RoleNavigationService navigation;
    private final com.auraboot.framework.menu.service.RoleNavigationPreviewService previews;
    @Operation(operationId="readNavigationPreviewOptions")
    @GetMapping("/preview/options") public ApiResponse<PreviewOptions> options(){return ApiResponse.success(previews.options());}
    @Operation(operationId="previewUserNavigation")
    @PostMapping("/preview") public ApiResponse<UserPreview> preview(@Valid @RequestBody PreviewRequest request){return ApiResponse.success(previews.preview(request));}
    @Operation(operationId="readRoleNavigationAudit")
    @GetMapping("/roles/{rolePid}/audit") public ApiResponse<java.util.List<Audit>> audit(@PathVariable String rolePid){return ApiResponse.success(navigation.auditLog(rolePid));}
    @Operation(operationId="readRoleNavigationSelection")
    @GetMapping("/roles/{rolePid}") public ApiResponse<Selection> read(@PathVariable String rolePid){return ApiResponse.success(navigation.read(rolePid));}
    @Operation(operationId="saveRoleNavigationSelection")
    @PutMapping("/roles/{rolePid}") public ApiResponse<Selection> save(@PathVariable String rolePid,@Valid @RequestBody Write request){return ApiResponse.success(navigation.save(rolePid,request));}
    @Operation(operationId="readNavigationEntryRoles")
    @GetMapping("/entries/{pid}/roles") public ApiResponse<EntryRoles> roles(@PathVariable String pid){return ApiResponse.success(navigation.entryRoles(pid));}
    @Operation(operationId="saveNavigationEntryRoles")
    @PutMapping("/entries/{pid}/roles") public ApiResponse<EntryRoles> roles(@PathVariable String pid,@Valid @RequestBody EntryRolesWrite request){return ApiResponse.success(navigation.saveEntryRoles(pid,request));}
}
