package com.auraboot.framework.plugin.marketplace.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.plugin.marketplace.dto.SolutionInstallResult;
import com.auraboot.framework.plugin.marketplace.service.SolutionInstallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/marketplace/solutions")
@RequiredArgsConstructor
@RequirePermission("plugin.plugin.manage")
@Tag(name = "Solution Install", description = "Solution installation APIs")
public class SolutionInstallController {

    private final SolutionInstallService installService;

    @PostMapping("/{code}/install")
    @Operation(summary = "Install a solution (installs all bundled plugins)")
    public ApiResponse<SolutionInstallResult> install(@PathVariable String code) {
        return ApiResponse.ok(installService.install(code));
    }

    @PostMapping("/{code}/uninstall")
    @Operation(summary = "Uninstall a solution")
    public ApiResponse<Void> uninstall(@PathVariable String code) {
        installService.uninstall(code);
        return ApiResponse.ok();
    }
}
