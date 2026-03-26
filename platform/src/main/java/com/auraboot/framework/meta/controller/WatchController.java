package com.auraboot.framework.meta.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.service.WatchService;
import com.auraboot.framework.meta.util.PageKeyConverter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST endpoints for record watch/follow/subscribe.
 *
 * Mounted under /api/dynamic/{pageKey}/{recordId}/watch to align with
 * the existing DynamicController URL namespace.
 *
 * @since 6.1.0
 */
@RestController
@RequestMapping("/api/dynamic")
@RequiredArgsConstructor
@Tag(name = "Watch/Follow", description = "Record watch/follow subscription endpoints")
public class WatchController {

    private final WatchService watchService;

    @PostMapping("/{pageKey}/{recordId}/watch")
    @Operation(summary = "Toggle watch", description = "Toggle the current user's watch state on a record. Returns the new state.")
    public ApiResponse<Map<String, Boolean>> toggleWatch(
            @PathVariable String pageKey,
            @PathVariable Long recordId) {
        String modelCode = PageKeyConverter.toModelCode(pageKey);
        boolean isNowWatching = watchService.toggleWatch(modelCode, recordId);
        return ApiResponse.success(Map.of("watching", isNowWatching));
    }

    @GetMapping("/{pageKey}/{recordId}/watching")
    @Operation(summary = "Check watch state", description = "Check if the current user is watching a specific record.")
    public ApiResponse<Map<String, Boolean>> isWatching(
            @PathVariable String pageKey,
            @PathVariable Long recordId) {
        String modelCode = PageKeyConverter.toModelCode(pageKey);
        boolean watching = watchService.isWatching(modelCode, recordId);
        return ApiResponse.success(Map.of("watching", watching));
    }
}
