package com.auraboot.framework.tenant.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/system-preferences")
@RequiredArgsConstructor
@Tag(name = "System Preferences", description = "Tenant-level display preferences")
public class SystemPreferencesController {

    private static final String DATETIME_FORMAT_KEY = "ui.datetime.format";
    private static final String TIMEZONE_KEY = "ui.timezone";
    private static final String DEFAULT_DATETIME_FORMAT = "YYYY-MM-DD HH:mm:ss";
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final TenantPreferenceService tenantPreferenceService;

    @GetMapping
    @RequirePermission("system_management")
    public ApiResponse<SystemPreferencesResponse> getSystemPreferences() {
        Long tenantId = MetaContext.getCurrentTenantId();
        String datetimeFormat = textOrDefault(
            tenantPreferenceService.getPreference(tenantId, DATETIME_FORMAT_KEY),
            DEFAULT_DATETIME_FORMAT
        );
        JsonNode timezoneNode = tenantPreferenceService.getPreference(tenantId, TIMEZONE_KEY);
        boolean timezoneConfigured = hasText(timezoneNode);
        String timezone = timezoneConfigured ? timezoneNode.asText().trim() : DEFAULT_TIMEZONE;
        return ApiResponse.success(toResponse(datetimeFormat, timezone, timezoneConfigured));
    }

    @PutMapping
    @RequirePermission("system_management")
    public ApiResponse<SystemPreferencesResponse> updateSystemPreferences(
        @RequestBody SystemPreferencesRequest request
    ) {
        Long tenantId = MetaContext.getCurrentTenantId();
        String datetimeFormat = StringUtils.hasText(request.getDatetimeFormat())
            ? request.getDatetimeFormat().trim()
            : DEFAULT_DATETIME_FORMAT;
        String timezone = StringUtils.hasText(request.getTimezone())
            ? request.getTimezone().trim()
            : DEFAULT_TIMEZONE;

        tenantPreferenceService.setPreference(
            tenantId,
            DATETIME_FORMAT_KEY,
            TextNode.valueOf(datetimeFormat)
        );
        tenantPreferenceService.setPreference(tenantId, TIMEZONE_KEY, TextNode.valueOf(timezone));
        return ApiResponse.success(toResponse(datetimeFormat, timezone, true));
    }

    private static String textOrDefault(JsonNode node, String fallback) {
        if (!hasText(node)) {
            return fallback;
        }
        return node.asText().trim();
    }

    private static boolean hasText(JsonNode node) {
        return node != null && node.isTextual() && StringUtils.hasText(node.asText());
    }

    private static SystemPreferencesResponse toResponse(
        String datetimeFormat,
        String timezone,
        boolean timezoneConfigured
    ) {
        SystemPreferencesResponse response = new SystemPreferencesResponse();
        response.setDatetimeFormat(datetimeFormat);
        response.setTimezone(timezone);
        response.setTimezoneConfigured(timezoneConfigured);
        response.setTimezoneStatusText(
            timezoneConfigured
                ? "租户默认时区已设置为 " + timezone + "。"
                : "尚未配置租户默认时区，保存后将作为租户默认值。"
        );
        return response;
    }

    @Data
    public static class SystemPreferencesRequest {
        private String datetimeFormat;
        private String timezone;
    }

    @Data
    public static class SystemPreferencesResponse {
        private String datetimeFormat;
        private String timezone;
        private boolean timezoneConfigured;
        private String timezoneStatusText;
    }
}
