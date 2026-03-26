package com.auraboot.framework.common.util;

import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Tenant-aware clock for business date calculations.
 * System time is always UTC (Instant). This clock converts to tenant-local
 * dates when business date boundaries matter (e.g. "today's orders").
 */
@Component
public class TenantClock {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private final TenantPreferenceService tenantPreferenceService;

    public TenantClock(TenantPreferenceService tenantPreferenceService) {
        this.tenantPreferenceService = tenantPreferenceService;
    }

    /**
     * Get the configured timezone for a tenant.
     * Falls back to Asia/Shanghai if not configured.
     */
    public ZoneId getZoneId(Long tenantId) {
        if (tenantId == null) {
            return DEFAULT_ZONE;
        }
        try {
            JsonNode node = tenantPreferenceService.getPreference(tenantId, "ui.timezone");
            if (node == null || node.isNull() || !node.isTextual()) {
                return DEFAULT_ZONE;
            }
            String tz = node.asText();
            if (tz.isBlank()) {
                return DEFAULT_ZONE;
            }
            return ZoneId.of(tz);
        } catch (Exception e) {
            return DEFAULT_ZONE;
        }
    }

    /**
     * Current business date in the tenant's timezone.
     * Use for date-boundary logic (e.g. "today's tasks", voucher dates).
     */
    public LocalDate businessDate(Long tenantId) {
        return Instant.now().atZone(getZoneId(tenantId)).toLocalDate();
    }

    /**
     * Current business datetime in the tenant's timezone.
     */
    public ZonedDateTime businessDateTime(Long tenantId) {
        return Instant.now().atZone(getZoneId(tenantId));
    }
}
