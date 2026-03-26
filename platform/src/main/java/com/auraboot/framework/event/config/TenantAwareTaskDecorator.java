package com.auraboot.framework.event.config;

import com.auraboot.framework.application.tenant.MetaContext;
import org.springframework.core.task.TaskDecorator;

/**
 * Propagates MetaContext (tenant, user) from the calling thread to async threads.
 * Prevents cross-tenant data leakage in @Async methods.
 *
 * @since 6.2.0
 */
public class TenantAwareTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        // Capture context from the calling thread
        if (!MetaContext.exists()) {
            return runnable;
        }

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String userPid = MetaContext.getCurrentUserPid();
        String username = MetaContext.getCurrentUsername();

        return () -> {
            MetaContext.setContext(tenantId, userId, userPid, username);
            try {
                runnable.run();
            } finally {
                MetaContext.clear();
            }
        };
    }
}
