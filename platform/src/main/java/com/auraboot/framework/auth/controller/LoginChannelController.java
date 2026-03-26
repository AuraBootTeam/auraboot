package com.auraboot.framework.auth.controller;

import com.auraboot.framework.auth.service.TenantLoginChannelService;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Public endpoint for querying available login channels.
 * <p>
 * This endpoint is unauthenticated (on the security whitelist) so the login page
 * can discover which authentication methods to render before the user logs in.
 *
 * @since 7.0.0
 */
@RestController
@RequestMapping("/api/auth/login")
@RequiredArgsConstructor
public class LoginChannelController {

    private final TenantLoginChannelService channelService;

    /**
     * Get the list of enabled login channel codes for a tenant.
     * <p>
     * If tenantId is not provided, returns the default channels (EMAIL_PASSWORD).
     *
     * @param tenantId optional tenant ID
     * @return sorted list of enabled channel codes
     */
    @GetMapping("/channels")
    public ApiResponse<List<String>> getAvailableChannels(
            @RequestParam(required = false) Long tenantId) {
        return ApiResponse.success(channelService.getEnabledChannels(tenantId));
    }
}
