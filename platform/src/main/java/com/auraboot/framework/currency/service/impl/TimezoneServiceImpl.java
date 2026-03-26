package com.auraboot.framework.currency.service.impl;

import com.auraboot.framework.currency.dto.TimezoneInfo;
import com.auraboot.framework.currency.service.TimezoneService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.TextStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Implementation of TimezoneService using Java's built-in ZoneId support.
 * All storage uses UTC; this service handles display-layer conversions.
 */
@Slf4j
@Service
public class TimezoneServiceImpl implements TimezoneService {

    @Override
    public List<TimezoneInfo> listTimezones() {
        Instant now = Instant.now();
        return ZoneId.getAvailableZoneIds().stream()
                .filter(id -> !id.startsWith("SystemV/") && !id.startsWith("Etc/"))
                .map(id -> buildTimezoneInfo(id, now))
                .sorted(Comparator.comparingInt(TimezoneInfo::getOffsetSeconds)
                        .thenComparing(TimezoneInfo::getId))
                .collect(Collectors.toList());
    }

    @Override
    public LocalDateTime toLocal(Instant utcInstant, String timezoneId) {
        validateTimezoneOrThrow(timezoneId);
        return utcInstant.atZone(ZoneId.of(timezoneId)).toLocalDateTime();
    }

    @Override
    public Instant toUtc(LocalDateTime localDateTime, String timezoneId) {
        validateTimezoneOrThrow(timezoneId);
        return localDateTime.atZone(ZoneId.of(timezoneId)).toInstant();
    }

    @Override
    public ZonedDateTime now(String timezoneId) {
        validateTimezoneOrThrow(timezoneId);
        return ZonedDateTime.now(ZoneId.of(timezoneId));
    }

    @Override
    public boolean isValidTimezone(String timezoneId) {
        if (timezoneId == null || timezoneId.isBlank()) {
            return false;
        }
        try {
            ZoneId.of(timezoneId);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    @Override
    public TimezoneInfo getTimezoneInfo(String timezoneId) {
        validateTimezoneOrThrow(timezoneId);
        return buildTimezoneInfo(timezoneId, Instant.now());
    }

    private TimezoneInfo buildTimezoneInfo(String zoneIdStr, Instant referenceInstant) {
        ZoneId zoneId = ZoneId.of(zoneIdStr);
        ZoneOffset offset = zoneId.getRules().getOffset(referenceInstant);
        int totalSeconds = offset.getTotalSeconds();

        String displayName = zoneId.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        String utcOffset = formatOffset(totalSeconds);

        return new TimezoneInfo(zoneIdStr, displayName, utcOffset, totalSeconds);
    }

    private String formatOffset(int totalSeconds) {
        int hours = totalSeconds / 3600;
        int minutes = Math.abs((totalSeconds % 3600) / 60);
        return String.format("UTC%+03d:%02d", hours, minutes);
    }

    private void validateTimezoneOrThrow(String timezoneId) {
        if (!isValidTimezone(timezoneId)) {
            throw new RuntimeException("Invalid timezone ID: " + timezoneId);
        }
    }
}
