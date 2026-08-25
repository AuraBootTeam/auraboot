package com.auraboot.framework.plugin.extension;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackgroundDataAccessorContractTest {

    @Test
    void batchClaimRequestIsBoundedAndDefensivelyCopied() {
        Map<String, Object> exact = new LinkedHashMap<>(Map.of("status", "pending"));
        List<Object> statuses = new ArrayList<>(List.of("pending", "retry"));
        Map<String, List<Object>> in = new LinkedHashMap<>();
        in.put("status", statuses);

        BackgroundDataAccessor.BatchClaimRequest request =
                new BackgroundDataAccessor.BatchClaimRequest(
                        "job", exact, in, Map.of("due_at", "now"),
                        Map.of("leased_until", "later"), List.of("due_at"), 10);
        exact.put("other", "value");
        statuses.add("publishing");

        assertThat(request.exactFilters()).containsOnlyKeys("status");
        assertThat(request.inFilters().get("status")).containsExactly("pending", "retry");
        assertThatThrownBy(() -> request.claimValues().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void batchClaimRequestRejectsUnboundedOrAmbiguousInputs() {
        assertThatThrownBy(() -> new BackgroundDataAccessor.BatchClaimRequest(
                "job", Map.of(), Map.of(), Map.of(), Map.of("lease", "x"), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackgroundDataAccessor.BatchClaimRequest(
                "job", Map.of(), Map.of(), Map.of(), Map.of(), List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        Map<String, Object> nullValue = new LinkedHashMap<>();
        nullValue.put("lease", null);
        assertThatThrownBy(() -> new BackgroundDataAccessor.BatchClaimRequest(
                "job", Map.of(), Map.of(), Map.of(), nullValue, List.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundedPageRejectsHostContractViolation() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i <= BackgroundDataAccessor.MAX_BOUNDED_BATCH_SIZE; i++) {
            rows.add(Map.of("pid", String.valueOf(i)));
        }
        assertThatThrownBy(() -> new BackgroundDataAccessor.BoundedPage(rows, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transactionExecutionFailsClosedWhenHostCapabilityIsMissing() {
        BackgroundDataAccessor accessor = new MinimalAccessor();

        assertThatThrownBy(() -> accessor.executeInTransaction(() -> { }))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    private static final class MinimalAccessor implements BackgroundDataAccessor {
        @Override public Map<String, Object> create(long t, String m, Map<String, Object> d) { return Map.of(); }
        @Override public java.util.Optional<Map<String, Object>> tryCreate(long t, String m, Map<String, Object> d) { return java.util.Optional.empty(); }
        @Override public Map<String, Object> getById(long t, String m, String id) { return null; }
        @Override public List<Map<String, Object>> query(long t, String m, Map<String, Object> f) { return List.of(); }
        @Override public Map<String, Object> update(long t, String m, String id, Map<String, Object> d) { return Map.of(); }
        @Override public boolean compareAndSet(long t, String m, String id, String f, Object e, Object n) { return false; }
        @Override public boolean compareAndSet(long t, String m, String id, String f, Object e, Map<String, Object> n) { return false; }
        @Override public void delete(long t, String m, String id) { }
        @Override public java.util.Optional<Long> incrementWithinCap(long t, String m, String id, String f, long d, String c) { return java.util.Optional.empty(); }
    }
}
