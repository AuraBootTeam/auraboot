package com.auraboot.framework.meta.event;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.PageSchemaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test verifying that SchemaPublishedEvent is emitted when a page schema is published.
 */
class SchemaPublishedEventIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PageSchemaService pageSchemaService;

    @Autowired
    private PageSchemaMapper pageSchemaMapper;

    @Autowired
    private TestSchemaPublishedEventListener eventListener;

    @Test
    void publish_emitsSchemaPublishedEvent() {
        // Arrange: create a draft schema
        String suffix = String.valueOf(System.currentTimeMillis());
        String pageKey = "event_test_" + suffix;
        String pid = UniqueIdGenerator.generate();

        pageSchemaMapper.insertForPluginImport(
                pid,
                getTestTenant().getId(),
                "draft",
                pageKey,
                "event_model_" + suffix,
                "Event Test Page " + pageKey,
                "\"Event Test Title\"",
                "Event test description",
                "list",
                "default",
                "{}",
                "{\"blocks\":[]}",
                3,
                false,
                null,
                null,
                0,
                "{}",
                null
        );

        eventListener.clear();

        // Act: publish the schema
        pageSchemaService.publish(pid);

        // Assert: event was published with correct pageKey and version
        assertThat(eventListener.getEvents()).isNotEmpty();
        SchemaPublishedEvent event = eventListener.getEvents().stream()
                .filter(e -> pageKey.equals(e.getPageKey()))
                .findFirst()
                .orElse(null);
        assertThat(event).isNotNull();
        assertThat(event.getPageKey()).isEqualTo(pageKey);
        assertThat(event.getVersion()).isEqualTo(3);
    }

    /**
     * Test listener that captures SchemaPublishedEvent instances.
     */
    @Component
    static class TestSchemaPublishedEventListener {

        private final CopyOnWriteArrayList<SchemaPublishedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void onSchemaPublished(SchemaPublishedEvent event) {
            events.add(event);
        }

        public CopyOnWriteArrayList<SchemaPublishedEvent> getEvents() {
            return events;
        }

        public void clear() {
            events.clear();
        }
    }
}
