package com.auraboot.framework.saas.executor;

import com.auraboot.framework.application.tenant.MetaContext;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

@Slf4j
public final class SystemTenantContextExecutor {

    public static final Long SYSTEM_TENANT_ID = 1L;

    private SystemTenantContextExecutor() {}

    public static <T> T executeAsSystem(Supplier<T> block) {
        Long previousTenantId = null;
        Long previousUserId = null;
        String previousUserPid = null;
        String previousUsername = null;
        boolean hadContext = MetaContext.exists();

        if (hadContext) {
            previousTenantId = MetaContext.getCurrentTenantId();
            previousUserId = MetaContext.getCurrentUserId();
            previousUserPid = MetaContext.getCurrentUserPid();
            previousUsername = MetaContext.getCurrentUsername();
        }

        MetaContext.setContext(SYSTEM_TENANT_ID, null, null, "system");
        try {
            log.debug("Executing in system tenant context, caller: {}",
                Thread.currentThread().getStackTrace()[2].getClassName());
            return block.get();
        } finally {
            MetaContext.clear();
            if (hadContext) {
                MetaContext.setContext(previousTenantId, previousUserId,
                    previousUserPid, previousUsername);
            }
        }
    }

    public static void runAsSystem(Runnable block) {
        executeAsSystem(() -> {
            block.run();
            return null;
        });
    }
}
