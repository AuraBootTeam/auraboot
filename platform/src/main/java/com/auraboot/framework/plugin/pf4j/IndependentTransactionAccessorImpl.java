package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.IndependentTransactionAccessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Function;

/** Platform implementation of the plugin checkpoint transaction bridge. */
public final class IndependentTransactionAccessorImpl implements IndependentTransactionAccessor {

    private final TransactionTemplate requiresNew;
    private final DataAccessor dataAccessor;

    public IndependentTransactionAccessorImpl(PlatformTransactionManager transactionManager,
                                              DynamicDataService dynamicDataService) {
        Objects.requireNonNull(transactionManager, "transactionManager");
        Objects.requireNonNull(dynamicDataService, "dynamicDataService");
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // Unbounded legacy deliveries use ordinary complete-grant field checks inside each checkpoint.
        if (com.auraboot.framework.meta.service.impl.AsyncTaskExecutionContext.isActive())
            this.requiresNew.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.dataAccessor = new DynamicDataAccessorImpl(dynamicDataService);
    }

    @Override
    public <T> T requiresNew(Function<DataAccessor, T> work) {
        Objects.requireNonNull(work, "work");
        return requiresNew.execute(status -> work.apply(dataAccessor));
    }
}
