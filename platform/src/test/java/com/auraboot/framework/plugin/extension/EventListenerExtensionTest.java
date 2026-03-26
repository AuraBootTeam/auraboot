package com.auraboot.framework.plugin.extension;

import com.auraboot.framework.plugin.extension.EventListenerExtension.EventContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EventListenerExtension} extension point.
 *
 * Validates wildcard pattern matching, ordering, async configuration,
 * and exception isolation for event listeners.
 *
 * Test IDs: C4-07 through C4-13
 *
 * @author AuraBoot Team
 */
@DisplayName("EventListenerExtension Unit Tests")
class EventListenerExtensionTest {

    // ── Inner test implementations ────────────────────────────────────────

    /**
     * Listener that subscribes to a prefix wildcard pattern like "asset:*".
     */
    static class PrefixWildcardListener implements EventListenerExtension {

        private final Set<String> patterns;
        private final List<String> receivedEvents = new ArrayList<>();

        PrefixWildcardListener(String... patterns) {
            this.patterns = Set.of(patterns);
        }

        @Override
        public Set<String> getSubscribedEvents() {
            return patterns;
        }

        @Override
        public void onEvent(EventContext context) {
            receivedEvents.add(context.eventType());
        }

        List<String> getReceivedEvents() {
            return receivedEvents;
        }
    }

    /**
     * Listener with configurable order and optional async behavior.
     */
    static class OrderedListener implements EventListenerExtension {

        private final String name;
        private final int order;
        private final boolean async;
        private final List<String> receivedEvents = new ArrayList<>();

        OrderedListener(String name, int order) {
            this(name, order, false);
        }

        OrderedListener(String name, int order, boolean async) {
            this.name = name;
            this.order = order;
            this.async = async;
        }

        @Override
        public Set<String> getSubscribedEvents() {
            return Set.of("*");
        }

        @Override
        public void onEvent(EventContext context) {
            receivedEvents.add(context.eventType());
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public boolean isAsync() {
            return async;
        }

        String getName() {
            return name;
        }
    }

    /**
     * Listener that throws an exception on every event.
     */
    static class FailingListener implements EventListenerExtension {

        @Override
        public Set<String> getSubscribedEvents() {
            return Set.of("*");
        }

        @Override
        public void onEvent(EventContext context) {
            throw new RuntimeException("Listener failed on: " + context.eventType());
        }
    }

    /**
     * Listener that subscribes to exact event types only.
     */
    static class ExactMatchListener implements EventListenerExtension {

        private final Set<String> events;

        ExactMatchListener(String... events) {
            this.events = Set.of(events);
        }

        @Override
        public Set<String> getSubscribedEvents() {
            return events;
        }

        @Override
        public void onEvent(EventContext context) {
            // no-op
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("C4-07: Prefix wildcard 'asset:*' matches 'asset:created'")
    void prefixWildcard_assetStar_shouldMatchAssetCreated() {
        // Arrange
        var listener = new PrefixWildcardListener("asset:*");

        // Act & Assert
        assertTrue(listener.isInterestedIn("asset:created"));
        assertTrue(listener.isInterestedIn("asset:updated"));
        assertTrue(listener.isInterestedIn("asset:deleted"));
        assertFalse(listener.isInterestedIn("order:created"));
        assertFalse(listener.isInterestedIn("user:login"));
    }

    @Test
    @DisplayName("C4-08: Suffix wildcard '*:created' matches 'order:created'")
    void suffixWildcard_starCreated_shouldMatchOrderCreated() {
        // Arrange
        var listener = new PrefixWildcardListener("*:created");

        // Act & Assert
        assertTrue(listener.isInterestedIn("order:created"));
        assertTrue(listener.isInterestedIn("asset:created"));
        assertTrue(listener.isInterestedIn("user:created"));
        assertFalse(listener.isInterestedIn("order:updated"));
        assertFalse(listener.isInterestedIn("order:deleted"));
    }

    @Test
    @DisplayName("C4-09: Global wildcard '*' matches any event type")
    void globalWildcard_star_shouldMatchAnyEvent() {
        // Arrange
        var listener = new PrefixWildcardListener("*");

        // Act & Assert
        assertTrue(listener.isInterestedIn("asset:created"));
        assertTrue(listener.isInterestedIn("order:completed"));
        assertTrue(listener.isInterestedIn("user:login"));
        assertTrue(listener.isInterestedIn("anything:at:all"));
        assertTrue(listener.isInterestedIn("single"));
    }

    @Test
    @DisplayName("C4-10: Exact match only matches the target event")
    void exactMatch_shouldOnlyMatchTargetEvent() {
        // Arrange
        var listener = new ExactMatchListener("order:created");

        // Act & Assert
        assertTrue(listener.isInterestedIn("order:created"));
        assertFalse(listener.isInterestedIn("order:updated"));
        assertFalse(listener.isInterestedIn("order:deleted"));
        assertFalse(listener.isInterestedIn("asset:created"));
    }

    @Test
    @DisplayName("C4-11: Listeners sort correctly by getOrder()")
    void getOrder_sorting_shouldPreservePriorityOrder() {
        // Arrange
        var listenerA = new OrderedListener("late", 200);
        var listenerB = new OrderedListener("early", 10);
        var listenerC = new OrderedListener("default", 100);

        List<EventListenerExtension> listeners = List.of(listenerA, listenerB, listenerC);

        // Act - sort by order ascending (lower values first)
        List<EventListenerExtension> sorted = listeners.stream()
                .sorted(Comparator.comparingInt(EventListenerExtension::getOrder))
                .toList();

        // Assert
        assertEquals(10, sorted.get(0).getOrder());
        assertEquals(100, sorted.get(1).getOrder());
        assertEquals(200, sorted.get(2).getOrder());
        assertEquals("early", ((OrderedListener) sorted.get(0)).getName());
        assertEquals("default", ((OrderedListener) sorted.get(1)).getName());
        assertEquals("late", ((OrderedListener) sorted.get(2)).getName());
    }

    @Test
    @DisplayName("C4-12: isAsync() defaults to false, can be overridden to true")
    void isAsync_defaultAndOverride() {
        // Arrange
        var syncListener = new OrderedListener("sync", 100, false);
        var asyncListener = new OrderedListener("async", 100, true);

        // Default behavior via exact match listener (uses interface default)
        var defaultListener = new ExactMatchListener("test:event");

        // Act & Assert
        assertFalse(syncListener.isAsync(), "Explicit false should be false");
        assertTrue(asyncListener.isAsync(), "Override to true should be true");
        assertFalse(defaultListener.isAsync(), "Interface default should be false");
    }

    @Test
    @DisplayName("C4-13: Exception in one listener does not affect others")
    void exceptionIsolation_failingListener_shouldNotAffectOthers() {
        // Arrange
        var successfulBefore = new PrefixWildcardListener("*");
        var failingListener = new FailingListener();
        var successfulAfter = new PrefixWildcardListener("*");

        List<EventListenerExtension> listeners = List.of(
                successfulBefore, failingListener, successfulAfter
        );

        var context = EventContext.builder()
                .tenantId(1001L)
                .pluginId("test-plugin")
                .namespace("test")
                .eventType("order:created")
                .sourceModel("order")
                .recordId("ord-001")
                .eventData(Map.of("amount", 100.0))
                .previousData(Map.of())
                .timestamp(System.currentTimeMillis())
                .build();

        // Act - simulate dispatching to all listeners with exception isolation
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (EventListenerExtension listener : listeners) {
            try {
                if (listener.isInterestedIn(context.eventType())) {
                    listener.onEvent(context);
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                errorCount.incrementAndGet();
                // Exception is caught and does not propagate
            }
        }

        // Assert
        assertEquals(2, successCount.get(), "Two listeners should have succeeded");
        assertEquals(1, errorCount.get(), "One listener should have failed");
        assertEquals(1, successfulBefore.getReceivedEvents().size(),
                "First listener should have received the event");
        assertEquals(1, successfulAfter.getReceivedEvents().size(),
                "Third listener should have received the event despite second failing");
        assertEquals("order:created", successfulBefore.getReceivedEvents().get(0));
        assertEquals("order:created", successfulAfter.getReceivedEvents().get(0));
    }
}
