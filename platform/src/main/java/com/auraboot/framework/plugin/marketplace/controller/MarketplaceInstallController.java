package com.auraboot.framework.plugin.marketplace.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.marketplace.dto.*;
import com.auraboot.framework.plugin.marketplace.service.MarketplaceInstallService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/marketplace")
@RequiredArgsConstructor
@RequirePermission("plugin.plugin.manage")
@Tag(name = "Marketplace Install", description = "Plugin installation APIs")
public class MarketplaceInstallController {

    private final MarketplaceInstallService installService;

    @PostMapping("/plugins/{pluginId}/install")
    @Operation(summary = "Install a plugin from marketplace")
    public ApiResponse<ImportExecuteResult> install(
            @PathVariable String pluginId,
            @RequestBody(required = false) MarketplaceInstallRequest request) {
        if (request == null) {
            request = new MarketplaceInstallRequest();
        }
        return ApiResponse.ok(installService.install(pluginId, request));
    }

    @GetMapping("/installed")
    @Operation(summary = "Get installed plugins")
    public ApiResponse<List<MarketplacePluginDTO>> getInstalled() {
        return ApiResponse.ok(installService.getInstalled());
    }

    @PostMapping("/plugins/{pluginId}/uninstall")
    @Operation(summary = "Uninstall a plugin")
    public ApiResponse<Void> uninstall(@PathVariable String pluginId) {
        installService.uninstall(pluginId);
        return ApiResponse.ok();
    }
}
