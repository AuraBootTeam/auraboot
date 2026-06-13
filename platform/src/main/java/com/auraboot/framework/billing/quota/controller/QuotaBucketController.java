package com.auraboot.framework.billing.quota.controller;

import com.auraboot.framework.billing.quota.spi.QuotaService;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Read-only API for quota bucket balances.
 *
 * <p>Intended for billing dashboards and monitoring.  Write paths (reserve/commit/release)
 * are internal SPI calls, not exposed via HTTP in the OSS tier.
 */
@Tag(name = "Billing Quota", description = "Quota bucket balance queries")
@RestController
@RequestMapping("/api/billing/quota")
@RequiredArgsConstructor
public class QuotaBucketController {

    private final QuotaService quotaService;

    /**
     * List active quota buckets for an account and resource type.
     *
     * <p>Returns buckets ordered by priority ascending (consumption order).
     *
     * @param accountId    account identifier
     * @param resourceCode resource type code (e.g. {@code AI_TOKEN})
     */
    @Operation(summary = "List active quota bucket balances for an account")
    @GetMapping("/buckets")
    @RequirePermission(MetaPermission.BILLING_QUOTA_READ)
    public ApiResponse<List<QuotaBucketBalanceDto>> listBuckets(
            @Parameter(description = "Account ID", required = true)
            @RequestParam Long accountId,
            @Parameter(description = "Resource code (e.g. AI_TOKEN)")
            @RequestParam(required = false) String resourceCode) {

        if (resourceCode != null && !resourceCode.isBlank()) {
            List<QuotaBucketBalanceDto> dtos = quotaService.listActiveBuckets(accountId, resourceCode)
                    .stream()
                    .map(QuotaBucketBalanceDto::from)
                    .collect(Collectors.toList());
            return ApiResponse.ok(dtos);
        }
        // resourceCode not supplied — return empty (avoid full-account scan in OSS base)
        return ApiResponse.ok(List.of());
    }
}
