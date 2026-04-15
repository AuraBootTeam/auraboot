package com.auraboot.framework.im.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.im.model.ImNotificationPreference;
import com.auraboot.framework.im.service.ImNotificationPreferenceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/im/notification-preferences")
public class ImNotificationPreferenceController {

    private final ImNotificationPreferenceService preferenceService;

    public ImNotificationPreferenceController(ImNotificationPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping
    public ApiResponse<List<ImNotificationPreference>> list() {
        return ApiResponse.success(preferenceService.listByUser(
                MetaContext.getCurrentUserId(), MetaContext.getCurrentTenantId()));
    }

    @PostMapping
    public ApiResponse<ImNotificationPreference> set(@RequestBody Map<String, Object> body) {
        String modelCode = (String) body.get("modelCode");
        String operationType = (String) body.get("operationType");
        Boolean enabled = (Boolean) body.getOrDefault("enabled", true);

        ImNotificationPreference pref = preferenceService.setPreference(
                MetaContext.getCurrentUserId(), MetaContext.getCurrentTenantId(),
                modelCode, operationType, enabled);
        return ApiResponse.success(pref);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        preferenceService.deletePreference(id,
                MetaContext.getCurrentUserId(), MetaContext.getCurrentTenantId());
        return ApiResponse.success(null);
    }
}
