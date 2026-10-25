package com.auraboot.framework.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CanonicalJsonHasher} — no Spring context required.
 *
 * These guarantees are load-bearing for Shadow Mode: if any of them break
 * we silently start rejecting valid draft promotions again (or worse,
 * promote drafts whose output doesn't actually match).
 */
@DisplayName("CanonicalJsonHasher (PR-54)")
class CanonicalJsonHasherTest {

    @Test
    @DisplayName("key order independence: hash({a:1,b:2}) == hash({b:2,a:1})")
    void map_key_order_stable() {
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("a", 1);
        m1.put("b", 2);

        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("b", 2);
        m2.put("a", 1);

        String h1 = CanonicalJsonHasher.sha256Canonical(m1);
        String h2 = CanonicalJsonHasher.sha256Canonical(m2);
        assertThat(h1).isNotNull().hasSize(64);
        assertThat(h1).isEqualTo(h2);
    }

    @Test
    @DisplayName("nested maps sort recursively")
    void nested_maps_sort_recursively() {
        Map<String, Object> inner1 = new LinkedHashMap<>();
        inner1.put("x", 10);
        inner1.put("y", 20);
        Map<String, Object> outer1 = new LinkedHashMap<>();
        outer1.put("z", inner1);
        outer1.put("a", "first");

        Map<String, Object> inner2 = new HashMap<>();
        inner2.put("y", 20);
        inner2.put("x", 10);
        Map<String, Object> outer2 = new LinkedHashMap<>();
        outer2.put("a", "first");
        outer2.put("z", inner2);

        assertThat(CanonicalJsonHasher.sha256Canonical(outer1))
                .isEqualTo(CanonicalJsonHasher.sha256Canonical(outer2));
    }

    @Test
    @DisplayName("list of maps: each element's keys sorted but list order preserved")
    void list_order_preserved_but_items_sorted() {
        Map<String, Object> a1 = new LinkedHashMap<>();
        a1.put("b", 2);
        a1.put("a", 1);
        Map<String, Object> a2 = new LinkedHashMap<>();
        a2.put("a", 1);
        a2.put("b", 2);

        List<Object> listOrder1 = List.of(a1, Map.of("c", 3));
        List<Object> listOrder2 = new ArrayList<>();
        listOrder2.add(Map.of("c", 3));
        listOrder2.add(a2);

        // Same items, different list order → different hash.
        assertThat(CanonicalJsonHasher.sha256Canonical(listOrder1))
                .isNotEqualTo(CanonicalJsonHasher.sha256Canonical(listOrder2));

        // Same items, same list order, reordered inner keys → same hash.
        List<Object> listOrder1b = List.of(a2, Map.of("c", 3));
        assertThat(CanonicalJsonHasher.sha256Canonical(listOrder1))
                .isEqualTo(CanonicalJsonHasher.sha256Canonical(listOrder1b));
    }

    @Test
    @DisplayName("null payload returns null (not empty string)")
    void null_payload_null_hash() {
        assertThat(CanonicalJsonHasher.sha256Canonical(null)).isNull();
    }

    @Test
    @DisplayName("non-serializable input returns null")
    void non_serializable_null_hash() {
        Object bad = new Object() {
            @SuppressWarnings("unused")
            public Object getSelf() { throw new RuntimeException("boom"); }
        };
        assertThat(CanonicalJsonHasher.sha256Canonical(bad)).isNull();
    }

    @Test
    @DisplayName("sha256CanonicalJsonString matches sha256Canonical of the parsed object")
    void json_string_and_object_produce_same_hash() {
        Map<String, Object> obj = new LinkedHashMap<>();
        obj.put("a", 1);
        obj.put("b", List.of("x", "y"));

        String raw = "{\"b\":[\"x\",\"y\"],\"a\":1}";
        assertThat(CanonicalJsonHasher.sha256CanonicalJsonString(raw))
                .isEqualTo(CanonicalJsonHasher.sha256Canonical(obj));
    }

    @Test
    @DisplayName("sha256CanonicalJsonString: null/blank/invalid → null")
    void json_string_null_handling() {
        assertThat(CanonicalJsonHasher.sha256CanonicalJsonString(null)).isNull();
        assertThat(CanonicalJsonHasher.sha256CanonicalJsonString("")).isNull();
        assertThat(CanonicalJsonHasher.sha256CanonicalJsonString("   ")).isNull();
        assertThat(CanonicalJsonHasher.sha256CanonicalJsonString("not json {")).isNull();
    }
}
