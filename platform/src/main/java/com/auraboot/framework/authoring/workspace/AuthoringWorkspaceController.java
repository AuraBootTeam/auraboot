package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CapabilityRegistryView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ChangeSetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CreateHandoffRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.HandoffContextView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.HandoffCreatedView;
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
    private final AuthoringHandoffService handoffService;

    public AuthoringWorkspaceController(
            AuthoringWorkspaceService workspaceService,
            AuthoringGovernanceService governanceService,
            AuthoringHandoffService handoffService) {
        this.workspaceService = workspaceService;
        this.governanceService = governanceService;
        this.handoffService = handoffService;
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

    @PostMapping("/sessions/{sessionPid}/handoffs")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_MANAGE)
    public ApiResponse<HandoffCreatedView> createHandoff(
            @PathVariable String sessionPid,
            @Valid @RequestBody CreateHandoffRequest request) {
        return ApiResponse.success(handoffService.create(sessionPid, request));
    }

    @PostMapping("/handoffs/{contextId}/consume")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_MANAGE)
    public ApiResponse<HandoffContextView> consumeHandoff(@PathVariable String contextId) {
        return ApiResponse.success(handoffService.consume(contextId));
    }

    @PatchMapping("/sessions/{sessionPid}/patches")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_MANAGE)
    public ApiResponse<PatchResult> apply(
            @PathVariable String sessionPid,
            @Valid @RequestBody ApplyPatchRequest request) {
        return ApiResponse.success(workspaceService.apply(sessionPid, request));
    }

    @PatchMapping("/sessions/{sessionPid}/studio-patches")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_ADMIN)
    public ApiResponse<PatchResult> applyStudio(
            @PathVariable String sessionPid,
            @Valid @RequestBody ApplyPatchRequest request) {
        return ApiResponse.success(workspaceService.applyStudio(sessionPid, request));
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
