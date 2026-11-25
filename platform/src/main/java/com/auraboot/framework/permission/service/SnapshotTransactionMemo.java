package com.auraboot.framework.permission.service;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Values that hold for the whole life of a read-only snapshot transaction.
 *
 * <p>A Spring transaction that is read-only and runs at repeatable read or serializable cannot
 * write, and sees one database snapshot from its first statement to its end. Every lookup it
 * repeats therefore returns exactly what it returned the first time, so answering a repeated
 * lookup from its first answer changes no result and saves the statement. The answer is bound to
 * the transaction: it is dropped when the transaction completes, it is not shared with another
 * thread, and a transaction that is suspended for an inner one does not see it in the inner one.
 *
 * <p>"Cannot write" is what the database enforces for a transaction Spring reports as read-only:
 * the JDBC transaction manager puts the connection in read-only mode and PostgreSQL rejects a
 * write in such a transaction. The isolation level is likewise the one of the connection.
 *
 * <p>Outside such a transaction (no transaction, a writable one, read committed, or one that keeps
 * no transaction synchronization) every call reaches the loader, exactly as before. Configuration
 * that a transaction may change itself is therefore never answered from an earlier read.
 */
public final class SnapshotTransactionMemo {

    private static final Object RESOURCE_KEY = new Object() {
        @Override
        public String toString() {
            return "SnapshotTransactionMemo";
        }
    };

    private SnapshotTransactionMemo() {
    }

    /**
     * The answers of one transaction, together with the synchronization whose completion ends
     * them. A synchronization that is no longer registered belongs to a transaction that ended.
     */
    private record Answers(Map<Object, Object> values, TransactionSynchronization owner) {
    }

    /** True while the current transaction is read-only and at a snapshot isolation level. */
    public static boolean isActive() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return false;
        }
        Integer isolation = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
        return isolation != null
                && (isolation == TransactionDefinition.ISOLATION_REPEATABLE_READ
                || isolation == TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    /**
     * The value for {@code key}: the loader's answer the first time, that same answer for every
     * later call in the same read-only snapshot transaction. The key must name every input the
     * loader depends on, and the loader must only read.
     */
    @SuppressWarnings("unchecked")
    public static <T> T get(Object key, Supplier<T> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        if (!isActive()) {
            return loader.get();
        }
        Map<Object, Object> answers = answersOfCurrentTransaction();
        if (answers.containsKey(key)) {
            return (T) answers.get(key);
        }
        T value = loader.get();
        answers.put(key, value);
        return value;
    }

    private static Map<Object, Object> answersOfCurrentTransaction() {
        Answers bound = (Answers) TransactionSynchronizationManager.getResource(RESOURCE_KEY);
        if (bound != null && TransactionSynchronizationManager.getSynchronizations().contains(bound.owner())) {
            return bound.values();
        }
        // Nothing is bound, or a transaction that ended left its answers behind (its completion
        // callback did not run): those answers are not this transaction's.
        if (bound != null) {
            TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY);
        }
        Map<Object, Object> answers = new HashMap<>();
        TransactionSynchronization owner = new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY);
            }
        };
        TransactionSynchronizationManager.registerSynchronization(owner);
        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, new Answers(answers, owner));
        return answers;
    }
}
