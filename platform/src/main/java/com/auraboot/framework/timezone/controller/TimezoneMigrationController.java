package com.auraboot.framework.timezone.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.auraboot.framework.timezone.service.TimezoneMigrationService;
import com.auraboot.framework.timezone.service.TimezoneMigrationService.MigrationAssessment;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.DateTimeException;
import java.util.Map;

/**
 * REST controller for timezone migration guidance.
 *
 * <p>Endpoints help tenant administrators understand the impact of changing their
 * display timezone and safely apply the new setting.</p>
 */
@RestController
@RequestMapping("/api/admin/timezone")
@RequiredArgsConstructor
public class TimezoneMigrationController {

    private static final String TIMEZONE_PREF_KEY = "ui.timezone";
    private static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

    private final TimezoneMigrationService timezoneMigrationService;
    private final TenantPreferenceService tenantPreferenceService;

    /**
     * Assess the impact of a hypothetical timezone change without applying it.
     *
     * <p>GET /api/admin/timezone/migration-check?fromTimezone=Asia/Shanghai&amp;toTimezone=Asia/Tokyo</p>
     */
    @GetMapping("/migration-check")
    public ApiResponse<?> migrationCheck(
            @RequestParam String fromTimezone,
            @RequestParam String toTimezone) {
        try {
            MigrationAssessment assessment = timezoneMigrationService.assess(fromTimezone, toTimezone);
            return ApiResponse.success(assessment);
        } catch (DateTimeException e) {
            return ApiResponse.error("Invalid timezone identifier: " + e.getMessage());
        }
    }

    /**
     * Update the current tenant's timezone after generating an impact assessment.
     *
     * <p>PUT /api/admin/timezone/tenant-timezone</p>
     * <p>Body: {@code { "timezone": "Asia/Tokyo" }}</p>
     *
     * <p>The endpoint:</p>
     * <ol>
     *   <li>Validates the IANA timezone identifier.</li>
     *   <li>Retrieves the current tenant timezone from preferences (falls back to Asia/Shanghai).</li>
     *   <li>Produces a migration assessment report.</li>
     *   <li>Persists the new timezone to tenant preferences.</li>
     *   <li>Returns both the assessment and a confirmation flag.</li>
     * </ol>
     */
    @PutMapping("/tenant-timezone")
    public ApiResponse<?> updateTenantTimezone(@RequestBody Map<String, String> body) {
        String newTimezone = body.get("timezone");
        if (newTimezone == null || newTimezone.isBlank()) {
            return ApiResponse.error("timezone field is required");
        }

        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            String currentTimezone = getCurrentTimezone(tenantId);

            // Produce assessment before applying change
            MigrationAssessment assessment = timezoneMigrationService.assess(currentTimezone, newTimezone);

            // Persist new timezone
            timezoneMigrationService.updateTimezone(tenantId, newTimezone);

            Map<String, Object> result = Map.of(
                    "assessment", assessment,
                    "updated", true
            );
            return ApiResponse.success(result);

        } catch (DateTimeException e) {
            return ApiResponse.error("Invalid timezone identifier: " + e.getMessage());
        }
    }

    /**
     * Read the tenant's currently configured timezone preference.
     * Falls back to {@link #DEFAULT_TIMEZONE} when no preference has been set,
     * consistent with TenantClock fallback behavior.
     */
    private String getCurrentTimezone(Long tenantId) {
        if (tenantId == null) {
            return DEFAULT_TIMEZONE;
        }
        try {
            JsonNode node = tenantPreferenceService.getPreference(tenantId, TIMEZONE_PREF_KEY);
            if (node != null && node.isTextual()) {
                String tz = node.asText();
                if (!tz.isBlank()) {
                    return tz;
                }
            }
        } catch (Exception ignored) {
            // fall through to default
        }
        return DEFAULT_TIMEZONE;
    }
}
