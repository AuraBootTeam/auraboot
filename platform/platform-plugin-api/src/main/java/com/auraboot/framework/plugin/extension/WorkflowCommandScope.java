package com.auraboot.framework.plugin.extension;

import java.util.Objects;
import java.util.function.Supplier;

/** Server-owned workflow invocation identity; never populated from HTTP payloads. */
public final class WorkflowCommandScope {
    private static final ThreadLocal<Invocation> CURRENT = new ThreadLocal<>();

    private WorkflowCommandScope() {}

    public record Invocation(Long tenantId, String processInstanceId, String businessKey) {
        public boolean owns(Long tenant, String targetRecordPid) {
            return tenantId != null && Objects.equals(tenantId, tenant)
                    && processInstanceId != null && !processInstanceId.isBlank()
                    && businessKey != null && Objects.equals(businessKey, targetRecordPid);
        }
    }

    public static Invocation current() {
        return CURRENT.get();
    }

    public static <T> T call(Invocation invocation, Supplier<T> action) {
        Objects.requireNonNull(invocation);
        Invocation previous = CURRENT.get();
        CURRENT.set(invocation);
        try {
            return action.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }
}
