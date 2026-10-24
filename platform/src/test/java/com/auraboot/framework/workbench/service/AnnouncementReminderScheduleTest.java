package com.auraboot.framework.workbench.service;

import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;

class AnnouncementReminderScheduleTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static Instant instant(String value) { return Instant.parse(value); }
    private static AnnouncementReminderSchedule.Policy quiet(int hour, int start, int end) {
        return new AnnouncementReminderSchedule.Policy(UTC, LocalTime.of(hour,0), LocalTime.of(start,0), LocalTime.of(end,0));
    }
    @Test void firstSlotIncludesExactlyTwentyFourHoursAndRejectsOneSecondEarlier() {
        var policy = AnnouncementReminderSchedule.Policy.defaults(UTC);
        Instant now = instant("2026-10-25T09:00:00Z");
        assertThat(AnnouncementReminderSchedule.due(policy,now.minusSeconds(86400),now,null,null)).contains(now);
        assertThat(AnnouncementReminderSchedule.due(policy,now.minusSeconds(86399),now,null,null)).isEmpty();
    }
    @Test void laterSameDayScanUsesTodaysSlotRatherThanAccumulatingMissedDates() {
        Instant now=instant("2026-10-25T18:00:00Z");
        assertThat(AnnouncementReminderSchedule.due(AnnouncementReminderSchedule.Policy.defaults(UTC),
            now.minus(Duration.ofDays(10)),now,null,null)).contains(instant("2026-10-25T09:00:00Z"));
    }
    @Test void neverRunsBeforeTodaysSlot() {
        Instant now=instant("2026-10-25T08:59:59Z");
        assertThat(AnnouncementReminderSchedule.due(AnnouncementReminderSchedule.Policy.defaults(UTC),
            now.minus(Duration.ofDays(2)),now,null,null)).isEmpty();
    }
    @Test void deadlineAndExpiryEqualityStopAutomaticRemindersEvenAfterAnEarlierSlot() {
        Instant now=instant("2026-10-25T10:00:00Z"),published=now.minus(Duration.ofDays(2));
        var policy=AnnouncementReminderSchedule.Policy.defaults(UTC);
        assertThat(AnnouncementReminderSchedule.due(policy,published,now,now,null)).isEmpty();
        assertThat(AnnouncementReminderSchedule.due(policy,published,now,null,now)).isEmpty();
        assertThat(AnnouncementReminderSchedule.due(policy,published,now,now.plusSeconds(1),now.plusSeconds(1))).isPresent();
    }
    @Test void quietHoursMoveToSameDayEndAndEndBoundaryIsAllowed() {
        Instant now=instant("2026-10-25T10:00:00Z"),published=now.minus(Duration.ofDays(2));
        assertThat(AnnouncementReminderSchedule.due(quiet(9,8,10),published,now,null,null)).contains(now);
        assertThat(AnnouncementReminderSchedule.due(quiet(10,8,10),published,now,null,null)).contains(now);
        assertThat(AnnouncementReminderSchedule.due(quiet(9,8,10),published,now.minusSeconds(1),null,null)).isEmpty();
    }
    @Test void overnightQuietHoursAllowMorningEndButNeverCarryAnEveningSlotIntoTomorrow() {
        Instant now=instant("2026-10-25T23:00:00Z"),published=now.minus(Duration.ofDays(2));
        assertThat(AnnouncementReminderSchedule.due(quiet(23,22,8),published,now,null,null)).isEmpty();
        Instant morning=instant("2026-10-25T08:00:00Z");
        assertThat(AnnouncementReminderSchedule.due(quiet(6,22,8),published,morning,null,null)).contains(morning);
    }
    @Test void daylightSavingGapMovesToFirstValidInstantOfTheDay() {
        var policy=new AnnouncementReminderSchedule.Policy(ZoneId.of("America/New_York"),LocalTime.of(2,30),null,null);
        Instant now=instant("2026-03-08T07:00:00Z");
        assertThat(AnnouncementReminderSchedule.due(policy,now.minus(Duration.ofDays(2)),now,null,null)).contains(now);
    }
    @Test void daylightSavingFoldAlwaysUsesEarlierOccurrenceForTheDailyUniqueKey() {
        var policy=new AnnouncementReminderSchedule.Policy(ZoneId.of("America/New_York"),LocalTime.of(1,30),null,null);
        Instant now=instant("2026-11-01T06:30:00Z");
        assertThat(AnnouncementReminderSchedule.due(policy,now.minus(Duration.ofDays(2)),now,null,null))
            .contains(instant("2026-11-01T05:30:00Z"));
    }
    @Test void frozenTenantZoneDeterminesCalendarDay() {
        var policy=AnnouncementReminderSchedule.Policy.defaults(ZoneId.of("Asia/Shanghai"));
        Instant now=instant("2026-10-25T01:00:00Z");
        assertThat(AnnouncementReminderSchedule.due(policy,now.minus(Duration.ofDays(2)),now,null,null)).contains(now);
    }
    @Test void partialOrAllDayQuietWindowsCannotCreateAnUnrunnablePolicy() {
        assertThatThrownBy(()->new AnnouncementReminderSchedule.Policy(UTC,LocalTime.NOON,null,LocalTime.NOON))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new AnnouncementReminderSchedule.Policy(UTC,LocalTime.NOON,LocalTime.NOON,LocalTime.NOON))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
