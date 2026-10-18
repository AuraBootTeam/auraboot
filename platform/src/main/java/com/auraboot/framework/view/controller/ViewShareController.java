package com.auraboot.framework.view.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.view.service.ViewShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import com.auraboot.framework.view.dto.ViewShareRequest;
import com.auraboot.framework.view.dto.ViewShareResponse;
import com.auraboot.framework.view.dto.ViewShareStatusResponse;
import com.auraboot.framework.view.dto.PublicSharedViewResponse;

/**
 * View Share Controller (GAP-121)
 *
 * Provides REST API for generating, revoking, and accessing
 * public share links for SavedViews.
 */
@Slf4j
@RestController
@RequestMapping("/api/views")
@RequiredArgsConstructor
@Tag(name = "View Sharing", description = "Public sharing and embedding of saved views")
@AuthenticatedAccess("unguarded handler is the share-link access endpoint scoped by the share token and optional password")
public class ViewShareController {

    private final ViewShareService viewShareService;

    @PostMapping("/{viewPid}/share")
    @RequirePermission(MetaPermission.VIEW_PUBLIC_SHARE)
    @Operation(summary = "Generate a public share link for a view")
    public ApiResponse<ViewShareResponse> shareView(
            @PathVariable String viewPid,
            @RequestBody(required = false) ViewShareRequest options) {
        String password = options != null ? options.password() : null;
        Integer expireHours = options != null && options.expireHours() != null
                ? options.expireHours() : null;

        ViewShareResponse result = viewShareService.createShareLink(viewPid, password, expireHours);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{viewPid}/share")
    @RequirePermission(MetaPermission.VIEW_PUBLIC_SHARE)
    @Operation(summary = "Revoke a public share link")
    public ApiResponse<Boolean> revokeShare(@PathVariable String viewPid) {
        viewShareService.revokeShareLink(viewPid);
        return ApiResponse.success(true);
    }

    @GetMapping("/{viewPid}/share/status")
    @RequirePermission(MetaPermission.VIEW_PUBLIC_SHARE)
    @Operation(summary = "Get share status for a view")
    public ApiResponse<ViewShareStatusResponse> getShareStatus(@PathVariable String viewPid) {
        ViewShareStatusResponse status = viewShareService.getShareStatus(viewPid);
        return ApiResponse.success(status);
    }

    @GetMapping("/shared/{shareToken}")
    @Operation(summary = "Access a shared view (public, no auth required)")
    public ApiResponse<PublicSharedViewResponse> accessSharedView(
            @PathVariable String shareToken,
            @RequestParam(required = false) String password) {
        PublicSharedViewResponse viewData = viewShareService.accessSharedView(shareToken, password);
        return ApiResponse.success(viewData);
    }
}
