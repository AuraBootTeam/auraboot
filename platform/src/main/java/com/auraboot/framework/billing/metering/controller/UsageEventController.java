package com.auraboot.framework.billing.metering.controller;

import com.auraboot.framework.billing.metering.model.UsageEvent;
import com.auraboot.framework.billing.metering.spi.MeteringService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read-only HTTP API for usage event queries.
 *
 * <p>Write ingestion (record) is an internal SPI call — not exposed as HTTP
 * in the OSS tier.  Enterprise M3 may add an authenticated write endpoint for
 * external service integration.
 *
 * <p>All endpoints require {@link MetaPermission#BILLING_USAGE_READ}.
 */
@Tag(name = "Billing Metering", description = "Usage event queries")
@RestController
@RequestMapping("/api/billing/usage")
@RequiredArgsConstructor
public class UsageEventController {

    private final MeteringService meteringService;

    /**
     * Look up a usage event by its server-assigned event code.
     *
     * @param eventCode the stable event code (e.g. {@code UE-...})
     */
    @Operation(summary = "Get a usage event by event code")
    @GetMapping("/events/{eventCode}")
    @RequirePermission(MetaPermission.BILLING_USAGE_READ)
    public ApiResponse<UsageEvent> getByCode(
            @Parameter(description = "Server-assigned event code", required = true)
            @PathVariable String eventCode) {
        return meteringService.findByCode(eventCode)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.error("Usage event not found: " + eventCode));
    }

    /**
     * List recent usage events for an account, optionally filtered by resource type.
     *
     * <p>Returns at most 100 events ordered by occurred_at DESC.
     * For production analytics use the enterprise M3 reporting pipeline.
     *
     * @param accountId    account identifier
     * @param resourceCode optional resource type filter (e.g. {@code AI_TOKEN})
     */
    @Operation(summary = "List recent usage events for an account")
    @GetMapping("/events")
    @RequirePermission(MetaPermission.BILLING_USAGE_READ)
    public ApiResponse<List<UsageEvent>> listEvents(
            @Parameter(description = "Account ID", required = true)
            @RequestParam Long accountId,
            @Parameter(description = "Resource code filter (e.g. AI_TOKEN)")
            @RequestParam(required = false) String resourceCode) {
        List<UsageEvent> events = meteringService.listByAccount(accountId, resourceCode, 100);
        return ApiResponse.ok(events);
    }
}
