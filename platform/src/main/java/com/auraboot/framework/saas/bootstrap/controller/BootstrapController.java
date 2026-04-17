package com.auraboot.framework.saas.bootstrap.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.saas.bootstrap.BootstrapEngineService;
import com.auraboot.framework.saas.bootstrap.constant.BootstrapMissingPart;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapProgressResponse;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapStatusResponse;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.BootstrapStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Bootstrap API — exposes status/setup/progress endpoints for system initialization.
 * All endpoints are whitelisted (no auth required) since bootstrap runs before any user exists.
 */
@RestController
@RequestMapping("/api/bootstrap")
@RequiredArgsConstructor
public class BootstrapController {

    static final String ERR_ALREADY_INITIALIZED = "System is already initialized";
    static final String REASON_NOT_INITIALIZED = "Bootstrap not completed";

    private final BootstrapEngineService bootstrapEngineService;
    private final SystemConfigService systemConfigService;

    @GetMapping("/status")
    public ApiResponse<BootstrapStatusResponse> getStatus() {
        boolean initialized = systemConfigService.isInitialized();
        BootstrapProgressResponse progress = bootstrapEngineService.getProgress();
        boolean inProgress = BootstrapStatus.RUNNING.getCode().equals(progress.getStatus())
                          || BootstrapStatus.PENDING.getCode().equals(progress.getStatus());

        return ApiResponse.success(BootstrapStatusResponse.builder()
                .initialized(initialized)
                .inProgress(inProgress)
                .missingParts(initialized ? List.of() : List.of(BootstrapMissingPart.SYSTEM_CONFIG))
                .reason(initialized ? null : REASON_NOT_INITIALIZED)
                .build());
    }

    @PostMapping("/setup")
    public ApiResponse<Object> setup(@RequestBody BootstrapRequest request) {
        if (systemConfigService.isInitialized()) {
            return ApiResponse.error(ERR_ALREADY_INITIALIZED);
        }
        var result = bootstrapEngineService.execute(request);
        if (result.success()) {
            return ApiResponse.success(Map.of(
                    "success", true,
                    "tenantId", result.tenantId()
            ));
        } else {
            return ApiResponse.error(result.error());
        }
    }

    @GetMapping("/progress")
    public ApiResponse<BootstrapProgressResponse> getProgress() {
        return ApiResponse.success(bootstrapEngineService.getProgress());
    }
}
