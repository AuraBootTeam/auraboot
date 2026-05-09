package com.auraboot.framework.meta.service.executor;

import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test for {@link ExecutorRegistry} resolution semantics.
 */
class ExecutorRegistryTest {

    private static final class FakeExecutor implements ModelDataExecutor {
        private final String type;

        FakeExecutor(String type) {
            this.type = type;
        }

        @Override
        public String sourceType() {
            return type;
        }

        @Override
        public PaginationResult<Map<String, Object>> list(String modelCode, DynamicQueryRequest request) {
            return PaginationResult.of(List.of(), 0L, 1, 10);
        }

        @Override
        public Map<String, Object> get(String modelCode, Object primaryKeyValue) {
            return null;
        }
    }

    @Test
    void resolves_registered_sourceType() {
        FakeExecutor named = new FakeExecutor("namedQuery");
        FakeExecutor sqlView = new FakeExecutor("sqlView");
        ExecutorRegistry registry = new ExecutorRegistry(List.of(named, sqlView));

        Optional<ModelDataExecutor> r = registry.resolve("namedQuery");
        assertThat(r).isPresent().get().isSameAs(named);
        assertThat(registry.resolve("sqlView")).get().isSameAs(sqlView);
    }

    @Test
    void resolve_physical_returns_empty() {
        ExecutorRegistry registry = new ExecutorRegistry(List.of(new FakeExecutor("namedQuery")));
        assertThat(registry.resolve("physical")).isEmpty();
    }

    @Test
    void resolve_null_returns_empty() {
        ExecutorRegistry registry = new ExecutorRegistry(List.of(new FakeExecutor("namedQuery")));
        assertThat(registry.resolve(null)).isEmpty();
    }

    @Test
    void resolve_unknown_returns_empty() {
        ExecutorRegistry registry = new ExecutorRegistry(List.of(new FakeExecutor("namedQuery")));
        assertThat(registry.resolve("doesNotExist")).isEmpty();
    }

    @Test
    void duplicate_sourceType_throws() {
        FakeExecutor a = new FakeExecutor("namedQuery");
        FakeExecutor b = new FakeExecutor("namedQuery");
        assertThatThrownBy(() -> new ExecutorRegistry(List.of(a, b)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("namedQuery");
    }
}
