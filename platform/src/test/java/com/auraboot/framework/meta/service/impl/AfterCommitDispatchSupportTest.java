package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfterCommitDispatchSupportTest {
    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    void dispatchesImmediatelyWithoutATransaction() {
        AtomicInteger calls = new AtomicInteger();
        AfterCommitDispatchSupport.afterCommitOrNow(calls::incrementAndGet);
        assertThat(calls).hasValue(1);
    }

    @Test
    void synchronizationWithoutAnActualTransactionDispatchesImmediately() {
        TransactionSynchronizationManager.initSynchronization();
        AtomicInteger calls = new AtomicInteger();
        AfterCommitDispatchSupport.afterCommitOrNow(calls::incrementAndGet);
        assertThat(calls).hasValue(1);
        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
    }

    @Test
    void actualTransactionWithoutSynchronizationDispatchesImmediately() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        AtomicInteger calls = new AtomicInteger();
        AfterCommitDispatchSupport.afterCommitOrNow(calls::incrementAndGet);
        assertThat(calls).hasValue(1);
    }

    @Test
    void defersUntilCommitAndPreservesRegistrationOrder() {
        beginTransaction();
        StringBuilder calls = new StringBuilder();
        AfterCommitDispatchSupport.afterCommitOrNow(() -> calls.append("first"));
        AfterCommitDispatchSupport.afterCommitOrNow(() -> calls.append("second"));
        assertThat(calls).isEmpty();
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertThat(callbacks).hasSize(2);
        callbacks.forEach(callback -> callback.beforeCommit(false));
        assertThat(calls).isEmpty();
        callbacks.forEach(TransactionSynchronization::afterCommit);
        assertThat(calls).hasToString("firstsecond");
    }

    @Test
    void rollbackDoesNotDispatch() {
        beginTransaction();
        AtomicInteger calls = new AtomicInteger();
        AfterCommitDispatchSupport.afterCommitOrNow(calls::incrementAndGet);
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        assertThat(callbacks).hasSize(1);
        callbacks.forEach(TransactionSynchronization::beforeCompletion);
        callbacks.forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(calls).hasValue(0);
    }

    @Test
    void dispatchErrorsRemainTheCallersResponsibility() {
        IllegalStateException failure = new IllegalStateException("dispatch failed");
        assertThatThrownBy(() -> AfterCommitDispatchSupport.afterCommitOrNow(() -> { throw failure; }))
                .isSameAs(failure);
    }

    private static void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }
}
