package com.auraboot.framework.auth.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.DeactivationRequest;
import com.auraboot.framework.auth.dto.DeactivationResponse;
import com.auraboot.framework.auth.entity.UserDeactivation;
import com.auraboot.framework.auth.service.UserDeactivationService;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for user account deactivation.
 * All endpoints require an authenticated user (JWT token).
 *
 * @since 7.1.0
 */
@RestController
@RequestMapping("/api/auth/deactivation")
@RequiredArgsConstructor
public class DeactivationController {

    private final UserDeactivationService deactivationService;

    /**
     * Request account deactivation. Starts a 7-day cooling-off period.
     */
    @PostMapping("/request")
    public ApiResponse<DeactivationResponse> requestDeactivation(
            @RequestBody DeactivationRequest request) {
        Long userId = MetaContext.getCurrentUserId();
        UserDeactivation deactivation = deactivationService.requestDeactivation(
                userId, request.getReason(), request.getConsentSnapshot());
        return ApiResponse.success(toResponse(deactivation));
    }

    /**
     * Cancel an active deactivation during the cooling-off period.
     */
    @PostMapping("/cancel")
    public ApiResponse<Void> cancelDeactivation() {
        Long userId = MetaContext.getCurrentUserId();
        deactivationService.cancelDeactivation(userId);
        return ApiResponse.success();
    }

    /**
     * Get the current deactivation status for the authenticated user.
     * Returns null data if no active deactivation exists.
     */
    @GetMapping("/status")
    public ApiResponse<DeactivationResponse> getStatus() {
        Long userId = MetaContext.getCurrentUserId();
        UserDeactivation deactivation = deactivationService.getDeactivationStatus(userId);
        if (deactivation == null) {
            return ApiResponse.success(null);
        }
        return ApiResponse.success(toResponse(deactivation));
    }

    private DeactivationResponse toResponse(UserDeactivation entity) {
        return DeactivationResponse.builder()
                .pid(entity.getPid())
                .status(entity.getStatus())
                .reason(entity.getReason())
                .requestedAt(entity.getRequestedAt())
                .coolingOffUntil(entity.getCoolingOffUntil())
                .cancelledAt(entity.getCancelledAt())
                .completedAt(entity.getCompletedAt())
                .build();
    }
}
