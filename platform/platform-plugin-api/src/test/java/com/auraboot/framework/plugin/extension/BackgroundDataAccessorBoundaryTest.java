package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;

class BackgroundDataAccessorBoundaryTest {
    @Test
    void queryInForwardsTenantAndDistinctNonNullCandidatesInOrder() {
        List<List<Object>> calls = new ArrayList<>();
        BackgroundDataAccessor accessor = accessor((proxy, method, args) -> {
            assertThat(method.getName()).isEqualTo("query");
            calls.add(List.of(args));
            return List.of(Map.of("pid", ((Map<?, ?>) args[2]).get("status")));
        });
        assertThat(accessor.queryIn(7L, "job", "status", Arrays.asList("pending", null, "retry", "pending")))
                .containsExactly(Map.of("pid", "pending"), Map.of("pid", "retry"));
        assertThat(calls).containsExactly(List.of(7L, "job", Map.of("status", "pending")),
                List.of(7L, "job", Map.of("status", "retry")));
    }

    @Test
    void emptyCandidatesNeverAccessHostAndInvalidFieldsFailBeforeQuery() {
        BackgroundDataAccessor accessor = accessor((proxy, method, args) -> { throw new AssertionError("Host must not be invoked"); });
        assertThat(accessor.queryIn(7L, "job", "status", null)).isEmpty();
        assertThat(accessor.queryIn(7L, "job", "status", List.of())).isEmpty();
        assertThat(accessor.queryIn(7L, "job", "status", Arrays.asList(null, null))).isEmpty();
        for (String field : Arrays.asList(null, "", " ")) {
            assertThatThrownBy(() -> accessor.queryIn(7L, "job", field, List.of("value")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fieldName");
        }
    }

    @Test
    void absentCapabilitiesFailClosedWithoutInvokingWork() {
        BackgroundDataAccessor accessor = accessor((proxy, method, args) -> { throw new AssertionError("Host must not be invoked"); });
        AtomicBoolean invoked = new AtomicBoolean();
        assertThatThrownBy(() -> accessor.executeInTransaction(() -> invoked.set(true))).isInstanceOf(UnsupportedOperationException.class);
        assertThat(invoked).isFalse();
        assertThatThrownBy(accessor::transactionResourceId).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> accessor.queryPage(7L, "job", Map.of(), null, 10)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> accessor.claimBatch(7L, null)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void unboundedIncrementForwardsEveryArgumentWithNoCap() {
        BackgroundDataAccessor accessor = accessor((proxy, method, args) -> {
            assertThat(method.getName()).isEqualTo("incrementWithinCap");
            assertThat(args).containsExactly(7L, "job", "row", "attempts", 2L, null);
            return Optional.of(4L);
        });
        assertThat(accessor.increment(7L, "job", "row", "attempts", 2L)).contains(4L);
    }

    @Test
    void boundedPageCopiesRowsAndNormalizesMissingRows() {
        List<Map<String, Object>> rows = new ArrayList<>(List.of(Map.of("pid", "row")));
        var page = new BackgroundDataAccessor.BoundedPage(rows, "row");
        rows.clear();
        assertThat(page.records()).containsExactly(Map.of("pid", "row"));
        assertThat(page.nextCursor()).isEqualTo("row");
        assertThatThrownBy(() -> page.records().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new BackgroundDataAccessor.BoundedPage(null, null).records()).isEmpty();
    }

    @Test
    void batchClaimRejectsEachInvalidInputBeforeHostExecution() {
        for (String model : Arrays.asList(null, "", " ")) {
            assertThatThrownBy(() -> claim(model, null, null, null, Map.of("lease", "x"), null, 1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modelCode");
        }
        for (int limit : new int[]{0, -1, 1001}) {
            assertThatThrownBy(() -> claim("job", null, null, null, Map.of("lease", "x"), null, limit))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limit");
        }
        Map<String, Object> nullValue = new java.util.LinkedHashMap<>();
        nullValue.put("state", null);
        assertThatThrownBy(() -> claim("job", nullValue, null, null, Map.of("lease", "x"), null, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("filters");
        assertThatThrownBy(() -> claim("job", null, null, nullValue, Map.of("lease", "x"), null, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("filters");
        for (String field : Arrays.asList(null, "", " ")) {
            Map<String, Object> bad = new java.util.LinkedHashMap<>();
            bad.put(field, "value");
            assertThatThrownBy(() -> claim("job", bad, null, null, Map.of("lease", "x"), null, 1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("field codes");
            Map<String, List<Object>> badIn = new java.util.LinkedHashMap<>();
            badIn.put(field, List.of("value"));
            assertThatThrownBy(() -> claim("job", null, badIn, null, Map.of("lease", "x"), null, 1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IN filters");
        }
        for (List<Object> candidates : Arrays.<List<Object>>asList(null, List.of(), Arrays.asList("x", null))) {
            Map<String, List<Object>> badIn = new java.util.LinkedHashMap<>();
            badIn.put("state", candidates);
            assertThatThrownBy(() -> claim("job", null, badIn, null, Map.of("lease", "x"), null, 1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("IN filters");
        }
        assertThatThrownBy(() -> claim("job", null, null, null, Map.of("lease", "x"), List.of(" "), 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orderByFields");
    }

    @Test
    void absentClaimFiltersNormalizeAndMaximumBatchIsAccepted() {
        var request = claim("job", null, null, null, Map.of("lease", "x"), null, 1000);
        assertThat(request.exactFilters()).isEmpty();
        assertThat(request.inFilters()).isEmpty();
        assertThat(request.notAfterFilters()).isEmpty();
        assertThat(request.orderByFields()).isEmpty();
        assertThat(request.limit()).isEqualTo(1000);
        assertThat(request.claimValues()).containsExactlyEntriesOf(Map.of("lease", "x"));
    }

    private static BackgroundDataAccessor.BatchClaimRequest claim(String model, Map<String, Object> exact,
            Map<String, List<Object>> in, Map<String, Object> before, Map<String, Object> values, List<String> order, int limit) {
        return new BackgroundDataAccessor.BatchClaimRequest(model, exact, in, before, values, order, limit);
    }

    private static BackgroundDataAccessor accessor(InvocationHandler host) {
        return (BackgroundDataAccessor) Proxy.newProxyInstance(BackgroundDataAccessor.class.getClassLoader(),
                new Class<?>[]{BackgroundDataAccessor.class}, (proxy, method, args) -> method.isDefault()
                        ? InvocationHandler.invokeDefault(proxy, method, args) : host.invoke(proxy, method, args));
    }
}
