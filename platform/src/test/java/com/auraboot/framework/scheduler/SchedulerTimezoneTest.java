package com.auraboot.framework.scheduler;

import com.auraboot.framework.scheduler.entity.ScheduledTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.time.*;
import java.util.Date;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests verifying that CRON tasks are fired in the correct tenant timezone.
 *
 * <p>The core fix is in {@link com.auraboot.framework.scheduler.service.impl.DatabaseSchedulerEngine}
 * which now passes an effective {@link TimeZone} to {@link CronTrigger}.
 * These tests validate the Spring CronTrigger timezone semantics directly — no Spring context needed.
 *
 * @since 5.1.0
 */
@DisplayName("Scheduler Timezone Awareness Tests")
class SchedulerTimezoneTest {

    private static final String CRON_8AM_DAILY = "0 0 8 * * *"; // Every day at 08:00

    /**
     * A CronTrigger using UTC timezone should fire at 08:00 UTC.
     */
    @Test
    @DisplayName("CRON 0 0 8 * * * fires at 08:00 UTC when timezone=UTC")
    void utcCronFiresAtEightAmUtc() {
        TimeZone utc = TimeZone.getTimeZone(ZoneId.of("UTC"));
        CronTrigger trigger = new CronTrigger(CRON_8AM_DAILY, utc);

        // Simulate last-fired at 2026-01-01 07:59:59 UTC
        Instant lastFired = ZonedDateTime.of(2026, 1, 1, 7, 59, 59, 0, ZoneOffset.UTC).toInstant();
        Date nextFire = trigger.nextExecutionTime(new SimpleTriggerContext(
                Date.from(lastFired), Date.from(lastFired), Date.from(lastFired)));

        assertThat(nextFire).isNotNull();
        ZonedDateTime nextInUtc = nextFire.toInstant().atZone(ZoneOffset.UTC);
        assertThat(nextInUtc.getHour()).isEqualTo(8);
        assertThat(nextInUtc.getMinute()).isEqualTo(0);
        assertThat(nextInUtc.getSecond()).isEqualTo(0);
    }

    /**
     * A CronTrigger using Asia/Shanghai (UTC+8) should fire at 08:00 CST = 00:00 UTC.
     * This is the key bug-fix scenario: tenants in UTC+8 configure "08:00 daily"
     * but without timezone the task would fire at 08:00 UTC (16:00 CST).
     */
    @Test
    @DisplayName("CRON 0 0 8 * * * fires at 00:00 UTC (08:00 CST) when timezone=Asia/Shanghai")
    void shanghaiCronFiresAtMidnightUtc() {
        TimeZone shanghai = TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai"));
        CronTrigger trigger = new CronTrigger(CRON_8AM_DAILY, shanghai);

        // Simulate last-fired at 2026-01-01 07:59:59 CST = 23:59:59 UTC (previous day)
        Instant lastFiredCst = ZonedDateTime.of(2026, 1, 1, 7, 59, 59, 0,
                ZoneId.of("Asia/Shanghai")).toInstant();
        Date nextFire = trigger.nextExecutionTime(new SimpleTriggerContext(
                Date.from(lastFiredCst), Date.from(lastFiredCst), Date.from(lastFiredCst)));

        assertThat(nextFire).isNotNull();
        // Next fire should be 2026-01-01 08:00:00 CST = 2026-01-01 00:00:00 UTC
        ZonedDateTime nextInCst = nextFire.toInstant().atZone(ZoneId.of("Asia/Shanghai"));
        assertThat(nextInCst.getHour()).isEqualTo(8);
        assertThat(nextInCst.getMinute()).isEqualTo(0);

        // The same instant in UTC should be midnight (00:00)
        ZonedDateTime nextInUtc = nextFire.toInstant().atZone(ZoneOffset.UTC);
        assertThat(nextInUtc.getHour()).isEqualTo(0);
        assertThat(nextInUtc.getMinute()).isEqualTo(0);
    }

    /**
     * The difference in UTC fire times between UTC and Asia/Shanghai (UTC+8) timezones
     * for the same cron expression must be exactly 8 hours.
     *
     * <p>Scenario: both triggers are evaluated right after firing at their respective 08:00.
     * <ul>
     *   <li>UTC trigger: last fired at 2026-01-01 08:00 UTC → next fires at 2026-01-02 08:00 UTC</li>
     *   <li>Shanghai trigger: last fired at 2026-01-01 08:00 CST (= 00:00 UTC) → next fires at 2026-01-02 08:00 CST (= 2026-01-02 00:00 UTC)</li>
     * </ul>
     * Delta: 08:00 UTC - 00:00 UTC = +8 hours.
     */
    @Test
    @DisplayName("UTC and Asia/Shanghai timezones produce a nextFireTime delta of exactly 8 hours")
    void timezoneDeltaIsEightHours() {
        TimeZone utc = TimeZone.getTimeZone(ZoneId.of("UTC"));
        TimeZone shanghai = TimeZone.getTimeZone(ZoneId.of("Asia/Shanghai"));

        CronTrigger utcTrigger = new CronTrigger(CRON_8AM_DAILY, utc);
        CronTrigger shanghaiTrigger = new CronTrigger(CRON_8AM_DAILY, shanghai);

        // UTC baseline: just after UTC 08:00 fire on 2026-01-01
        Instant utcBaseline = ZonedDateTime.of(2026, 1, 1, 8, 0, 1, 0, ZoneOffset.UTC).toInstant();
        SimpleTriggerContext utcCtx = new SimpleTriggerContext(
                Date.from(utcBaseline), Date.from(utcBaseline), Date.from(utcBaseline));

        // Shanghai baseline: just after CST 08:00 fire on 2026-01-01 (= 00:00:01 UTC)
        Instant shanghaiBaseline = ZonedDateTime.of(2026, 1, 1, 8, 0, 1, 0,
                ZoneId.of("Asia/Shanghai")).toInstant();
        SimpleTriggerContext shanghaiCtx = new SimpleTriggerContext(
                Date.from(shanghaiBaseline), Date.from(shanghaiBaseline), Date.from(shanghaiBaseline));

        Date utcNextFire = utcTrigger.nextExecutionTime(utcCtx);
        Date shanghaiNextFire = shanghaiTrigger.nextExecutionTime(shanghaiCtx);

        assertThat(utcNextFire).isNotNull();
        assertThat(shanghaiNextFire).isNotNull();

        long deltaMillis = utcNextFire.getTime() - shanghaiNextFire.getTime();
        long deltaHours = deltaMillis / (1000 * 60 * 60);

        // UTC next fires 2026-01-02 08:00 UTC; Shanghai next fires 2026-01-02 00:00 UTC (08:00 CST)
        // UTC is 8 hours AFTER Shanghai in UTC terms
        assertThat(deltaHours).isEqualTo(8L);
    }

    /**
     * Tests the task-level timezone field: a ScheduledTask with an explicit timezone
     * should use that timezone regardless of the tenant setting.
     */
    @Test
    @DisplayName("Task-level timezone field 'America/New_York' is correctly parsed to TimeZone")
    void taskLevelTimezoneFieldParsesCorrectly() {
        ScheduledTask task = new ScheduledTask();
        task.setTimezone("America/New_York");
        task.setCronExpression(CRON_8AM_DAILY);

        // Simulate the resolveTimeZone logic inline (method is private in the engine)
        ZoneId zoneId = ZoneId.of(task.getTimezone());
        TimeZone resolved = TimeZone.getTimeZone(zoneId);

        assertThat(resolved.getID()).isEqualTo("America/New_York");

        // Verify the cron fires at 08:00 ET = 13:00 UTC (EST, UTC-5)
        CronTrigger trigger = new CronTrigger(CRON_8AM_DAILY, resolved);
        Instant baseline = ZonedDateTime.of(2026, 1, 2, 7, 0, 0, 0, ZoneOffset.UTC).toInstant();
        Date nextFire = trigger.nextExecutionTime(new SimpleTriggerContext(
                Date.from(baseline), Date.from(baseline), Date.from(baseline)));

        assertThat(nextFire).isNotNull();
        ZonedDateTime nextInEt = nextFire.toInstant().atZone(ZoneId.of("America/New_York"));
        assertThat(nextInEt.getHour()).isEqualTo(8);
    }

    /**
     * Ensures a null/missing timezone falls back gracefully to UTC without throwing.
     */
    @Test
    @DisplayName("Null task timezone falls back to UTC without error")
    void nullTimezoneDefaultsToUtc() {
        ScheduledTask task = new ScheduledTask();
        task.setTimezone(null); // no explicit timezone
        task.setTenantId(null); // no tenant either

        // Simulate the resolveTimeZone fallback logic
        TimeZone resolved;
        if (task.getTimezone() != null && !task.getTimezone().isBlank()) {
            resolved = TimeZone.getTimeZone(ZoneId.of(task.getTimezone()));
        } else {
            resolved = TimeZone.getTimeZone(ZoneId.of("UTC"));
        }

        assertThat(resolved.getID()).isEqualTo("UTC");

        // CronTrigger should work fine with UTC
        CronTrigger trigger = new CronTrigger(CRON_8AM_DAILY, resolved);
        Instant baseline = ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant();
        Date nextFire = trigger.nextExecutionTime(new SimpleTriggerContext(
                Date.from(baseline), Date.from(baseline), Date.from(baseline)));
        assertThat(nextFire).isNotNull();
    }
}
