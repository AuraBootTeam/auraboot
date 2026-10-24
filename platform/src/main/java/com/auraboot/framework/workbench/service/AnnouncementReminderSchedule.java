package com.auraboot.framework.workbench.service;

import java.time.*;
import java.time.zone.ZoneRules;
import java.util.Objects;
import java.util.Optional;

/** Calendar-day eligibility for a version's frozen reminder policy. No missed-day catch-up. */
public final class AnnouncementReminderSchedule {
    private AnnouncementReminderSchedule() {}

    public record Policy(ZoneId zone, LocalTime at, LocalTime quietStart, LocalTime quietEnd) {
        public Policy {
            Objects.requireNonNull(zone, "zone");
            Objects.requireNonNull(at, "at");
            if ((quietStart == null) != (quietEnd == null)
                    || (quietStart != null && quietStart.equals(quietEnd))) {
                throw new IllegalArgumentException("Quiet hours must define a nonempty interval");
            }
        }
        public static Policy defaults(ZoneId zone) {
            return new Policy(zone, LocalTime.of(9, 0), null, null);
        }
    }

    /** Only the current tenant date may be scanned; deadlines are exclusive. */
    public static Optional<Instant> due(Policy policy, Instant publishedAt, Instant now,
            Instant acknowledgementDeadline, Instant expiresAt) {
        Objects.requireNonNull(publishedAt, "publishedAt");
        Objects.requireNonNull(now, "now");
        LocalDate day = now.atZone(policy.zone()).toLocalDate();
        ZonedDateTime scheduled = resolve(day.atTime(policy.at()), policy.zone());
        if (inQuietHours(scheduled.toLocalTime(), policy)) {
            LocalDate endDay = day;
            if (policy.quietStart().isAfter(policy.quietEnd())
                    && !scheduled.toLocalTime().isBefore(policy.quietStart())) endDay = day.plusDays(1);
            scheduled = resolve(endDay.atTime(policy.quietEnd()), policy.zone());
        }
        Instant candidate = scheduled.toInstant();
        if (!scheduled.toLocalDate().equals(day)
                || inQuietHours(scheduled.toLocalTime(), policy)
                || candidate.isBefore(publishedAt.plus(Duration.ofHours(24)))
                || now.isBefore(candidate)
                || atOrAfter(now, acknowledgementDeadline) || atOrAfter(now, expiresAt)
                || atOrAfter(candidate, acknowledgementDeadline) || atOrAfter(candidate, expiresAt)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private static boolean atOrAfter(Instant value, Instant limit) {
        return limit != null && !value.isBefore(limit);
    }
    private static boolean inQuietHours(LocalTime value, Policy policy) {
        if (policy.quietStart() == null) return false;
        if (policy.quietStart().isBefore(policy.quietEnd())) {
            return !value.isBefore(policy.quietStart()) && value.isBefore(policy.quietEnd());
        }
        return !value.isBefore(policy.quietStart()) || value.isBefore(policy.quietEnd());
    }
    private static ZonedDateTime resolve(LocalDateTime local, ZoneId zone) {
        ZoneRules rules = zone.getRules();
        var offsets = rules.getValidOffsets(local);
        if (offsets.isEmpty()) {
            var transition = rules.getTransition(local);
            return ZonedDateTime.ofStrict(transition.getDateTimeAfter(), transition.getOffsetAfter(), zone);
        }
        return ZonedDateTime.ofStrict(local, offsets.getFirst(), zone);
    }
}
