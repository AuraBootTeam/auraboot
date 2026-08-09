package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataAccessorTest {

    @Test
    void queryIn_defaultFallbackDelegatesToQueryForDistinctNonNullValues() {
        InMemoryAccessor accessor = new InMemoryAccessor();

        List<Map<String, Object>> rows = accessor.queryIn("m", "code", Arrays.asList("A", "B", "A", null));

        assertThat(rows).containsExactly(
                Map.of("code", "A"),
                Map.of("code", "B"));
        assertThat(accessor.queries).containsExactly(
                Map.of("code", "A"),
                Map.of("code", "B"));
    }

    @Test
    void queryIn_defaultFallbackShortCircuitsEmptyValues() {
        InMemoryAccessor accessor = new InMemoryAccessor();

        assertThat(accessor.queryIn("m", "code", null)).isEmpty();
        assertThat(accessor.queryIn("m", "code", List.of())).isEmpty();
        assertThat(accessor.queryIn("m", "code", Arrays.asList(null, null))).isEmpty();

        assertThat(accessor.queries).isEmpty();
    }

    @Test
    void queryIn_rejectsBlankFieldName() {
        InMemoryAccessor accessor = new InMemoryAccessor();

        assertThatThrownBy(() -> accessor.queryIn("m", " ", List.of("A")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldName");
    }

    @Test
    void batchDelete_defaultFallbackDeletesDistinctNonBlankIds() {
        InMemoryAccessor accessor = new InMemoryAccessor();

        accessor.batchDelete("m", Arrays.asList("A", "B", "A", null, ""));

        assertThat(accessor.deletes).containsExactly("A", "B");
    }

    @Test
    void compareAndSet_defaultFallbackUpdatesOnlyTheExpectedValue() {
        InMemoryAccessor accessor = new InMemoryAccessor();
        accessor.current = new java.util.HashMap<>(Map.of("status", "accepted"));

        assertThat(accessor.compareAndSet("m", "A", "status", "pending", "superseded"))
                .isFalse();
        assertThat(accessor.compareAndSet("m", "A", "status", "accepted", "superseded"))
                .isTrue();
        assertThat(accessor.current).containsEntry("status", "superseded");
    }

    private static final class InMemoryAccessor implements DataAccessor {
        final List<Map<String, Object>> queries = new ArrayList<>();
        final List<String> deletes = new ArrayList<>();
        Map<String, Object> current;

        @Override
        public Map<String, Object> getById(String modelCode, String recordId) {
            return current;
        }

        @Override
        public List<Map<String, Object>> query(String modelCode, Map<String, Object> filters) {
            queries.add(filters);
            return List.of(Map.of("code", filters.get("code")));
        }

        @Override
        public Map<String, Object> create(String modelCode, Map<String, Object> data) {
            return data;
        }

        @Override
        public Map<String, Object> update(String modelCode, String recordId, Map<String, Object> data) {
            if (current != null) current.putAll(data);
            return data;
        }

        @Override
        public List<Map<String, Object>> batchCreate(String modelCode, List<Map<String, Object>> dataList) {
            return dataList;
        }

        @Override
        public void delete(String modelCode, String recordId) {
            deletes.add(recordId);
        }
    }
}
