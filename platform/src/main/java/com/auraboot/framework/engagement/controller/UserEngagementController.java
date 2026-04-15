package com.auraboot.framework.engagement.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.engagement.dto.UserEngagementDTO;
import com.auraboot.framework.engagement.service.UserEngagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/user-engagement")
@RequiredArgsConstructor
@Tag(name = "User Engagement", description = "Favorites, recent views, bookmarks")
public class UserEngagementController {

    private final UserEngagementService engagementService;

    /**
     * List engagement records for the current user.
     *
     * @param engagementType required: favorite | recent_view | pinned
     * @param targetType     optional: menu | record | page
     */
    @GetMapping
    @Operation(summary = "List engagement records")
    public ApiResponse<List<UserEngagementDTO>> list(
            @RequestParam String engagementType,
            @RequestParam(required = false) String targetType) {
        return ApiResponse.ok(engagementService.list(
                MetaContext.getCurrentUserId(),
                MetaContext.getCurrentTenantId(),
                engagementType,
                targetType));
    }

    /**
     * Add or update an engagement record (upsert by composite key).
     */
    @PostMapping
    @Operation(summary = "Add or update engagement record")
    public ApiResponse<UserEngagementDTO> upsert(@RequestBody UserEngagementDTO dto) {
        return ApiResponse.ok(engagementService.upsert(
                MetaContext.getCurrentUserId(),
                MetaContext.getCurrentTenantId(),
                dto));
    }

    /**
     * Remove an engagement record.
     * Only the owning user may delete their own records.
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Remove engagement record")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        engagementService.delete(id, MetaContext.getCurrentUserId());
        return ApiResponse.ok(null);
    }

    /**
     * Reorder favorites or pinned items.
     * Accepts an ordered list of IDs; sortOrder is set to array index.
     */
    @PutMapping("/reorder")
    @Operation(summary = "Reorder favorites/pins")
    public ApiResponse<Void> reorder(@RequestBody UserEngagementDTO.ReorderRequest request) {
        engagementService.reorder(MetaContext.getCurrentUserId(), request.getOrderedIds());
        return ApiResponse.ok(null);
    }
}
