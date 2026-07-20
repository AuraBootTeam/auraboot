package com.auraboot.framework.permission.listener;

import com.auraboot.framework.permission.event.PermissionDefinitionChangedEvent;
import com.auraboot.framework.permission.event.RolePermissionChangedEvent;
import com.auraboot.framework.permission.event.UserRoleChangedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;
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
 *   <li>Permission-definition changes must evict the catalog so negative lookups do not linger.</li>
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

    /**
     * Events this listener reacts to. SubjectPermissionChangedEvent used to be here — and that
     * is the whole point of {@link #everyListenedEventIsActuallyPublished()}: its listener was
     * annotated perfectly, ran AFTER_COMMIT, evicted the right cache... and nothing in the
     * codebase ever published the event. This test suite passed the entire time. (Removed
     * 2026-07-14 together with the cache it was clearing, which nothing ever populated either.)
     */
    private static final Class<?>[] LISTENED_EVENTS = {
        RolePermissionChangedEvent.class,
        UserRoleChangedEvent.class,
        PermissionDefinitionChangedEvent.class,
    };

    /**
     * A listener for an event nobody publishes is dead code that looks alive — and it looks
     * alive to tests too, which is why this one existed for so long. Assert the other half of
     * the contract: something must construct each event.
     */
    @Test
    @DisplayName("every listened event is actually published somewhere in production code")
    void everyListenedEventIsActuallyPublished() throws Exception {
        Path main = Paths.get("src/main/java");
        assertTrue(Files.isDirectory(main), "expected to run from the platform module");

        for (Class<?> eventType : LISTENED_EVENTS) {
            String ctor = "new " + eventType.getSimpleName() + "(";
            try (Stream<Path> files = Files.walk(main)) {
                boolean published = files
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !f.toString().contains("/event/"))
                    .anyMatch(f -> {
                        try {
                            return Files.readString(f).contains(ctor);
                        } catch (Exception e) {
                            return false;
                        }
                    });
                assertTrue(published,
                    eventType.getSimpleName() + " has a listener but nothing ever publishes it — "
                        + "the eviction it performs can never happen");
            }
        }
    }

    @Test
    @DisplayName("eviction listeners run AFTER_COMMIT with fallbackExecution")
    void listenersAreAfterCommit() {
        for (Class<?> eventType : LISTENED_EVENTS) {
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

}
