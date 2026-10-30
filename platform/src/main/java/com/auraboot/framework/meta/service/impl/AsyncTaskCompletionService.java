package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.entity.AsyncTask;
import com.auraboot.framework.meta.mapper.AsyncTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publish prepared domain results and queue completion atomically, ordered against cancellation. */
@Service
@RequiredArgsConstructor
public class AsyncTaskCompletionService {
    private final AsyncTaskMapper tasks;
    @Transactional
    public boolean complete(AsyncTask task,Runnable action) {
        Long owned=MetaContext.runWithoutTenantFilter(()->tasks.lockOwnedExecution(task.getId(),task.getTenantId(),task.getExecutionToken()));
        if(owned==null) return false;
        action.run();
        if(MetaContext.runWithoutTenantFilter(()->tasks.finishOwned(task))!=1)
            throw new CannotAcquireLockException("Async execution lease expired before publishing the domain result");
        return true;
    }
}
