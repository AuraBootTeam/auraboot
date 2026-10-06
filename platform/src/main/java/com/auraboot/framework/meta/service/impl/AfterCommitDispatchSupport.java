package com.auraboot.framework.meta.service.impl;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Registers dispatch only after a real transaction commits; direct callers dispatch immediately. */
final class AfterCommitDispatchSupport {
    private AfterCommitDispatchSupport() {}

    static void afterCommitOrNow(Runnable dispatch) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch.run();
                }
            });
            return;
        }
        dispatch.run();
    }
}
