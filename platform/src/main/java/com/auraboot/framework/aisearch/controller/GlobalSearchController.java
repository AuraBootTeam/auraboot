package com.auraboot.framework.aisearch.controller;

import com.auraboot.framework.aisearch.dto.GlobalSearchCandidates;
import com.auraboot.framework.aisearch.dto.GlobalSearchPreference;
import com.auraboot.framework.aisearch.dto.GlobalSearchPreferenceRequest;
import com.auraboot.framework.aisearch.dto.GlobalSearchResult;
import com.auraboot.framework.aisearch.service.GlobalSearchService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified cross-model global search endpoint backing the header search
 * palette. Access is bounded per model inside the service: only models the
 * caller may read (model.&lt;code&gt;.read) are searched, so authorization is
 * enforced per candidate rather than per endpoint.
 *
 * @author AuraBoot Team
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@AuthenticatedAccess("self-scoped cross-model search: candidates are converged to models the "
        + "caller may read (model.<code>.read via UserPermissionService, fail-closed) before any "
        + "query runs; execution goes through DynamicDataService (tenant + data scope applied)")
@Tag(name = "Global Search", description = "Cross-model global search with per-model read permission enforcement")
public class GlobalSearchController {

    private final GlobalSearchService globalSearchService;

    @GetMapping("/global")
    @Operation(
            summary = "Global cross-model search",
            description = "Search records across the models the caller may read. Results are "
                    + "grouped by model; models without read permission are absent from the response."
    )
    public ApiResponse<GlobalSearchResult> search(
            @Parameter(description = "Search keyword", required = true)
            @RequestParam String keyword,

            @Parameter(description = "Max records per model (1-10, default 3)")
            @RequestParam(required = false) Integer perModelLimit,

            @Parameter(description = "Max model groups (1-40, default 12)")
            @RequestParam(required = false) Integer maxModels) {

        GlobalSearchResult result = globalSearchService.search(
                MetaContext.getCurrentUserId(),
                MetaContext.getCurrentTenantId(),
                keyword,
                perModelLimit,
                maxModels);
        return ApiResponse.success(result);
    }

    @GetMapping("/global/candidates")
    @Operation(
            summary = "List personal global-search candidates",
            description = "Returns only models the caller may read, with the current personal selection."
    )
    public ApiResponse<GlobalSearchCandidates> candidates() {
        return ApiResponse.success(globalSearchService.listCandidates(
                MetaContext.getCurrentUserId(), MetaContext.getCurrentTenantId()));
    }

    @GetMapping("/global/preferences")
    @Operation(summary = "Get personal global-search preference")
    public ApiResponse<GlobalSearchPreference> getPreference() {
        return ApiResponse.success(globalSearchService.getSearchPreference(
                MetaContext.getCurrentUserId(), MetaContext.getCurrentTenantId()));
    }

    @PutMapping("/global/preferences")
    @Operation(
            summary = "Save personal global-search preference",
            description = "Stores an ordered enabled-model selection after normalizing it to the live readable set."
    )
    public ApiResponse<GlobalSearchPreference> savePreference(
            @RequestBody GlobalSearchPreferenceRequest request) {
        return ApiResponse.success(globalSearchService.saveSearchPreference(
                MetaContext.getCurrentUserId(), MetaContext.getCurrentTenantId(), request.getModelCodes()));
    }
}
