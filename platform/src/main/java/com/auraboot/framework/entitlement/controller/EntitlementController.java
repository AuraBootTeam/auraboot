package com.auraboot.framework.entitlement.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.entitlement.spi.EntitlementSnapshotService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/entitlements")
@AuthenticatedAccess("read-only entitlement snapshot for the current caller's tenant")
public class EntitlementController {
    private final EntitlementSnapshotService entitlementSnapshotService;

    public EntitlementController(EntitlementSnapshotService entitlementSnapshotService) {
        this.entitlementSnapshotService = entitlementSnapshotService;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> listEntitlements() {
        return ApiResponse.ok(entitlementSnapshotService.getSnapshot());
    }
}
