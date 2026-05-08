package com.auraboot.framework.application.tenant;

import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.Set;

/**
 * 租户上下文管理
 * 使用ThreadLocal存储当前线程的租户信息
 */
public class MetaContext {

    private static final ThreadLocal<MetaContext> HOLDER = new ThreadLocal<>();
    private static final ThreadLocal<Long> MEMBER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Long> ENV_ID = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ENV_FILTER_BYPASSED = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> LOCK_GUARD_BYPASSED = ThreadLocal.withInitial(() -> false);

    @Getter
    private final Long tenantId;



    @Getter
    private final Long userId;

    @Getter
    private final String userPid;

    @Getter
    private final String username;

    private final Set<Long> roleIds;


    private MetaContext(Long tenantId, Long userId, String userPid, String username, Set<Long> roleIds) {
        this.tenantId = tenantId;
        this.userId = userId;
        this.userPid = userPid;
        this.username = username;
        this.roleIds = roleIds;
    }

    /* ---------- static API ---------- */

    public static void setContext(Long tenantId, Long userId, String userPid, String username) {
        setContext(tenantId, userId, userPid, username, Set.of());
    }

    public static void setContext(Long tenantId, Long userId, String userPid, String username,
                                  Set<Long> roleIds) {
        Set<Long> snapshot = roleIds == null
            ? Set.of()
            : Set.copyOf(roleIds);
        HOLDER.set(new MetaContext(tenantId, userId, userPid, username, snapshot));
    }

    public static Set<Long> getCurrentRoleIds() {
        MetaContext ctx = HOLDER.get();
        return ctx == null ? Set.of() : ctx.roleIds;
    }

    public static MetaContext get() {
        MetaContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "MetaContext not initialized for current thread"
            );
        }
        return ctx;
    }

    public static boolean exists() {
        return HOLDER.get() != null;
    }

    public static void clear() {
        HOLDER.remove();
        MEMBER_ID.remove();
        ENV_ID.remove();
        ENV_FILTER_BYPASSED.remove();
        LOCK_GUARD_BYPASSED.remove();
    }

    // ---- env-layering extension (PoC) ----

    /**
     * Get the current environment id. May be null if the request did not specify one and the
     * tenant has no default env yet.
     */
    public static Long getCurrentEnvironmentId() {
        return ENV_ID.get();
    }

    /**
     * Set the current environment id. Typically called by EnvironmentResolverFilter from
     * {@code ?env=} or {@code X-Environment} header, or by services starting background work.
     */
    public static void setEnvironmentId(Long envId) {
        ENV_ID.set(envId);
    }

    /**
     * @return true if env filter is currently bypassed (cross-env queries such as promotion).
     */
    public static boolean isEnvFilterBypassed() {
        return Boolean.TRUE.equals(ENV_FILTER_BYPASSED.get());
    }

    /**
     * Run a block with env filtering disabled. Use sparingly — only for legitimate cross-env
     * reads/writes (promotion source→target). State is restored even on exception.
     */
    public static <T> T runWithoutEnvFilter(java.util.function.Supplier<T> action) {
        Boolean prior = ENV_FILTER_BYPASSED.get();
        ENV_FILTER_BYPASSED.set(true);
        try {
            return action.get();
        } finally {
            ENV_FILTER_BYPASSED.set(prior);
        }
    }

    /**
     * Run a block with env filtering disabled (no return value).
     */
    public static void runWithoutEnvFilter(Runnable action) {
        runWithoutEnvFilter(() -> {
            action.run();
            return null;
        });
    }

    // ---- env lock guard (env-layering #17) ----

    /**
     * @return true if the lock guard is currently bypassed (legitimate writes such as
     *         {@code promotion.apply} that target a locked env via four-eyes flow).
     */
    public static boolean isLockGuardBypassed() {
        return Boolean.TRUE.equals(LOCK_GUARD_BYPASSED.get());
    }

    /**
     * Run a block with the lock guard disabled. Use sparingly — only for the legitimate
     * writes-to-locked-env path: promotion apply, plugin import bootstrap, system migrations.
     * State is restored even on exception.
     */
    public static <T> T runWithoutLockGuard(java.util.function.Supplier<T> action) {
        Boolean prior = LOCK_GUARD_BYPASSED.get();
        LOCK_GUARD_BYPASSED.set(true);
        try {
            return action.get();
        } finally {
            LOCK_GUARD_BYPASSED.set(prior);
        }
    }

    public static void runWithoutLockGuard(Runnable action) {
        runWithoutLockGuard(() -> {
            action.run();
            return null;
        });
    }

    public static Long getCurrentMemberId() {
        return MEMBER_ID.get();
    }

    public static void setMemberId(Long memberId) {
        MEMBER_ID.set(memberId);
    }

    /**
     * Set tenant-scoped context for system/background work with no user identity.
     */
    public static void setSystemTenantContext(Long tenantId) {
        HOLDER.set(new MetaContext(tenantId, null, null, null, Set.of()));
    }


    public static   Long getCurrentTenantId() {
        return get().getTenantId();
    }

    public static   String getCurrentTenantIdAsString() {
        return get().getTenantId()+"";
    }

    public static   Long getCurrentUserId() {
        return get().getUserId();
    }

    public static   String getCurrentUserPid() {
        return get().getUserPid();
    }




    public static String getCurrentUsername() {
        return get().getUsername();
    }

    /**
     * @deprecated Use {@link #setContext(Long, Long, String, String)} instead.
     * Setting tenantId alone may leave userId/userPid/username as null, causing inconsistency.
     * Only acceptable in scheduled tasks or batch jobs where no user context exists.
     */
    @Deprecated(since = "6.1.0")
    public static void setCurrentTenantId(Long tenantId) {
        MetaContext current = HOLDER.get();
        if (current != null) {
            HOLDER.set(new MetaContext(tenantId, current.userId, current.userPid, current.username, current.roleIds));
            return;
        }
        setSystemTenantContext(tenantId);
    }





    /**
     * @deprecated Use {@link #setContext(Long, Long, String, String)} instead.
     * Setting userId alone may leave tenantId as null, causing inconsistency.
     */
    @Deprecated(since = "6.1.0")
    public static void setCurrentUserId(Long userId) {
        MetaContext current = HOLDER.get();
        if (current != null) {
            HOLDER.set(new MetaContext(current.tenantId, userId, current.userPid, current.username, current.roleIds));
        } else {
            HOLDER.set(new MetaContext(null, userId, null, null, Set.of()));
        }
    }



}
