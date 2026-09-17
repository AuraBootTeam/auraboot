package com.auraboot.framework.meta.cache;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide epoch for data-access-scoped result caches (e.g. the
 * {@code aggregateQuery} cache).
 *
 * <p>Cache keys for data-access results embed tenant/user/member/permit-scope,
 * which prevents reuse across subjects but NOT across permission changes for
 * the same subject: a revoked user could keep reading stale aggregate results
 * until the TTL expires (AMOS cockpit gap G06, "撤权后旧缓存不得泄露").
 * Bumping the epoch whenever a permission-affecting event commits makes every
 * pre-existing key unreachable immediately; entries expire passively.
 *
 * <p>{@link com.auraboot.framework.permission.listener.PermissionCacheEvictionListener}
 * is the production bump source. Single-node semantics only, matching the
 * in-process cache manager; a distributed cache would move the epoch into the
 * shared store.
 */
public final class DataAccessCacheEpoch {

    private static final AtomicLong EPOCH = new AtomicLong(0);

    private DataAccessCacheEpoch() {
    }

    /** Current epoch value for cache-key embedding. */
    public static long current() {
        return EPOCH.get();
    }

    /** Invalidate all epoch-keyed cached entries by advancing the epoch. */
    public static long bump() {
        return EPOCH.incrementAndGet();
    }
}
