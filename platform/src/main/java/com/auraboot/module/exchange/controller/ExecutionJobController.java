package com.auraboot.module.exchange.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.module.exchange.dto.ExecutionJobDTO;
import com.auraboot.module.exchange.service.ExecutionJobQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/exchange/jobs")
@RequiredArgsConstructor
@Tag(name = "Exchange Jobs", description = "Owner-scoped import, export, and document jobs")
public class ExecutionJobController {

    private final ExecutionJobQueryService queryService;

    @GetMapping
    @Operation(summary = "List recent data exchange and document jobs")
    public ApiResponse<List<ExecutionJobDTO>> recent(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(queryService.recent(limit));
    }
}
