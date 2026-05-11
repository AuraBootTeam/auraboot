package com.auraboot.framework.plugin.marketplace.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.plugin.marketplace.dto.*;
import com.auraboot.framework.plugin.marketplace.entity.MarketplaceCategory;
import com.auraboot.framework.plugin.marketplace.service.MarketplaceBrowseService;
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
@Tag(name = "Marketplace Browse", description = "Plugin marketplace browsing APIs")
public class MarketplaceBrowseController {

    private final MarketplaceBrowseService browseService;

    // lgtm[java/spring-disabled-csrf-protection] Read-only JWT API; CSRF is disabled centrally for stateless bearer-token authentication.
    @GetMapping("/plugins")
    @Operation(summary = "Search marketplace plugins")
    public ApiResponse<List<MarketplacePluginDTO>> searchPlugins(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "popular") String sort) {
        return ApiResponse.ok(browseService.search(keyword, category, sort));
    }

    // lgtm[java/spring-disabled-csrf-protection] Read-only JWT API; CSRF is disabled centrally for stateless bearer-token authentication.
    @GetMapping("/plugins/{pluginId}")
    @Operation(summary = "Get plugin detail")
    public ApiResponse<MarketplacePluginDetailDTO> getPluginDetail(@PathVariable String pluginId) {
        return ApiResponse.ok(browseService.getDetail(pluginId));
    }

    // lgtm[java/spring-disabled-csrf-protection] Read-only JWT API; CSRF is disabled centrally for stateless bearer-token authentication.
    @GetMapping("/plugins/{pluginId}/versions")
    @Operation(summary = "Get plugin versions")
    public ApiResponse<List<MarketplaceVersionDTO>> getVersions(@PathVariable String pluginId) {
        return ApiResponse.ok(browseService.getVersions(pluginId));
    }

    // lgtm[java/spring-disabled-csrf-protection] Read-only JWT API; CSRF is disabled centrally for stateless bearer-token authentication.
    @GetMapping("/categories")
    @Operation(summary = "Get marketplace categories")
    public ApiResponse<List<MarketplaceCategory>> getCategories() {
        return ApiResponse.ok(browseService.getCategories());
    }

    // lgtm[java/spring-disabled-csrf-protection] Read-only JWT API; CSRF is disabled centrally for stateless bearer-token authentication.
    @GetMapping("/featured")
    @Operation(summary = "Get featured plugins")
    public ApiResponse<List<MarketplacePluginDTO>> getFeatured() {
        return ApiResponse.ok(browseService.getFeatured());
    }
}
