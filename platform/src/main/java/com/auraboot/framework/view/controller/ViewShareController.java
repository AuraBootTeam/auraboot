package com.auraboot.framework.view.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.view.service.ViewShareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

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
public class ViewShareController {

    private final ViewShareService viewShareService;

    @PostMapping("/{viewPid}/share")
    @Operation(summary = "Generate a public share link for a view")
    public ApiResponse<Map<String, Object>> shareView(
            @PathVariable String viewPid,
            @RequestBody(required = false) Map<String, Object> options) {
        String password = options != null ? (String) options.get("password") : null;
        Integer expireHours = options != null && options.get("expireHours") != null
                ? ((Number) options.get("expireHours")).intValue() : null;

        Map<String, Object> result = viewShareService.createShareLink(viewPid, password, expireHours);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{viewPid}/share")
    @Operation(summary = "Revoke a public share link")
    public ApiResponse<Boolean> revokeShare(@PathVariable String viewPid) {
        viewShareService.revokeShareLink(viewPid);
        return ApiResponse.success(true);
    }

    @GetMapping("/{viewPid}/share/status")
    @Operation(summary = "Get share status for a view")
    public ApiResponse<Map<String, Object>> getShareStatus(@PathVariable String viewPid) {
        Map<String, Object> status = viewShareService.getShareStatus(viewPid);
        return ApiResponse.success(status);
    }

    @GetMapping("/shared/{shareToken}")
    @Operation(summary = "Access a shared view (public, no auth required)")
    public ApiResponse<Map<String, Object>> accessSharedView(
            @PathVariable String shareToken,
            @RequestParam(required = false) String password) {
        Map<String, Object> viewData = viewShareService.accessSharedView(shareToken, password);
        return ApiResponse.success(viewData);
    }
}
