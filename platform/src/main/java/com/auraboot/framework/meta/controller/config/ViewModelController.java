package com.auraboot.framework.meta.controller.config;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.dto.*;
import com.auraboot.framework.meta.service.ViewModelService;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.permission.constants.MetaPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;

/**
 * ViewModel API controller.
 * Provides endpoints for resolving ViewModel fields and querying data.
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/view-models")
@RequiredArgsConstructor
@Validated
@Tag(name = "ViewModel Management", description = "ViewModel field resolution and data query")
public class ViewModelController {

    private final ViewModelService viewModelService;

    @GetMapping("/{code}/resolved-fields")
    @Operation(summary = "Get resolved fields", description = "Returns the three-layer merged field list for a ViewModel")
    @RequirePermission(MetaPermission.META_MODEL_READ)
    public ApiResponse<List<ResolvedFieldDTO>> getResolvedFields(
            @Parameter(description = "ViewModel code") @PathVariable @NotBlank String code) {
        log.info("Resolving view fields for: {}", code);
        List<ResolvedFieldDTO> fields = viewModelService.resolveViewFields(code);
        return ApiResponse.success(fields);
    }

    @PostMapping("/{code}/query")
    @Operation(summary = "Query view data", description = "Execute a data query against the ViewModel's underlying data source")
    @RequirePermission(MetaPermission.META_MODEL_READ)
    public ApiResponse<PaginationResult<Map<String, Object>>> queryViewData(
            @Parameter(description = "ViewModel code") @PathVariable @NotBlank String code,
            @Valid @RequestBody NamedQueryTestRequest request) {
        log.info("Querying view data for: {}, page={}, size={}", code, request.getPage(), request.getSize());
        PaginationResult<Map<String, Object>> result = viewModelService.queryViewData(code, request);
        return ApiResponse.success(result);
    }

    @GetMapping("/{code}/summary")
    @Operation(summary = "Get ViewModel summary", description = "Returns summary information for a ViewModel")
    @RequirePermission(MetaPermission.META_MODEL_READ)
    public ApiResponse<ViewModelSummaryDTO> getSummary(
            @Parameter(description = "ViewModel code") @PathVariable @NotBlank String code) {
        log.info("Getting ViewModel summary for: {}", code);
        ViewModelSummaryDTO summary = viewModelService.getSummary(code);
        return ApiResponse.success(summary);
    }

    @PostMapping("/{code}/validate")
    @Operation(summary = "Validate ViewModel config", description = "Check ViewModel configuration for completeness and correctness")
    @RequirePermission(MetaPermission.META_MODEL_READ)
    public ApiResponse<ViewModelValidationResult> validateConfig(
            @Parameter(description = "ViewModel code") @PathVariable @NotBlank String code) {
        log.info("Validating ViewModel config for: {}", code);
        ViewModelValidationResult result = viewModelService.validateConfig(code);
        return ApiResponse.success(result);
    }

    @PostMapping("/cache/evict")
    @Operation(summary = "Evict ViewModel cache", description = "Clear all ViewModel field and summary caches")
    @RequirePermission(MetaPermission.META_MODEL_MANAGE)
    public ApiResponse<Void> evictCache() {
        log.info("Evicting all ViewModel caches");
        viewModelService.evictAllCache();
        return ApiResponse.success(null);
    }
}
