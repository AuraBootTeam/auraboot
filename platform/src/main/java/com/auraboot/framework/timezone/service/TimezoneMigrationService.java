package com.auraboot.framework.timezone.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.tenant.service.TenantPreferenceService;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Service for assessing the impact of a tenant timezone change.
 *
 * <p>Core principle: all datetime values are stored as TIMESTAMPTZ (UTC). Changing a
 * tenant's display timezone does NOT require any data migration. This service exists to
 * communicate that fact clearly and to highlight the one edge case where care is needed:
 * DATE-only fields, which store a calendar date without a time component, may map to a
 * different UTC day depending on the local timezone at the time of entry.</p>
 */
@Service
@RequiredArgsConstructor
public class TimezoneMigrationService {

    private static final String TIMEZONE_PREFERENCE_KEY = "ui.timezone";

    private final TenantPreferenceService tenantPreferenceService;

    /**
     * Assess the impact of changing a tenant's timezone from {@code fromTz} to {@code toTz}.
     *
     * @param fromTz IANA timezone identifier (e.g. "Asia/Shanghai")
     * @param toTz   IANA timezone identifier (e.g. "Asia/Tokyo")
     * @return an immutable assessment record
     * @throws DateTimeException if either timezone identifier is invalid
     */
    public MigrationAssessment assess(String fromTz, String toTz) {
        ZoneId fromZone = parseZoneId(fromTz);  // throws DateTimeException on invalid id
        ZoneId toZone = parseZoneId(toTz);

        Instant now = Instant.now();
        ZoneOffset fromOffset = fromZone.getRules().getStandardOffset(now);
        ZoneOffset toOffset = toZone.getRules().getStandardOffset(now);
        int diffMinutes = (toOffset.getTotalSeconds() - fromOffset.getTotalSeconds()) / 60;

        String impact;
        String description;
        String recommendation;

        if (diffMinutes == 0) {
            impact = "none";
            description = "The source and target timezones have the same UTC offset. "
                    + "No display changes will occur for any field type.";
            recommendation = "No action required.";
        } else if (Math.abs(diffMinutes) < 60) {
            // Sub-hour offset difference — typically DST transitions
            impact = "low";
            description = "All TIMESTAMPTZ values are stored in UTC and will display correctly in the new timezone. "
                    + "DATE-only fields near midnight boundaries may show a different calendar date "
                    + "if the entry was created within 1 hour of midnight in the original timezone.";
            recommendation = "Review DATE fields (e.g. due dates, voucher dates) for records "
                    + "created close to midnight if precision is critical.";
        } else {
            impact = "medium";
            description = "All TIMESTAMPTZ values are stored in UTC and will display correctly in the new timezone. "
                    + "DATE-only fields near midnight boundaries may show a different calendar date "
                    + "because the offset difference exceeds one hour.";
            recommendation = "Review DATE fields (e.g. due dates, voucher dates) created near midnight "
                    + "in the original timezone. TIMESTAMPTZ fields require no action.";
        }

        return new MigrationAssessment(
                fromTz,
                toTz,
                diffMinutes,
                impact,
                description,
                recommendation,
                new String[]{"date"}
        );
    }

    /**
     * Update the tenant's timezone preference after producing an assessment.
     *
     * @param tenantId the tenant to update
     * @param timezone IANA timezone identifier
     */
    public void updateTimezone(Long tenantId, String timezone) {
        // Validate first — ZoneId.of() throws DateTimeException on invalid id
        parseZoneId(timezone);
        tenantPreferenceService.setPreference(
                tenantId,
                TIMEZONE_PREFERENCE_KEY,
                JsonNodeFactory.instance.textNode(timezone)
        );
    }

    private ZoneId parseZoneId(String timezoneId) {
        try {
            return ZoneId.of(timezoneId);
        } catch (DateTimeException ex) {
            if (timezoneId != null) {
                String normalized = timezoneId.trim();
                if ("gmt".equalsIgnoreCase(normalized) || "utc".equalsIgnoreCase(normalized)) {
                    return ZoneId.of(normalized.toUpperCase(Locale.ROOT));
                }
            }
            throw ex;
        }
    }

    /**
     * Immutable record describing the impact of a timezone change.
     *
     * @param fromTimezone      source IANA timezone
     * @param toTimezone        target IANA timezone
     * @param offsetDiffMinutes offset difference in minutes (positive = eastward shift)
     * @param impact            "none" | "low" | "medium"
     * @param description       human-readable impact description
     * @param recommendation    suggested follow-up actions
     * @param affectedFieldTypes field types that may require attention
     */
    public record MigrationAssessment(
            String fromTimezone,
            String toTimezone,
            int offsetDiffMinutes,
            String impact,
            String description,
            String recommendation,
            String[] affectedFieldTypes
    ) {}
}
