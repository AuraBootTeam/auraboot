package com.auraboot.framework.permission.listener;

import com.auraboot.framework.permission.event.RolePermissionChangedEvent;
import com.auraboot.framework.permission.event.SubjectPermissionChangedEvent;
import com.auraboot.framework.permission.event.UserRoleChangedEvent;
import com.auraboot.framework.permission.service.impl.UserPermissionServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract guards for the permission-cache eviction pipeline (DDR-2026-06-29 §12):
 *
 * <ol>
 *   <li>Eviction listeners must run AFTER COMMIT — a pre-commit evict lets a concurrent
 *       request re-cache the pre-change permission set for a full TTL (30min), which for
 *       revokes means the removed permission keeps working.</li>
 *   <li>{@code fallbackExecution=true} keeps eviction working for non-transactional
 *       publishers.</li>
 *   <li>{@code getUserPermissionIds} must not cache empty results: a request with a
 *       missing tenant/member context resolves to an empty set, and caching it locks the
 *       user out of everything until the TTL expires.</li>
 * </ol>
 */
@DisplayName("Permission cache eviction contract")
class PermissionCacheEvictionContractTest {

    private Method listenerFor(Class<?> eventType) {
        return Arrays.stream(PermissionCacheEvictionListener.class.getDeclaredMethods())
            .filter(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0] == eventType)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no listener method for " + eventType.getSimpleName()));
    }

    @Test
    @DisplayName("eviction listeners run AFTER_COMMIT with fallbackExecution")
    void listenersAreAfterCommit() {
        for (Class<?> eventType : new Class<?>[] {
            RolePermissionChangedEvent.class,
            UserRoleChangedEvent.class,
            SubjectPermissionChangedEvent.class,
        }) {
            Method method = listenerFor(eventType);
            TransactionalEventListener annotation =
                method.getAnnotation(TransactionalEventListener.class);
            assertNotNull(annotation,
                method.getName() + " must use @TransactionalEventListener (AFTER_COMMIT)");
            assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase(),
                method.getName() + " must evict after commit");
            assertTrue(annotation.fallbackExecution(),
                method.getName() + " must still run for non-transactional publishers");
        }
    }

    @Test
    @DisplayName("getUserPermissionIds does not cache empty permission sets")
    void emptyPermissionSetsAreNotCached() throws Exception {
        Method method = UserPermissionServiceImpl.class.getMethod("getUserPermissionIds", Long.class);
        Cacheable cacheable = method.getAnnotation(Cacheable.class);
        assertNotNull(cacheable, "getUserPermissionIds must stay cacheable");
        assertEquals("#result.isEmpty()", cacheable.unless(),
            "empty results (e.g. missing tenant/member context) must not poison the cache");
    }
}
