package com.auraboot.framework.event.config;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.event.context.ThreadContextScopes;
import java.util.List;
import org.springframework.core.task.TaskDecorator;

/** Propagates tenant identity and registered domain contexts to platform workers. */
public class TenantAwareTaskDecorator implements TaskDecorator {
    private final ThreadContextScopes contexts;

    public TenantAwareTaskDecorator() {
        this(new ThreadContextScopes(List.of()));
    }

    public TenantAwareTaskDecorator(ThreadContextScopes contexts) {
        this.contexts = contexts;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        MetaContext.Snapshot snapshot = MetaContext.snapshot();
        var captured = contexts.capture();
        if (snapshot == null && captured.isEmpty()) {
            return runnable;
        }
        return () -> MetaContext.runWithSnapshot(snapshot, () -> contexts.runWith(captured, runnable));
    }
}
