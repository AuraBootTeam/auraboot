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

    private static final class InMemoryAccessor implements DataAccessor {
        final List<Map<String, Object>> queries = new ArrayList<>();

        @Override
        public Map<String, Object> getById(String modelCode, String recordId) {
            return null;
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
            return data;
        }

        @Override
        public List<Map<String, Object>> batchCreate(String modelCode, List<Map<String, Object>> dataList) {
            return dataList;
        }

        @Override
        public void delete(String modelCode, String recordId) {
        }
    }
}
