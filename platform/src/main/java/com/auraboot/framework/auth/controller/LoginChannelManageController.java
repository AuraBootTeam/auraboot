package com.auraboot.framework.auth.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.ChannelUpdateRequest;
import com.auraboot.framework.auth.entity.TenantLoginChannel;
import com.auraboot.framework.auth.service.TenantLoginChannelService;
import com.auraboot.framework.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin endpoint for managing tenant login channel configuration.
 * <p>
 * Requires authentication. The tenant ID is resolved from the current user's
 * JWT token via {@link MetaContext}.
 *
 * @since 7.0.0
 */
@RestController
@RequestMapping("/api/admin/login-channels")
@RequiredArgsConstructor
public class LoginChannelManageController {

    private final TenantLoginChannelService channelService;

    /**
     * List all login channels for the current tenant (enabled and disabled).
     */
    @GetMapping
    public ApiResponse<List<TenantLoginChannel>> list() {
        Long tenantId = MetaContext.getCurrentTenantId();
        return ApiResponse.success(channelService.listChannels(tenantId));
    }

    /**
     * Batch update login channel enabled state and sort order.
     *
     * @param updates list of channel updates
     */
    @PutMapping
    public ApiResponse<Void> updateChannels(@RequestBody List<ChannelUpdateRequest> updates) {
        Long tenantId = MetaContext.getCurrentTenantId();
        channelService.updateChannels(tenantId, updates);
        return ApiResponse.success(null);
    }
}
