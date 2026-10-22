package com.auraboot.framework.test.util;

/**
 * Ownership contract for the raw-JDBC "slot" ITs ({@code AuditTrailServiceCoverageIT},
 * {@code ValidationReferenceExistenceCoverageIT}, {@code DefaultEnvironmentConcurrencyIT}).
 * These tests create committed rows (hash-chain appends, validation fixtures, default
 * environments) through their own connection pool, so {@code TEST_DATABASE_URL} must
 * identify a database <b>this run owns</b> — never a shared developer database.
 *
 * <p>Two shapes satisfy that contract:
 * <ul>
 *   <li>a slot-named database ({@code auraboot_N} / {@code aura_bpm_N}) — the local-machine
 *       convention where the operator provisions disposable per-run slots;</li>
 *   <li>the CI runner's compose-provisioned ephemeral database ({@code aura_boot}) — a fresh
 *       volume migrated by this exact run, proven by the runner's {@code AURA_CI_JOB_ID}
 *       environment (same contract seam as {@code AURA_CI_REQUIRE_KAFKA}).</li>
 * </ul>
 */
public final class OwnedTestDatabase {

    private OwnedTestDatabase() {
    }

    /** {@code auraboot_N} slot family (DefaultEnvironmentConcurrencyIT). */
    public static final String AURABOOT_SLOT_PATTERN =
            "jdbc:postgresql://[^/]+/auraboot_[0-9]+(?:\\?.*)?";

    /** {@code aura_bpm_N} slot family (audit / validation coverage ITs). */
    public static final String AURA_BPM_SLOT_PATTERN =
            "jdbc:postgresql://[^/]+/aura_bpm_[0-9]+(?:\\?.*)?";

    /**
     * Environment-backed check used by the guards: the database is owned when it is
     * slot-named, or when the CI runner's job-id contract is present for this process.
     */
    public static boolean isOwned(String url, String slotPattern) {
        return isOwned(url, slotPattern, System.getenv("AURA_CI_JOB_ID") != null);
    }

    /** Pure decision core — {@code ciOwnedRun} models the runner's job-id contract. */
    public static boolean isOwned(String url, String slotPattern, boolean ciOwnedRun) {
        if (url == null) {
            return false;
        }
        return url.matches(slotPattern) || ciOwnedRun;
    }

    /** Guard helper mirroring the slot ITs' fail-closed {@link IllegalStateException}. */
    public static void require(String url, String slotPattern, String message) {
        require(url, slotPattern, message, System.getenv("AURA_CI_JOB_ID") != null);
    }

    /** Pure decision core of {@link #require(String, String, String)} for deterministic pins. */
    public static void require(String url, String slotPattern, String message, boolean ciOwnedRun) {
        if (!isOwned(url, slotPattern, ciOwnedRun)) {
            throw new IllegalStateException(message);
        }
    }
}
