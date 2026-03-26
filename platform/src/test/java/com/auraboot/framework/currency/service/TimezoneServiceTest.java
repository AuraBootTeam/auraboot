package com.auraboot.framework.currency.service;

import com.auraboot.framework.currency.dto.TimezoneInfo;
import com.auraboot.framework.currency.service.impl.TimezoneServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for TimezoneService -- no Spring context needed,
 * since timezone operations are purely in-memory.
 */
class TimezoneServiceTest {

    private TimezoneService timezoneService;

    @BeforeEach
    void setUp() {
        timezoneService = new TimezoneServiceImpl();
    }

    @Test
    void testListTimezonesNotEmpty() {
        List<TimezoneInfo> zones = timezoneService.listTimezones();
        assertThat(zones).isNotNull();
        assertThat(zones).hasSizeGreaterThan(100);
    }

    @Test
    void testListTimezonesSortedByOffset() {
        List<TimezoneInfo> zones = timezoneService.listTimezones();
        for (int i = 1; i < zones.size(); i++) {
            assertThat(zones.get(i).getOffsetSeconds() >= zones.get(i - 1).getOffsetSeconds()
                            || zones.get(i).getId().compareTo(zones.get(i - 1).getId()) >= 0)
                    .as("Timezones should be sorted by offset then ID")
                    .isTrue();
        }
    }

    @Test
    void testGetTimezoneInfoShanghai() {
        TimezoneInfo info = timezoneService.getTimezoneInfo("Asia/Shanghai");
        assertThat(info).isNotNull();
        assertThat(info.getId()).isEqualTo("Asia/Shanghai");
        assertThat(info.getUtcOffset()).isEqualTo("UTC+08:00");
        assertThat(info.getOffsetSeconds()).isEqualTo(28800);
    }

    @Test
    void testGetTimezoneInfoUTC() {
        TimezoneInfo info = timezoneService.getTimezoneInfo("UTC");
        assertThat(info).isNotNull();
        assertThat(info.getId()).isEqualTo("UTC");
        assertThat(info.getUtcOffset()).isEqualTo("UTC+00:00");
        assertThat(info.getOffsetSeconds()).isEqualTo(0);
    }

    @Test
    void testGetTimezoneInfoNewYork() {
        TimezoneInfo info = timezoneService.getTimezoneInfo("America/New_York");
        assertThat(info).isNotNull();
        assertThat(info.getId()).isEqualTo("America/New_York");
        // Offset varies by DST, but should be -5h or -4h
        assertThat(info.getOffsetSeconds()).isIn(-18000, -14400);
    }

    @Test
    void testToLocalFromUtc() {
        Instant utc = Instant.parse("2026-03-18T08:00:00Z");
        LocalDateTime local = timezoneService.toLocal(utc, "Asia/Shanghai");
        assertThat(local).isEqualTo(LocalDateTime.of(2026, 3, 18, 16, 0, 0));
    }

    @Test
    void testToUtcFromLocal() {
        LocalDateTime local = LocalDateTime.of(2026, 3, 18, 16, 0, 0);
        Instant utc = timezoneService.toUtc(local, "Asia/Shanghai");
        assertThat(utc).isEqualTo(Instant.parse("2026-03-18T08:00:00Z"));
    }

    @Test
    void testToLocalRoundTrip() {
        Instant original = Instant.parse("2026-06-15T12:30:00Z");
        String tz = "America/Los_Angeles";
        LocalDateTime local = timezoneService.toLocal(original, tz);
        Instant roundTripped = timezoneService.toUtc(local, tz);
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void testNowReturnsCorrectTimezone() {
        ZonedDateTime zdt = timezoneService.now("Asia/Tokyo");
        assertThat(zdt.getZone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
    }

    @Test
    void testIsValidTimezone() {
        assertThat(timezoneService.isValidTimezone("UTC")).isTrue();
        assertThat(timezoneService.isValidTimezone("Asia/Shanghai")).isTrue();
        assertThat(timezoneService.isValidTimezone("America/New_York")).isTrue();
        assertThat(timezoneService.isValidTimezone("Europe/London")).isTrue();

        assertThat(timezoneService.isValidTimezone(null)).isFalse();
        assertThat(timezoneService.isValidTimezone("")).isFalse();
        assertThat(timezoneService.isValidTimezone("Invalid/Zone")).isFalse();
        assertThat(timezoneService.isValidTimezone("FooBar")).isFalse();
    }

    @Test
    void testInvalidTimezoneThrowsException() {
        assertThatThrownBy(() -> timezoneService.getTimezoneInfo("Not/A/Zone"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> timezoneService.toLocal(Instant.now(), "Invalid"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> timezoneService.toUtc(LocalDateTime.now(), "Invalid"))
                .isInstanceOf(RuntimeException.class);
    }
}
