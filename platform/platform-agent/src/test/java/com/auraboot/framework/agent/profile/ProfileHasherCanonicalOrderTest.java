package com.auraboot.framework.agent.profile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Explicit canonical-order invariant tests for {@link ProfileHasher}.
 *
 * <p>The deriver relies on {@code hashProfile(projected)} being stable
 * across JVM runs, Map implementation choice, and insertion order so
 * that {@code Outcome.SKIPPED_NO_CHANGE} fires on re-derivation with
 * unchanged content. These tests prove that invariant directly, rather
 * than relying on the integration test accidentally seeing the same
 * {@code HashMap} iteration order twice in a row.
 *
 * <p>Invariants verified:
 * <ol>
 *   <li>Top-level key order differences collapse (already covered by
 *       {@code ProfileHasherTest.sameContentStableHash}; re-asserted with
 *       {@code HashMap} to rule out LinkedHashMap coincidence).</li>
 *   <li>Deep-nested key order differences collapse — profile shape has
 *       {@code preferences.notifications.channels} and similar.</li>
 *   <li>List element order IS semantic — {@code habits.recurring_actions[]}
 *       reordering must change the hash, otherwise we would drop a real
 *       behavioural signal.</li>
 *   <li>{@code Map} implementation choice ({@code HashMap} /
 *       {@code LinkedHashMap} / {@code TreeMap}) has no effect.</li>
 * </ol>
 */
@DisplayName("ProfileHasher canonical JSON ordering invariants")
class ProfileHasherCanonicalOrderTest {

    @Test
    @DisplayName("top-level key order invariance survives HashMap iteration")
    void topLevelKeyOrderInvariantAcrossMapTypes() {
        Map<String, Object> linked = new LinkedHashMap<>();
        linked.put("persona", Map.of("text", "x"));
        linked.put("language", "zh-CN");
        linked.put("tone", "warm");

        // HashMap iteration order is JVM-defined, often differs from insertion.
        Map<String, Object> hash = new HashMap<>();
        hash.put("tone", "warm");
        hash.put("language", "zh-CN");
        hash.put("persona", Map.of("text", "x"));

        Map<String, Object> tree = new TreeMap<>();
        tree.put("language", "zh-CN");
        tree.put("persona", Map.of("text", "x"));
        tree.put("tone", "warm");

        String h1 = ProfileHasher.hashProfile(linked);
        String h2 = ProfileHasher.hashProfile(hash);
        String h3 = ProfileHasher.hashProfile(tree);

        assertThat(h1).isNotNull().hasSize(64);
        assertThat(h2).isEqualTo(h1);
        assertThat(h3).isEqualTo(h1);
    }

    @Test
    @DisplayName("deep-nested key order invariance (3 levels, profile-shaped)")
    void deepNestedKeyOrderInvariant() {
        // Shape mirrors a real derived profile:
        //   preferences.notifications.channels  (nested map under map under map)
        //   persona.confidence / persona.text   (sibling order)
        Map<String, Object> channelsA = new LinkedHashMap<>();
        channelsA.put("email", true);
        channelsA.put("push", false);
        channelsA.put("sms", true);

        Map<String, Object> notificationsA = new LinkedHashMap<>();
        notificationsA.put("channels", channelsA);
        notificationsA.put("quiet_hours", "22:00-07:00");

        Map<String, Object> preferencesA = new LinkedHashMap<>();
        preferencesA.put("notifications", notificationsA);
        preferencesA.put("theme", "dark");

        Map<String, Object> a = new LinkedHashMap<>();
        a.put("persona", new LinkedHashMap<>(Map.of("confidence", 0.8, "text", "focused")));
        a.put("preferences", preferencesA);

        // Same content, all nested maps reordered, using HashMap at every level.
        Map<String, Object> channelsB = new HashMap<>();
        channelsB.put("sms", true);
        channelsB.put("email", true);
        channelsB.put("push", false);

        Map<String, Object> notificationsB = new HashMap<>();
        notificationsB.put("quiet_hours", "22:00-07:00");
        notificationsB.put("channels", channelsB);

        Map<String, Object> preferencesB = new HashMap<>();
        preferencesB.put("theme", "dark");
        preferencesB.put("notifications", notificationsB);

        Map<String, Object> personaB = new HashMap<>();
        personaB.put("text", "focused");
        personaB.put("confidence", 0.8);

        Map<String, Object> b = new HashMap<>();
        b.put("preferences", preferencesB);
        b.put("persona", personaB);

        assertThat(ProfileHasher.hashProfile(a)).isEqualTo(ProfileHasher.hashProfile(b));
    }

    @Test
    @DisplayName("list element order IS semantic (habits.recurring_actions[] reorder → different hash)")
    void listElementOrderIsSemantic() {
        List<String> actionsOrderA = List.of("morning_standup", "evening_review", "weekly_plan");
        List<String> actionsOrderB = List.of("weekly_plan", "morning_standup", "evening_review");

        Map<String, Object> a = Map.of("habits", Map.of("recurring_actions", actionsOrderA));
        Map<String, Object> b = Map.of("habits", Map.of("recurring_actions", actionsOrderB));

        assertThat(ProfileHasher.hashProfile(a))
                .isNotEqualTo(ProfileHasher.hashProfile(b));
    }

    @Test
    @DisplayName("maps inside lists: inner keys normalized, list position preserved")
    void mapsInsideListsNormalizedButPositionPreserved() {
        // Each list element is a map with unsorted keys. Same positional
        // order but different key order inside → same hash.
        Map<String, Object> item1A = new LinkedHashMap<>();
        item1A.put("action", "standup");
        item1A.put("hour", 9);
        Map<String, Object> item2A = new LinkedHashMap<>();
        item2A.put("action", "review");
        item2A.put("hour", 18);

        Map<String, Object> item1B = new LinkedHashMap<>();
        item1B.put("hour", 9);
        item1B.put("action", "standup");
        Map<String, Object> item2B = new LinkedHashMap<>();
        item2B.put("hour", 18);
        item2B.put("action", "review");

        Map<String, Object> a = Map.of("habits",
                Map.of("recurring_actions", List.of(item1A, item2A)));
        Map<String, Object> b = Map.of("habits",
                Map.of("recurring_actions", List.of(item1B, item2B)));

        assertThat(ProfileHasher.hashProfile(a))
                .isEqualTo(ProfileHasher.hashProfile(b));

        // Swap list positions → must NOT match.
        List<Map<String, Object>> swapped = new ArrayList<>();
        swapped.add(item2B);
        swapped.add(item1B);
        Map<String, Object> c = Map.of("habits", Map.of("recurring_actions", swapped));

        assertThat(ProfileHasher.hashProfile(a))
                .isNotEqualTo(ProfileHasher.hashProfile(c));
    }

    @Test
    @DisplayName("mutable meta + per-field timestamps stripped even when buried in nested maps")
    void mutableFieldsStrippedAtAllLevels() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("persona", new LinkedHashMap<>(Map.of(
                "text", "x",
                "confidence", 0.5,
                "last_derived_at", "2026-01-01T00:00:00Z",
                "derivation_run_id", "run_A")));
        a.put("meta", Map.of("derivation_run_id", "run_A", "timestamp", "2026-01-01"));

        Map<String, Object> b = new LinkedHashMap<>();
        b.put("meta", Map.of("derivation_run_id", "run_Z", "timestamp", "2099-12-31"));
        b.put("persona", new LinkedHashMap<>(Map.of(
                "confidence", 0.5,
                "last_derived_at", "2099-12-31T23:59:59Z",
                "derivation_run_id", "run_Z",
                "text", "x")));

        assertThat(ProfileHasher.hashProfile(a)).isEqualTo(ProfileHasher.hashProfile(b));
    }
}
