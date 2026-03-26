package com.auraboot.framework.consistency.controller;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.consistency.dto.*;
import com.auraboot.framework.consistency.service.ConsistencyRuleService;
import com.auraboot.framework.meta.dto.PaginationResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for managing cross-document consistency rules.
 */
@Slf4j
@RestController
@RequestMapping("/api/meta/consistency-rules")
@RequiredArgsConstructor
@Tag(name = "Consistency Rules", description = "Cross-document consistency rule management")
public class ConsistencyRuleController {

    private final ConsistencyRuleService consistencyRuleService;

    @GetMapping
    @Operation(summary = "List consistency rules", description = "List rules with optional source model filter")
    public ApiResponse<PaginationResult<ConsistencyRuleResponse>> listRules(
            @RequestParam(required = false) String sourceModel,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        PaginationResult<ConsistencyRuleResponse> result = consistencyRuleService.listRules(sourceModel, page, size);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get consistency rule by ID")
    @SuppressWarnings("unchecked")
    public ApiResponse<ConsistencyRuleResponse> getRuleById(@PathVariable Long id) {
        ConsistencyRuleResponse rule = consistencyRuleService.getRuleById(id);
        if (rule == null) {
            return (ApiResponse<ConsistencyRuleResponse>) (ApiResponse<?>) ApiResponse.error(ResponseCode.NOT_FOUND, "Consistency rule not found");
        }
        return ApiResponse.success(rule);
    }

    @PostMapping
    @Operation(summary = "Create a consistency rule")
    public ApiResponse<ConsistencyRuleResponse> createRule(
            @Valid @RequestBody ConsistencyRuleRequest request) {
        ConsistencyRuleResponse response = consistencyRuleService.createRule(request);
        return ApiResponse.success(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a consistency rule")
    public ApiResponse<ConsistencyRuleResponse> updateRule(
            @PathVariable Long id,
            @Valid @RequestBody ConsistencyRuleRequest request) {
        ConsistencyRuleResponse response = consistencyRuleService.updateRule(id, request);
        return ApiResponse.success(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a consistency rule")
    public ApiResponse<Boolean> deleteRule(@PathVariable Long id) {
        boolean result = consistencyRuleService.deleteRule(id);
        return ApiResponse.success(result);
    }

    @PostMapping("/validate")
    @Operation(summary = "Manually trigger consistency validation",
            description = "Validate a specific record against all applicable consistency rules")
    public ApiResponse<List<ConsistencyViolation>> validate(
            @Valid @RequestBody ConsistencyValidateRequest request) {
        List<ConsistencyViolation> violations = consistencyRuleService.validate(
                request.getModelCode(), request.getRecordId());
        return ApiResponse.success(violations);
    }
}
