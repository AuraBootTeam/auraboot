package com.auraboot.framework.promotion.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.promotion.dto.DryRunResult;
import com.auraboot.framework.promotion.dto.PromotionApplyRequest;
import com.auraboot.framework.promotion.dto.PromotionRequest;
import com.auraboot.framework.promotion.dto.PromotionResponse;
import com.auraboot.framework.promotion.service.PromotionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for {@link PromotionService} (env-layering #11 wire-up).
 *
 * <p>Mounted under {@code /api/admin/promotions/**} alongside environments, so the same
 * AdminRoleInterceptor coarse gate applies. Per-action permissions enforced inside the service.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/promotions")
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;

    @GetMapping
    public ApiResponse<List<PromotionResponse>> list(@RequestParam(required = false) String status) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(promotionService.listByStatus(tenantId, status));
    }

    @GetMapping("/{pid}")
    public ApiResponse<PromotionResponse> getByPid(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(promotionService.getByPid(pid, tenantId));
    }

    @PostMapping
    public ApiResponse<PromotionResponse> create(@Valid @RequestBody PromotionRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        return ApiResponse.success(promotionService.create(request, tenantId, userId));
    }

    @PostMapping("/{pid}/validate")
    public ApiResponse<DryRunResult> validate(@PathVariable String pid) {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(promotionService.validate(pid, tenantId));
    }

    @PostMapping("/{pid}/apply")
    public ApiResponse<PromotionResponse> apply(
            @PathVariable String pid,
            @Valid @RequestBody PromotionApplyRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long approverId = MetaContext.getCurrentUserId();
        return ApiResponse.success(
                promotionService.apply(pid, tenantId, approverId, request.getReason()));
    }
}
