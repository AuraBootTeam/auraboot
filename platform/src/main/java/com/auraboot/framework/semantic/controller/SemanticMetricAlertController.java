package com.auraboot.framework.semantic.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.semantic.dto.SemanticMetricAlertDTO;
import com.auraboot.framework.semantic.dto.SemanticMetricAlertRequest;
import com.auraboot.framework.semantic.service.SemanticMetricAlertService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Threshold alerts over published semantic metrics (BI rectification R2).
 *
 * <p>Authoring requires META_SEMANTIC_PUBLISH (an alert is a governance
 * artifact bound to a governed metric); reading requires META_SEMANTIC_USE.
 * The run-once evaluate endpoint is publish-gated so an author can prove an
 * alert's wiring before relying on the schedule.
 */
@RestController
@RequestMapping("/api/semantic/alerts")
@RequiredArgsConstructor
public class SemanticMetricAlertController {

    private final SemanticMetricAlertService alertService;

    @PostMapping
    @RequirePermission(MetaPermission.META_SEMANTIC_PUBLISH)
    public ApiResponse<SemanticMetricAlertDTO> create(
            @Valid @RequestBody SemanticMetricAlertRequest request) {
        return ApiResponse.success(alertService.create(request));
    }

    @GetMapping
    @RequirePermission(MetaPermission.META_SEMANTIC_USE)
    public ApiResponse<List<SemanticMetricAlertDTO>> list() {
        return ApiResponse.success(alertService.list());
    }

    @PutMapping("/{pid}")
    @RequirePermission(MetaPermission.META_SEMANTIC_PUBLISH)
    public ApiResponse<SemanticMetricAlertDTO> update(@PathVariable String pid,
                                      @Valid @RequestBody SemanticMetricAlertRequest request) {
        return ApiResponse.success(alertService.update(pid, request));
    }

    @DeleteMapping("/{pid}")
    @RequirePermission(MetaPermission.META_SEMANTIC_PUBLISH)
    public ApiResponse<Void> delete(@PathVariable String pid) {
        alertService.delete(pid);
        return ApiResponse.success();
    }

    /** Run one evaluation cycle immediately and return the facts. */
    @PostMapping("/{pid}/evaluate")
    @RequirePermission(MetaPermission.META_SEMANTIC_PUBLISH)
    public ApiResponse<Map<String, Object>> evaluate(@PathVariable String pid) {
        return ApiResponse.success(alertService.evaluateNow(pid));
    }
}
