package com.auraboot.framework.saas.bootstrap.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.saas.bootstrap.BootstrapEngineService;
import com.auraboot.framework.saas.bootstrap.BootstrapStatusEvaluator;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapProgressResponse;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import com.auraboot.framework.saas.bootstrap.dto.BootstrapStatusResponse;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Bootstrap API — exposes status/setup/progress endpoints for system initialization.
 * All endpoints are whitelisted (no auth required) since bootstrap runs before any user exists.
 */
@RestController
@RequestMapping("/api/bootstrap")
@RequiredArgsConstructor
public class BootstrapController {

    private final BootstrapEngineService bootstrapEngineService;
    private final BootstrapStatusEvaluator statusEvaluator;
    private final SystemConfigService systemConfigService;

    @GetMapping("/status")
    public ApiResponse<BootstrapStatusResponse> getStatus() {
        BootstrapStatusEvaluator.Result eval = statusEvaluator.evaluate();
        BootstrapProgressResponse progress = bootstrapEngineService.getProgress();
        boolean inProgress = "running".equals(progress.getStatus())
                          || "pending".equals(progress.getStatus());

        return ApiResponse.success(BootstrapStatusResponse.builder()
                .initialized(eval.missingParts().isEmpty())
                .inProgress(inProgress)
                .missingParts(eval.missingParts())
                .reason(eval.reason())
                .build());
    }

    @PostMapping("/setup")
    public ApiResponse<Object> setup(@RequestBody BootstrapRequest request) {
        if (systemConfigService.isInitialized()) {
            return ApiResponse.error("System is already initialized");
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
