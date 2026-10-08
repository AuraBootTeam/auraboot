package com.auraboot.framework.semantic.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.semantic.entity.AbSemanticPreagg;
import com.auraboot.framework.semantic.service.SemanticPreaggService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Pre-aggregation engine endpoints (BI rectification R3).
 */
@RestController
@RequestMapping("/api/semantic/preaggs")
@RequiredArgsConstructor
public class SemanticPreaggController {

    private final SemanticPreaggService preaggService;

    @PostMapping
    @RequirePermission(MetaPermission.META_SEMANTIC_PUBLISH)
    public ApiResponse<AbSemanticPreagg> create(
            @RequestParam String name,
            @RequestParam String semanticModelPid,
            @RequestParam String metricCode,
            @RequestParam(required = false) List<String> dimensionCodes,
            @RequestParam(defaultValue = "60") int refreshMinutes) {
        // Normalize the omitted parameter to an empty list so the service contract
        // is "no grouping dimensions" rather than a null the caller must unpack.
        return ApiResponse.success(preaggService.create(
                name, semanticModelPid, metricCode,
                dimensionCodes == null ? List.of() : dimensionCodes, refreshMinutes));
    }

    @GetMapping
    @RequirePermission(MetaPermission.META_SEMANTIC_USE)
    public ApiResponse<List<AbSemanticPreagg>> list() {
        return ApiResponse.success(preaggService.list());
    }

    @DeleteMapping("/{pid}")
    @RequirePermission(MetaPermission.META_SEMANTIC_PUBLISH)
    public ApiResponse<Void> delete(@PathVariable String pid) {
        preaggService.delete(pid);
        return ApiResponse.success();
    }

    @PostMapping("/{pid}/refresh")
    @RequirePermission(MetaPermission.META_SEMANTIC_PUBLISH)
    public ApiResponse<Map<String, Object>> refresh(@PathVariable String pid) {
        long rows = preaggService.refreshNow(pid);
        return ApiResponse.success(Map.of("rows", rows));
    }
}
