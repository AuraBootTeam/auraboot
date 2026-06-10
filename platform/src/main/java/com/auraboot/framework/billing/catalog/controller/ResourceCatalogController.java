package com.auraboot.framework.billing.catalog.controller;

import com.auraboot.framework.billing.catalog.model.ResourceCatalog;
import com.auraboot.framework.billing.catalog.spi.ResourceCatalogService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only API for the Resource Catalog.
 *
 * <p>Consumers (quota configuration UIs, billing dashboards) use this endpoint
 * to enumerate available resource types without coupling to the catalog DB
 * directly.
 */
@Tag(name = "Billing Resource Catalog", description = "Read-only catalog of billing/quota resource types")
@RestController
@RequestMapping("/api/billing/resource-catalog")
@RequiredArgsConstructor
public class ResourceCatalogController {

    private final ResourceCatalogService resourceCatalogService;

    /**
     * List all active resource catalog entries.
     *
     * <p>Ordered by category then resource_code.
     */
    @Operation(summary = "List active resource catalog entries")
    @GetMapping
    @RequirePermission(MetaPermission.BILLING_CATALOG_READ)
    public ApiResponse<List<ResourceCatalog>> listActive() {
        return ApiResponse.ok(resourceCatalogService.listActive());
    }
}
