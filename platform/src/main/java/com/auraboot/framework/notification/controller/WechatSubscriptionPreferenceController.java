package com.auraboot.framework.notification.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.notification.dto.WechatSubscriptionObservationRequest;
import com.auraboot.framework.notification.wechat.WechatSubscriptionPreferenceService;
import com.auraboot.framework.permission.annotation.AuthenticatedAccess;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** Current-account observations, separate from the provider-authenticated public callback. */
@RestController
@RequestMapping("/api/notifications/wechat-subscription")
@AuthenticatedAccess("current active member's configured mini-program subscription choices; ownership and identity resolved from MetaContext")
public class WechatSubscriptionPreferenceController {
    private final WechatSubscriptionPreferenceService service;
    public WechatSubscriptionPreferenceController(WechatSubscriptionPreferenceService service) { this.service = service; }

    @GetMapping("/templates")
    public ApiResponse<WechatSubscriptionPreferenceService.Catalog> templates() {
        return ApiResponse.success(service.catalog());
    }

    @PostMapping("/observations")
    public ApiResponse<WechatSubscriptionPreferenceService.Recorded> observe(@Valid @RequestBody WechatSubscriptionObservationRequest request) {
        return ApiResponse.success(service.observe(request));
    }
}
