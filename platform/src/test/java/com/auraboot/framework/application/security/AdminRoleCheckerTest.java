package com.auraboot.framework.application.security;

import com.auraboot.framework.permission.enums.RoleCodes;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The lookup itself is a stub; what is pinned is how often the database is asked. */
class AdminRoleCheckerTest {

    private static final long TENANT = 7L;
    private static final long USER = 42L;

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final AdminRoleChecker checker = new AdminRoleChecker(jdbcTemplate, new SimpleMeterRegistry());

    @AfterEach
    void clear() {
        TransactionSynchronizationManager.clear();
    }

    /** Binds what Spring binds for a transaction with the given read-only flag and isolation level. */
    private static void begin(boolean readOnly, Integer isolation) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(readOnly);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static void complete() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        TransactionSynchronizationManager.clear();
    }

    private void roleIsHeld(boolean held) {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(), any(), any()))
                .thenReturn(held ? 1L : 0L);
    }

    private void databaseAskedTimes(int expected) {
        verify(jdbcTemplate, times(expected)).queryForObject(anyString(), eq(Long.class), any(), any(), any());
    }

    @Test
    void aPrivilegedRoleIsReadFromTheDatabaseOnEveryCall() {
        roleIsHeld(true);
        assertThat(checker.hasRole(TENANT, USER, RoleCodes.TENANT_ADMIN)).isTrue();
        assertThat(checker.hasRole(TENANT, USER, RoleCodes.TENANT_ADMIN)).isTrue();
        assertThat(checker.hasRole(TENANT, USER, RoleCodes.PLATFORM_ADMIN)).isTrue();
        assertThat(checker.hasRole(TENANT, USER, RoleCodes.PLATFORM_ADMIN)).isTrue();
        databaseAskedTimes(4);
    }

    @Test
    void aPrivilegedRoleIsReadOnceInsideOneReadOnlySnapshotTransaction() {
        roleIsHeld(true);
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThat(checker.hasRole(TENANT, USER, RoleCodes.TENANT_ADMIN)).isTrue();
            assertThat(checker.hasRole(TENANT, USER, RoleCodes.TENANT_ADMIN)).isTrue();
        } finally {
            complete();
        }
        databaseAskedTimes(1);
    }

    @Test
    void aRevokedPrivilegedRoleIsNotRememberedPastTheTransaction() {
        roleIsHeld(true);
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThat(checker.hasRole(TENANT, USER, RoleCodes.TENANT_ADMIN)).isTrue();
        } finally {
            complete();
        }
        // The revocation is committed after the first transaction ended; the next one must see it.
        roleIsHeld(false);
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThat(checker.hasRole(TENANT, USER, RoleCodes.TENANT_ADMIN)).isFalse();
        } finally {
            complete();
        }
        databaseAskedTimes(2);
    }

    @Test
    void aPrivilegedRoleIsReadOnEveryCallInsideAWritableTransaction() {
        roleIsHeld(true);
        begin(false, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThat(checker.hasRole(TENANT, USER, RoleCodes.TENANT_ADMIN)).isTrue();
            assertThat(checker.hasRole(TENANT, USER, RoleCodes.TENANT_ADMIN)).isTrue();
        } finally {
            complete();
        }
        databaseAskedTimes(2);
    }

    @Test
    void differentSubjectsAreAskedSeparatelyInsideOneReadOnlySnapshotTransaction() {
        roleIsHeld(true);
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThat(checker.hasRole(TENANT, USER, RoleCodes.TENANT_ADMIN)).isTrue();
            assertThat(checker.hasRole(TENANT, USER + 1, RoleCodes.TENANT_ADMIN)).isTrue();
            assertThat(checker.hasRole(TENANT + 1, USER, RoleCodes.TENANT_ADMIN)).isTrue();
            assertThat(checker.hasRole(TENANT, USER, RoleCodes.PLATFORM_ADMIN)).isTrue();
        } finally {
            complete();
        }
        databaseAskedTimes(4);
    }

    @Test
    void anOrdinaryRoleKeepsItsBoundedCache() {
        roleIsHeld(false);
        assertThat(checker.hasRole(TENANT, USER, "operator")).isFalse();
        assertThat(checker.hasRole(TENANT, USER, "operator")).isFalse();
        databaseAskedTimes(1);
    }

    @Test
    void anIncompleteQuestionNeverReachesTheDatabase() {
        assertThat(checker.hasRole(null, USER, RoleCodes.TENANT_ADMIN)).isFalse();
        assertThat(checker.hasRole(TENANT, null, RoleCodes.TENANT_ADMIN)).isFalse();
        assertThat(checker.hasRole(TENANT, USER, null)).isFalse();
        databaseAskedTimes(0);
    }
}
