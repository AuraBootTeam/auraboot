package com.auraboot.framework.authoring.workspace;

import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ApplyPatchRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CapabilityRegistryView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ChangeItemView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ChangeSetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.CreateHandoffRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.HandoffContextView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.HandoffCreatedView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.MoveBlockRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ObserveChangeSetRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.OpenSessionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.PatchResult;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReleaseHistoryView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReleaseView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReviewRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ReviewWorkspaceView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RevisionRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.RollbackRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.ResumeEditingRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SessionView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SplitChangeSetRequest;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.SplitChangeSetView;
import com.auraboot.framework.authoring.workspace.AuthoringWorkspaceContracts.TakeoverWriterLeaseRequest;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Permission-rechecked HTTP boundary for contextual-authoring sessions. */
@RestController
@RequestMapping("/api/authoring")
@Validated
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

    @PostMapping("/change-sets/{changeSetPid}/sessions")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_ADMIN)
    public ApiResponse<SessionView> observe(
            @PathVariable String changeSetPid,
            @Valid @RequestBody(required = false) ObserveChangeSetRequest request) {
        return ApiResponse.success(workspaceService.observe(changeSetPid, request));
    }

    @PostMapping("/change-sets/{changeSetPid}/review-workspaces")
    @RequirePermission(MetaPermission.PAGE_PUBLISH_MANAGE)
    public ApiResponse<ReviewWorkspaceView> openReviewWorkspace(
            @PathVariable String changeSetPid,
            @Valid @RequestBody(required = false) ObserveChangeSetRequest request) {
        return ApiResponse.success(workspaceService.openReviewWorkspace(changeSetPid, request));
    }

    @GetMapping("/review-workspaces/{sessionPid}")
    @RequirePermission(MetaPermission.PAGE_PUBLISH_MANAGE)
    public ApiResponse<ReviewWorkspaceView> getReviewWorkspace(@PathVariable String sessionPid) {
        return ApiResponse.success(workspaceService.getReviewWorkspace(sessionPid));
    }

    @PostMapping("/sessions/{sessionPid}/writer-lease/takeover")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_ADMIN)
    public ApiResponse<SessionView> takeoverWriterLease(
            @PathVariable String sessionPid,
            @Valid @RequestBody TakeoverWriterLeaseRequest request) {
        return ApiResponse.success(workspaceService.takeoverWriterLease(sessionPid, request));
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

    @PatchMapping("/sessions/{sessionPid}/studio-moves")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_ADMIN)
    public ApiResponse<PatchResult> moveStudioBlock(
            @PathVariable String sessionPid,
            @Valid @RequestBody MoveBlockRequest request) {
        return ApiResponse.success(workspaceService.moveStudioBlock(sessionPid, request));
    }

    @GetMapping("/sessions/{sessionPid}/change-items")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_ADMIN)
    public ApiResponse<List<ChangeItemView>> listChangeItems(@PathVariable String sessionPid) {
        return ApiResponse.success(governanceService.listChangeItems(sessionPid));
    }

    @PostMapping("/sessions/{sessionPid}/split")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_ADMIN)
    public ApiResponse<SplitChangeSetView> splitChangeSet(
            @PathVariable String sessionPid,
            @Valid @RequestBody SplitChangeSetRequest request) {
        return ApiResponse.success(governanceService.split(sessionPid, request));
    }

    @PostMapping("/sessions/{sessionPid}/submit")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_MANAGE)
    public ApiResponse<ChangeSetView> submit(
            @PathVariable String sessionPid,
            @Valid @RequestBody RevisionRequest request) {
        return ApiResponse.success(governanceService.submit(sessionPid, request));
    }

    @PostMapping("/sessions/{sessionPid}/prepare")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_MANAGE)
    public ApiResponse<SessionView> prepare(
            @PathVariable String sessionPid,
            @Valid @RequestBody RevisionRequest request) {
        return ApiResponse.success(governanceService.prepare(sessionPid, request));
    }

    @PostMapping("/sessions/{sessionPid}/review/withdraw")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_MANAGE)
    public ApiResponse<ChangeSetView> withdrawReview(
            @PathVariable String sessionPid,
            @Valid @RequestBody ResumeEditingRequest request) {
        return ApiResponse.success(governanceService.withdrawReview(sessionPid, request));
    }

    @PostMapping("/sessions/{sessionPid}/approved/reopen")
    @RequirePermission(MetaPermission.PAGE_DESIGNER_MANAGE)
    public ApiResponse<ChangeSetView> reopenApproved(
            @PathVariable String sessionPid,
            @Valid @RequestBody ResumeEditingRequest request) {
        return ApiResponse.success(governanceService.reopenApproved(sessionPid, request));
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

    @GetMapping("/change-sets/{changeSetPid}/releases")
    @RequirePermission(MetaPermission.PAGE_PUBLISH_READ)
    public ApiResponse<ReleaseHistoryView> releaseHistory(
            @PathVariable String changeSetPid,
            @RequestParam(defaultValue = "1") @Min(1) @Max(100_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(governanceService.releaseHistory(changeSetPid, page, size));
    }

    @PostMapping("/releases/{releasePid}/rollback")
    @RequirePermission(MetaPermission.PAGE_PUBLISH_ADMIN)
    public ApiResponse<ReleaseView> rollback(
            @PathVariable String releasePid,
            @Valid @RequestBody RollbackRequest request) {
        return ApiResponse.success(governanceService.rollback(releasePid, request));
    }
}
