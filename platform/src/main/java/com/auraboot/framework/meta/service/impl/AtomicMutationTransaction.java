package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** Preserve caller transactions; ordinary human conditional writes require a coherent authorization view. */
@Component
@RequiredArgsConstructor
public class AtomicMutationTransaction {
    private final PlatformTransactionManager transactions;

    public <T> T execute(Supplier<T> operation) {
        NativeHumanActor.requireCompleteIdentity();
        if (TransactionSynchronizationManager.isActualTransactionActive()) return operation.get();
        boolean system = MetaContext.exists() && MetaContext.getCurrentUserId() == null
                && MetaContext.getCurrentUserPid() == null && MetaContext.getCurrentMemberId() == null
                && !com.auraboot.framework.application.security.MachineDataAccessContext.isActive()
                && !com.auraboot.framework.openplatform.service.InstalledIntegrationExecution.active();
        var transaction = new TransactionTemplate(transactions);
        // A host kernel counter is one guarded SQL UPDATE, not a human read/authorize/write unit.
        // READ COMMITTED lets PostgreSQL recheck its counter/cap predicate after a competing UPDATE.
        transaction.setIsolationLevel(system ? TransactionDefinition.ISOLATION_READ_COMMITTED
                : TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return transaction.execute(status -> operation.get());
    }
}
