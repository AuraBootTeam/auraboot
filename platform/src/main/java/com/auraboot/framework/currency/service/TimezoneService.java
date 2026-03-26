package com.auraboot.framework.currency.service;

import com.auraboot.framework.currency.dto.TimezoneInfo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Service for timezone operations: listing, conversion, and tenant/user timezone resolution.
 */
public interface TimezoneService {

    /**
     * List all supported IANA timezones with offset information.
     */
    List<TimezoneInfo> listTimezones();

    /**
     * Convert a UTC instant to LocalDateTime in the specified timezone.
     */
    LocalDateTime toLocal(Instant utcInstant, String timezoneId);

    /**
     * Convert a LocalDateTime in the specified timezone to UTC instant.
     */
    Instant toUtc(LocalDateTime localDateTime, String timezoneId);

    /**
     * Get the current time in a specific timezone.
     */
    ZonedDateTime now(String timezoneId);

    /**
     * Validate whether a timezone ID is a valid IANA timezone.
     */
    boolean isValidTimezone(String timezoneId);

    /**
     * Get TimezoneInfo for a specific timezone ID.
     */
    TimezoneInfo getTimezoneInfo(String timezoneId);
}
