package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.entity.DataPermissionPolicy;
import com.auraboot.framework.permission.engine.model.FieldPermissionSet;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Command/request local cache for dynamic-data query guards.
 *
 * <p>This scope caches derived SQL guards and permission metadata for one
 * command. It never caches row data or count results.
 */
public final class DynamicDataQueryScope implements AutoCloseable {

    private static final ThreadLocal<State> CURRENT = new ThreadLocal<>();

    private final State state;
    private boolean closed;

    private DynamicDataQueryScope(State state) {
        this.state = state;
    }

    public static DynamicDataQueryScope open() {
        State state = CURRENT.get();
        if (state == null) {
            state = new State();
            CURRENT.set(state);
        }
        state.depth++;
        return new DynamicDataQueryScope(state);
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    public static String rowFilter(Long tenantId, String modelCode, Long userId, Supplier<String> supplier) {
        return getOrCompute(new FilterKey(FilterKind.ROW, tenantId, modelCode, userId), supplier);
    }

    public static String domainFilter(Long tenantId, String modelCode, Long userId, Supplier<String> supplier) {
        return getOrCompute(new FilterKey(FilterKind.DOMAIN, tenantId, modelCode, userId), supplier);
    }

    public static Set<String> nonWritableFields(
            Long tenantId,
            String modelCode,
            Long userId,
            Supplier<Set<String>> supplier) {
        State state = CURRENT.get();
        if (state == null) {
            return supplier.get();
        }
        AccessKey key = new AccessKey(tenantId, modelCode, userId);
        return state.nonWritableFields.computeIfAbsent(key, ignored -> supplier.get());
    }

    public static FieldPermissionSet fieldPermissions(
            Long tenantId,
            String modelCode,
            Long memberId,
            Supplier<FieldPermissionSet> supplier) {
        State state = CURRENT.get();
        if (state == null) {
            return supplier.get();
        }
        AccessKey key = new AccessKey(tenantId, modelCode, memberId);
        return state.fieldPermissions.computeIfAbsent(key, ignored -> supplier.get());
    }

    public static List<DataPermissionPolicy> effectivePolicies(
            Long tenantId,
            String modelCode,
            Long memberId,
            Supplier<List<DataPermissionPolicy>> supplier) {
        State state = CURRENT.get();
        if (state == null) {
            return supplier.get();
        }
        AccessKey key = new AccessKey(tenantId, modelCode, memberId);
        return state.effectivePolicies.computeIfAbsent(key, ignored -> supplier.get());
    }

    private static String getOrCompute(FilterKey key, Supplier<String> supplier) {
        State state = CURRENT.get();
        if (state == null) {
            return supplier.get();
        }
        if (state.filters.containsKey(key)) {
            return state.filters.get(key);
        }
        String value = supplier.get();
        state.filters.put(key, value);
        return value;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        State current = CURRENT.get();
        if (current != state) {
            return;
        }
        current.depth--;
        if (current.depth <= 0) {
            CURRENT.remove();
        }
    }

    private static final class State {
        private final Map<FilterKey, String> filters = new HashMap<>();
        private final Map<AccessKey, Set<String>> nonWritableFields = new HashMap<>();
        private final Map<AccessKey, FieldPermissionSet> fieldPermissions = new HashMap<>();
        private final Map<AccessKey, List<DataPermissionPolicy>> effectivePolicies = new HashMap<>();
        private int depth;
    }

    private enum FilterKind {
        ROW,
        DOMAIN
    }

    private record FilterKey(FilterKind kind, Long tenantId, String modelCode, Long userId) {
        private FilterKey {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(modelCode, "modelCode");
        }
    }

    private record AccessKey(Long tenantId, String modelCode, Long subjectId) {
        private AccessKey {
            Objects.requireNonNull(modelCode, "modelCode");
        }
    }
}
