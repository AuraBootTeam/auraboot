package com.auraboot.framework.notification.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.notification.service.DeviceTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for mobile device push token management.
 *
 * @since 6.4.0
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/device-tokens")
@RequiredArgsConstructor
@Tag(name = "Device Tokens", description = "Push notification device token management")
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    /**
     * Register a device token for push notifications.
     * POST /api/mobile/device-tokens
     */
    @PostMapping
    @Operation(summary = "Register device token")
    public ApiResponse<Void> registerToken(@RequestBody Map<String, String> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.get().getUserId();

        String platform = body.get("platform");
        String pushToken = body.get("pushToken");
        String deviceId = body.get("deviceId");
        String tokenType = body.getOrDefault("tokenType", "apns");
        String appVersion = body.get("appVersion");
        String osVersion = body.get("osVersion");

        if (platform == null || platform.isBlank()) {
            return ApiResponse.error("platform is required");
        }
        if (pushToken == null || pushToken.isBlank()) {
            return ApiResponse.error("pushToken is required");
        }

        deviceTokenService.registerToken(tenantId, userId, platform, pushToken,
                deviceId, tokenType, appVersion, osVersion);
        return ApiResponse.ok();
    }

    /**
     * Unregister a device token (soft delete).
     * DELETE /api/mobile/device-tokens
     */
    @DeleteMapping
    @Operation(summary = "Unregister device token")
    public ApiResponse<Void> unregisterToken(@RequestBody Map<String, String> body) {
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.get().getUserId();

        String pushToken = body.get("pushToken");
        if (pushToken == null || pushToken.isBlank()) {
            return ApiResponse.error("pushToken is required");
        }

        deviceTokenService.unregisterToken(tenantId, userId, pushToken);
        return ApiResponse.ok();
    }
}
