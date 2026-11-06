package com.auraboot.framework.permission.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.cache.DataAccessCacheEpoch;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.service.impl.PermissionSnapshotCache;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual permission-cache eviction trigger.
 *
 * <p>The event-driven eviction covers application writes (role bindings, data scopes,
 * member roles). Changes made outside those paths — manual SQL fixes, data repairs,
 * out-of-band migrations — leave stale permission snapshots until the TTL expires.
 * This endpoint is the operator's explicit trigger for exactly that case: it clears the
 * permission snapshot caches entirely and bumps the data-access cache epoch, without
 * touching any data. The caches are intentionally small and short-lived, so a full sweep
 * is the honest scope — partial per-tenant eviction would promise freshness it cannot
 * deliver for shared projections.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/permission-cache")
@RequiredArgsConstructor
@Validated
@Tag(name = "Permission cache administration", description = "Manual eviction of permission snapshot caches")
public class PermissionCacheAdminController {

    private final PermissionSnapshotCache permissionSnapshotCache;

    @PostMapping("/evict")
    @Operation(summary = "Evict permission caches",
            description = "Manual trigger for permission snapshot eviction after out-of-band changes")
    @RequirePermission(MetaPermission.PERMISSION_MANAGE)
    public ApiResponse<String> evict(
            @RequestParam @NotBlank String reason) {
        log.warn("Manual permission-cache eviction requested: reason={}", reason);
        permissionSnapshotCache.clearAll();
        DataAccessCacheEpoch.bump();
        return ApiResponse.success("permission caches evicted");
    }
}
