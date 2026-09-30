package com.auraboot.framework.semantic.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.semantic.dto.SemanticUsageSummaryDTO;
import com.auraboot.framework.semantic.service.SemanticUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BI usage surface (BI rectification R1): roll the semantic query log up into
 * the effect metrics the rectification plan defines (adoption, latency,
 * cache absorption) instead of leaving the log as write-only telemetry.
 */
@RestController
@RequestMapping("/api/semantic/usage")
@RequiredArgsConstructor
public class SemanticUsageController {

    private final SemanticUsageService usageService;

    @GetMapping("/summary")
    @RequirePermission(MetaPermission.META_SEMANTIC_USE)
    public ApiResponse<SemanticUsageSummaryDTO> summary(
            @RequestParam(name = "days", defaultValue = "7") int days) {
        return ApiResponse.success(usageService.summary(days));
    }
}
