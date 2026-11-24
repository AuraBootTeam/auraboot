package com.auraboot.framework.openplatform.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.openplatform.service.InstalledIntegrationRecoveryService;
import com.auraboot.framework.openplatform.service.InstalledIntegrationRecoveryContracts.*;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.NoSuchElementException;

/** Existing admin-surface role enforcement applies; tenant and actor never come from request data. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/open-platform/installations/{installation}/event-sources/{source}/events/{event}/recovery")
@AuthenticatedAccess("Tenant-admin operator recovery; AdminRoleInterceptor guards /api/admin/** and current tenant scopes every receipt")
public class InstalledIntegrationRecoveryController {
    private final InstalledIntegrationRecoveryService service;
    @GetMapping
    public ResponseEntity<ApiResponse<Plan>> plan(@PathVariable String installation,@PathVariable String source,@PathVariable String event) {
        try {return ResponseEntity.ok(ApiResponse.success(service.plan(MetaContext.getCurrentTenantId(),installation,source,event)));}
        catch(NoSuchElementException failure){return ResponseEntity.status(404).body(ApiResponse.error(404,failure.getMessage()));}
        catch(IllegalStateException failure){return ResponseEntity.status(409).body(ApiResponse.error(409,failure.getMessage()));}
    }
    @PostMapping
    @Operation(operationId = "authorizeReceiptRecovery")
    public ResponseEntity<ApiResponse<Result>> authorize(@PathVariable String installation,@PathVariable String source,
                                                        @PathVariable String event,@Valid @RequestBody Decision decision) {
        try {return ResponseEntity.ok(ApiResponse.success(service.authorize(MetaContext.getCurrentTenantId(),installation,source,event,
                MetaContext.getCurrentUserPid(),decision)));}
        catch(NoSuchElementException failure){return ResponseEntity.status(404).body(ApiResponse.error(404,failure.getMessage()));}
        catch(IllegalStateException failure){return ResponseEntity.status(409).body(ApiResponse.error(409,failure.getMessage()));}
    }
}
