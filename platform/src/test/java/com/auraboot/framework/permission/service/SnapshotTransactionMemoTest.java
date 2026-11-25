package com.auraboot.framework.permission.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What Spring binds for a transaction is set by hand: no database, no Spring context. */
class SnapshotTransactionMemoTest {

    private final Object key = new Object();

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

    /** Notifies the synchronizations the way a commit does, then forgets the transaction. */
    private static void complete() {
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
        TransactionSynchronizationManager.clear();
    }

    @Test
    void activeOnlyForAReadOnlySnapshotTransaction() {
        assertThat(SnapshotTransactionMemo.isActive()).as("no transaction").isFalse();
        begin(false, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(SnapshotTransactionMemo.isActive()).as("writable at a snapshot level").isFalse();
        complete();
        begin(true, TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertThat(SnapshotTransactionMemo.isActive()).as("read-only, not a snapshot level").isFalse();
        complete();
        begin(true, null);
        assertThat(SnapshotTransactionMemo.isActive()).as("read-only, isolation unknown").isFalse();
        complete();
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(SnapshotTransactionMemo.isActive()).as("read-only repeatable read").isTrue();
        complete();
        begin(true, TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertThat(SnapshotTransactionMemo.isActive()).as("read-only serializable").isTrue();
        complete();
        assertThat(SnapshotTransactionMemo.isActive()).as("after completion").isFalse();
    }

    @Test
    void aRepeatedKeyIsLoadedOnceInsideOneReadOnlySnapshotTransaction() {
        AtomicInteger loads = new AtomicInteger();
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            String first = SnapshotTransactionMemo.get(key, () -> "answer " + loads.incrementAndGet());
            String second = SnapshotTransactionMemo.get(key, () -> "answer " + loads.incrementAndGet());
            assertThat(first).isEqualTo("answer 1");
            assertThat(second).isEqualTo("answer 1");
            assertThat(loads).hasValue(1);
        } finally {
            complete();
        }
    }

    @Test
    void differentKeysDoNotShareAnAnswer() {
        AtomicInteger loads = new AtomicInteger();
        Object other = new Object();
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThat(SnapshotTransactionMemo.get(key, loads::getAndIncrement)).isZero();
            assertThat(SnapshotTransactionMemo.get(other, loads::getAndIncrement)).isEqualTo(1);
            assertThat(SnapshotTransactionMemo.get(key, loads::getAndIncrement)).isZero();
        } finally {
            complete();
        }
        assertThat(loads).hasValue(2);
    }

    @Test
    void anAbsentAnswerIsReusedToo() {
        AtomicInteger loads = new AtomicInteger();
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            String absent = SnapshotTransactionMemo.get(key, () -> null);
            assertThat(absent).isNull();
            String again = SnapshotTransactionMemo.get(key, () -> {
                loads.incrementAndGet();
                return "late";
            });
            assertThat(again).as("a lookup that found nothing is not repeated").isNull();
        } finally {
            complete();
        }
        assertThat(loads).hasValue(0);
    }

    @Test
    void aFailingLoaderLeavesNothingBehind() {
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThatThrownBy(() -> SnapshotTransactionMemo.get(key, () -> {
                throw new IllegalStateException("database unreachable");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(SnapshotTransactionMemo.get(key, () -> "recovered")).isEqualTo("recovered");
        } finally {
            complete();
        }
    }

    @Test
    void theAnswerEndsWithTheTransaction() {
        AtomicInteger loads = new AtomicInteger();
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThat(SnapshotTransactionMemo.get(key, loads::getAndIncrement)).isZero();
        } finally {
            complete();
        }
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThat(SnapshotTransactionMemo.get(key, loads::getAndIncrement))
                    .as("the next transaction reads again").isEqualTo(1);
        } finally {
            complete();
        }
        assertThat(loads).hasValue(2);
    }

    @Test
    void aTransactionThatKeepsNoSynchronizationIsNotMemoized() {
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        TransactionSynchronizationManager.clearSynchronization();
        AtomicInteger loads = new AtomicInteger();
        assertThat(SnapshotTransactionMemo.isActive()).isFalse();
        assertThat(SnapshotTransactionMemo.get(key, loads::getAndIncrement)).isZero();
        assertThat(SnapshotTransactionMemo.get(key, loads::getAndIncrement)).isEqualTo(1);
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test
    void answersLeftBehindByAnUnfinishedTransactionAreNotTheNextOnes() {
        // A transaction that ended without running its completion callback leaves its answers bound
        // to the thread. They belong to a synchronization that is no longer registered.
        AtomicInteger loads = new AtomicInteger();
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThat(SnapshotTransactionMemo.get(key, loads::getAndIncrement)).isZero();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.clear();
        }
        assertThat(TransactionSynchronizationManager.getResourceMap()).isNotEmpty();

        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            assertThat(SnapshotTransactionMemo.get(key, loads::getAndIncrement))
                    .as("the next transaction reads for itself").isEqualTo(1);
            assertThat(SnapshotTransactionMemo.get(key, loads::getAndIncrement)).isEqualTo(1);
        } finally {
            complete();
        }
        assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
    }

    @Test
    void completionDropsTheAnswersOfTheThread() {
        begin(true, TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            SnapshotTransactionMemo.get(key, () -> "answer");
            assertThat(TransactionSynchronizationManager.getResourceMap()).hasSize(1);
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            assertThat(TransactionSynchronizationManager.getResourceMap())
                    .as("the answer leaves with the transaction, whichever way the transaction ends").isEmpty();
        } finally {
            complete();
        }
    }

    @ParameterizedTest(name = "readOnly={0} isolation={1}")
    @CsvSource({
            "false,-1",      // writable, isolation unknown
            "false,4",       // writable repeatable read
            "true,2",        // read-only read committed
            "true,1",        // read-only read uncommitted
            "true,0"         // read-only, default isolation
    })
    void outsideSuchATransactionEveryCallReachesTheLoader(boolean readOnly, int isolation) {
        AtomicInteger loads = new AtomicInteger();
        begin(readOnly, isolation == -1 ? null : isolation);
        try {
            assertThat(SnapshotTransactionMemo.get(key, loads::getAndIncrement)).isZero();
            assertThat(SnapshotTransactionMemo.get(key, loads::getAndIncrement)).isEqualTo(1);
        } finally {
            complete();
        }
        assertThat(SnapshotTransactionMemo.get(key, loads::getAndIncrement))
                .as("and without any transaction").isEqualTo(2);
    }
}
