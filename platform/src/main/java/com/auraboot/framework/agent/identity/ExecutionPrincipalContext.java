package com.auraboot.framework.agent.identity;

import com.auraboot.framework.application.tenant.MetaContext;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Thread-bound execution principal and scoped MetaContext authority switch.
 */
public final class ExecutionPrincipalContext {

    private static final ThreadLocal<ExecutionPrincipal> CURRENT = new ThreadLocal<>();

    private ExecutionPrincipalContext() {
    }

    public static Optional<ExecutionPrincipal> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static ExecutionPrincipal requireCurrent() {
        ExecutionPrincipal principal = CURRENT.get();
        if (principal == null) {
            throw new IllegalStateException("ExecutionPrincipal is not bound");
        }
        return principal;
    }

    public static void restore(ExecutionPrincipal principal) {
        if (principal == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(principal);
        }
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static <T> T callAs(ExecutionPrincipal principal, Supplier<T> action) {
        if (principal == null) {
            throw new IllegalArgumentException("execution principal is required");
        }
        if (action == null) {
            throw new IllegalArgumentException("execution action is required");
        }
        MetaContext.Snapshot previousMeta = MetaContext.snapshot();
        ExecutionPrincipal previousPrincipal = CURRENT.get();
        MetaContext.clear();
        MetaContext.setContext(
                principal.tenantId(),
                principal.actorUserId(),
                principal.actorUserPid(),
                principal.actorUsername(),
                principal.roleIds());
        MetaContext.setMemberId(principal.actorMemberId());
        // Identity changes; environment and correlation do not. Dropping either
        // here would make an employee escape the caller's selected environment
        // and sever the distributed trace at exactly the runtime boundary.
        if (previousMeta != null) {
            MetaContext.setEnvironmentId(previousMeta.envId());
            MetaContext.setOtelTraceId(previousMeta.otelTraceId());
        }
        CURRENT.set(principal);
        try {
            return action.get();
        } finally {
            CURRENT.remove();
            MetaContext.clear();
            if (previousMeta != null) {
                MetaContext.restore(previousMeta);
            }
            if (previousPrincipal != null) {
                CURRENT.set(previousPrincipal);
            }
        }
    }

    public static void runAs(ExecutionPrincipal principal, Runnable action) {
        callAs(principal, () -> {
            action.run();
            return null;
        });
    }
}
