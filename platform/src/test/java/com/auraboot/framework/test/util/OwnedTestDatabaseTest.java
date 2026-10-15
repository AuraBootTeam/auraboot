package com.auraboot.framework.test.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the ownership contract of the raw-JDBC slot ITs: slot-named databases are always
 * accepted, shared developer databases are rejected unless the CI runner's job-id
 * contract proves the run owns its own ephemeral database, and a missing URL never
 * passes.
 */
class OwnedTestDatabaseTest {

    private static final String CI_JOBS = "oss-backend-slot-abc123";

    @Test
    void slotNamedDatabasesAreOwnedRegardlessOfRunner() {
        assertThat(OwnedTestDatabase.isOwned(
                "jdbc:postgresql://127.0.0.1:5432/aura_bpm_2", OwnedTestDatabase.AURA_BPM_SLOT_PATTERN, false))
                .isTrue();
        assertThat(OwnedTestDatabase.isOwned(
                "jdbc:postgresql://127.0.0.1:5432/aura_bpm_17?charSet=UTF8", OwnedTestDatabase.AURA_BPM_SLOT_PATTERN, false))
                .isTrue();
        assertThat(OwnedTestDatabase.isOwned(
                "jdbc:postgresql://127.0.0.1:5432/auraboot_188", OwnedTestDatabase.AURABOOT_SLOT_PATTERN, false))
                .isTrue();
        assertThat(OwnedTestDatabase.isOwned(
                "jdbc:postgresql://127.0.0.1:5432/auraboot_3?charSet=UTF8", OwnedTestDatabase.AURABOOT_SLOT_PATTERN, false))
                .isTrue();
    }

    @Test
    void sharedDeveloperDatabaseIsRejectedWithoutTheRunnerContract() {
        assertThat(OwnedTestDatabase.isOwned(
                "jdbc:postgresql://127.0.0.1:5432/aura_boot", OwnedTestDatabase.AURA_BPM_SLOT_PATTERN, false))
                .isFalse();
        assertThat(OwnedTestDatabase.isOwned(
                "jdbc:postgresql://127.0.0.1:5432/postgres", OwnedTestDatabase.AURABOOT_SLOT_PATTERN, false))
                .isFalse();
        assertThat(OwnedTestDatabase.isOwned(
                "jdbc:postgresql://127.0.0.1:5432/aura_bpm_dev", OwnedTestDatabase.AURA_BPM_SLOT_PATTERN, false))
                .isFalse();
    }

    @Test
    void runnerOwnedEphemeralDatabaseSatisfiesTheContract() {
        // The CI runner provisions a fresh volume + Flyway migrate for this exact run and
        // exports AURA_CI_JOB_ID; that ownership is what the slot-name convention encoded.
        assertThat(OwnedTestDatabase.isOwned(
                "jdbc:postgresql://127.0.0.1:25341/aura_boot?charSet=UTF8", OwnedTestDatabase.AURA_BPM_SLOT_PATTERN, true))
                .isTrue();
        assertThat(OwnedTestDatabase.isOwned(
                "jdbc:postgresql://127.0.0.1:25341/aura_boot?charSet=UTF8", OwnedTestDatabase.AURABOOT_SLOT_PATTERN, true))
                .isTrue();
    }

    @Test
    void missingUrlNeverPassesEvenUnderTheRunnerContract() {
        assertThat(OwnedTestDatabase.isOwned(null, OwnedTestDatabase.AURA_BPM_SLOT_PATTERN, true)).isFalse();
    }

    @Test
    void requireThrowsExactlyTheGuardMessageWhenNotOwned() {
        // Pure overload: the env-backed variant would silently pass under the CI runner
        // (AURA_CI_JOB_ID set ⇒ owned), so the pin uses the deterministic decision core.
        assertThatThrownBy(() -> OwnedTestDatabase.require(
                "jdbc:postgresql://127.0.0.1:5432/aura_boot", OwnedTestDatabase.AURA_BPM_SLOT_PATTERN,
                "TEST_DATABASE_URL must identify an owned aura_bpm slot database", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TEST_DATABASE_URL must identify an owned aura_bpm slot database");
        assertThatThrownBy(() -> OwnedTestDatabase.require(
                null, OwnedTestDatabase.AURA_BPM_SLOT_PATTERN,
                "TEST_DATABASE_URL must identify an owned aura_bpm slot database", true))
                .isInstanceOf(IllegalStateException.class);
    }
}
