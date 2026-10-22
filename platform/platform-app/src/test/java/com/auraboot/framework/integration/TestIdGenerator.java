package com.auraboot.framework.integration;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Collision-free id generator for integration tests.
 *
 * <p>Replaces the legacy {@code 9_810_000L + System.nanoTime() % 10_000} pattern, whose
 * 10k modulus window allowed parallel test workers landing in the same nanosecond bucket
 * to mint identical tenant ids and sporadically fail unique-constraint checks
 * (e.g. {@code uq_user_soul_profile_active}).
 *
 * <p>Each generator method is backed by a single {@link AtomicLong} seeded once per JVM,
 * so every call within a test run returns a unique value regardless of worker count.
 * The {@code 9_810_000_000L} prefix (+1T) keeps these ids well above any real tenant id
 * allocated by production code paths.
 */
public final class TestIdGenerator {

    private static final long TENANT_PREFIX = 9_810_000_000L;
    private static final long USER_PREFIX = 9_820_000_000L;

    private static final AtomicLong TENANT_SEQ =
            new AtomicLong(System.currentTimeMillis() % 10_000_000L);
    private static final AtomicLong USER_SEQ =
            new AtomicLong(System.currentTimeMillis() % 10_000_000L);
    private static final AtomicLong CODE_SEQ =
            new AtomicLong(System.currentTimeMillis() % 10_000_000L);

    private TestIdGenerator() {}

    /** Returns a globally unique tenant id for the current JVM run. */
    public static Long uniqueTenantId() {
        return TENANT_PREFIX + TENANT_SEQ.incrementAndGet();
    }

    /** Returns a globally unique synthetic user id for the current JVM run. */
    public static Long uniqueUserId() {
        return USER_PREFIX + USER_SEQ.incrementAndGet();
    }

    /**
     * Returns a globally unique monotonically increasing suffix suitable for string
     * codes (e.g. {@code "agent-" + uniqueCodeSuffix()}).
     */
    public static long uniqueCodeSuffix() {
        return CODE_SEQ.incrementAndGet();
    }
}
