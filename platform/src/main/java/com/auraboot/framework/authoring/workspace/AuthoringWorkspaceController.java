package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CapabilityRegistryView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ChangeSetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.PatchResult;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReleaseView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReviewRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RevisionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RollbackRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Permission-rechecked HTTP boundary for contextual-authoring sessions. */
@RestController
@RequestMapping("/api/authoring")
public class AuthoringWorkspaceController {

    private final AuthoringWorkspaceService workspaceService;
    private final AuthoringGovernanceService governanceService;

    public AuthoringWorkspaceController(
            AuthoringWorkspaceService workspaceService,
            AuthoringGovernanceService governanceService) {
        this.workspaceService = workspaceService;
        this.governanceService = governanceService;
    }

    @GetMapping("/capabilities")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_READ)
    public ApiResponse<CapabilityRegistryView> capabilities() {
        return ApiResponse.success(workspaceService.capabilities());
    }

    @PostMapping("/sessions")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_MANAGE)
    public ApiResponse<SessionView> open(@Valid @RequestBody OpenSessionRequest request) {
        return ApiResponse.success(workspaceService.open(request));
    }

    @GetMapping("/sessions/{sessionPid}")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_MANAGE)
    public ApiResponse<SessionView> get(@PathVariable String sessionPid) {
        return ApiResponse.success(workspaceService.get(sessionPid));
    }

    @PatchMapping("/sessions/{sessionPid}/patches")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_MANAGE)
    public ApiResponse<PatchResult> apply(
            @PathVariable String sessionPid,
            @Valid @RequestBody ApplyPatchRequest request) {
        return ApiResponse.success(workspaceService.apply(sessionPid, request));
    }

    @PostMapping("/sessions/{sessionPid}/submit")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_MANAGE)
    public ApiResponse<ChangeSetView> submit(
            @PathVariable String sessionPid,
            @Valid @RequestBody RevisionRequest request) {
        return ApiResponse.success(governanceService.submit(sessionPid, request));
    }

    @PostMapping("/change-sets/{changeSetPid}/approve")
    @RequirePermission(MetaPermission.PAGE_PUBLISH_MANAGE)
    public ApiResponse<ChangeSetView> approve(
            @PathVariable String changeSetPid,
            @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.success(governanceService.approve(changeSetPid, request));
    }

    @PostMapping("/change-sets/{changeSetPid}/reject")
    @RequirePermission(MetaPermission.PAGE_PUBLISH_MANAGE)
    public ApiResponse<ChangeSetView> reject(
            @PathVariable String changeSetPid,
            @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.success(governanceService.reject(changeSetPid, request));
    }

    @PostMapping("/change-sets/{changeSetPid}/publish")
    @RequirePermission(MetaPermission.PAGE_PUBLISH_ADMIN)
    public ApiResponse<ReleaseView> publish(
            @PathVariable String changeSetPid,
            @Valid @RequestBody RevisionRequest request) {
        return ApiResponse.success(governanceService.publish(changeSetPid, request));
    }

    @PostMapping("/releases/{releasePid}/rollback")
    @RequirePermission(MetaPermission.PAGE_PUBLISH_ADMIN)
    public ApiResponse<ReleaseView> rollback(
            @PathVariable String releasePid,
            @Valid @RequestBody RollbackRequest request) {
        return ApiResponse.success(governanceService.rollback(releasePid, request));
    }
}
