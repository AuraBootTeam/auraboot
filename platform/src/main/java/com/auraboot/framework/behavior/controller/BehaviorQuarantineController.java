package com.auraboot.framework.behavior.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.behavior.dto.BehaviorQuarantineReplayBatchResult;
import com.auraboot.framework.behavior.dto.BehaviorQuarantineReplayResult;
import com.auraboot.framework.behavior.entity.BehaviorQuarantine;
import com.auraboot.framework.behavior.service.BehaviorQuarantineService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.common.util.PaginationSafetyUtils;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-scoped operator API for inspecting and replaying the behavior ingest quarantine sink.
 *
 * <p>Authorization: login-required, no RBAC role distinction (owner decision
 * 2026-07-02: operator/viewer split is not needed here). All operations are
 * tenant-scoped via {@link MetaContext#getCurrentTenantId()}, so a logged-in
 * tenant user only ever sees / replays their own tenant's quarantine sink.
 */
@RestController
@RequestMapping("/api/analytics/behavior/quarantine")
@RequiredArgsConstructor
@AuthenticatedAccess("tenant-scoped behavior-quarantine inspect/replay; owner decision 2026-07-02: "
        + "login-required, no operator/viewer role distinction. Tenant-isolated via MetaContext.")
public class BehaviorQuarantineController {

    private final BehaviorQuarantineService service;

    @GetMapping
    public ApiResponse<PageResult<BehaviorQuarantine>> list(
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String replayStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Integer pageNum,
            @RequestParam(required = false) Integer pageSize) {
        int apiPage = pageNum != null ? PaginationSafetyUtils.pageNumber(pageNum) - 1 : page;
        int apiSize = pageSize != null ? pageSize : size;
        return ApiResponse.success(service.list(MetaContext.getCurrentTenantId(), reason, replayStatus, apiPage, apiSize));
    }

    @PostMapping("/{id}/replay")
    public ApiResponse<BehaviorQuarantineReplayResult> replayOne(@PathVariable Long id) {
        return ApiResponse.success(service.replayOne(MetaContext.getCurrentTenantId(), id));
    }

    @PostMapping("/replay")
    public ApiResponse<BehaviorQuarantineReplayBatchResult> replayPending(
            @RequestParam(required = false) String reason,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(service.replayPending(MetaContext.getCurrentTenantId(), reason, limit));
    }
}
