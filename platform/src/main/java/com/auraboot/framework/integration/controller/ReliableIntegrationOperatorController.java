package com.auraboot.framework.integration.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.common.dto.PageResult;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.DeadLetterDetail;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.DeadLetterSummary;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.ReplayRequest;
import com.auraboot.framework.integration.ReliableIntegrationOperatorContracts.ReplayResult;
import com.auraboot.framework.integration.ReliableIntegrationOperatorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/** Tenant-admin public API for inspecting and replaying reliable-integration dead letters. */
@RestController
@RequestMapping("/api/admin/reliable-integration/dead-letters")
@RequiredArgsConstructor
public class ReliableIntegrationOperatorController {

    private final ReliableIntegrationOperatorService service;

    @GetMapping
    public ApiResponse<PageResult<DeadLetterSummary>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String correlationId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.success(service.list(MetaContext.getCurrentTenantId(), status, eventType,
                correlationId, pageNum, pageSize));
    }

    @GetMapping("/{eventId}")
    public ApiResponse<DeadLetterDetail> detail(@PathVariable String eventId) {
        try {
            return ApiResponse.success(service.detail(MetaContext.getCurrentTenantId(), eventId));
        } catch (NoSuchElementException exception) {
            return ApiResponse.error(404, exception.getMessage());
        }
    }

    @PostMapping("/{eventId}/replay")
    public ApiResponse<ReplayResult> replay(@PathVariable String eventId,
                                            @Valid @RequestBody ReplayRequest request) {
        try {
            return ApiResponse.success(service.replay(MetaContext.getCurrentTenantId(), eventId,
                    MetaContext.getCurrentUserPid(), request.reason(), request.expectedReplayCount()));
        } catch (NoSuchElementException exception) {
            return ApiResponse.error(404, exception.getMessage());
        } catch (IllegalStateException exception) {
            return ApiResponse.error(409, exception.getMessage());
        }
    }
}
