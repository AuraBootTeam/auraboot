package com.auraboot.plugins.crm.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** Pure, deterministic policy helpers shared by lead-pool commands and the recycle scanner. */
public final class LeadPoolRules {

    private LeadPoolRules() {
    }

    public static Set<String> userIds(Object csv) {
        if (csv == null) return Collections.emptySet();
        String encoded = csv.toString().trim();
        if (encoded.startsWith("[") && encoded.endsWith("]")) {
            encoded = encoded.substring(1, encoded.length() - 1);
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Arrays.stream(encoded.split(","))
                .map(String::trim)
                .map(LeadPoolRules::stripJsonQuotes)
                .filter(value -> !value.isEmpty())
                .forEach(result::add);
        return Collections.unmodifiableSet(result);
    }

    private static String stripJsonQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
        }
        return value;
    }

    public static boolean isAdministrator(Object adminIds, String actor) {
        return actor != null && userIds(adminIds).contains(actor);
    }

    public static boolean isMember(Object memberIds, Object adminIds, String actor) {
        return actor != null && (userIds(memberIds).contains(actor) || isAdministrator(adminIds, actor));
    }

    public static boolean cooldownElapsed(Instant enteredAt, int days, Instant now) {
        return days <= 0 || enteredAt == null || !enteredAt.plus(Duration.ofDays(days)).isAfter(now);
    }

    public static Instant releaseAt(Instant enteredAt, int days) {
        return enteredAt == null || days <= 0 ? enteredAt : enteredAt.plus(Duration.ofDays(days));
    }

    public static boolean shouldRecycle(Instant basis, int days, Instant now) {
        return basis != null && days >= 0 && !basis.plus(Duration.ofDays(days)).isAfter(now);
    }
}
