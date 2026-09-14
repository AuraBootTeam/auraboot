package com.auraboot.framework.openplatform.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiEventCatalogTest {
    private final OpenApiEventCatalog catalog = new OpenApiEventCatalog();

    @Test
    void exposesOnlyExternallyClassifiedVersionedEvents() {
        assertThat(catalog.externallyPublished()).extracting(OpenApiEventCatalog.EventDescriptor::type)
                .containsExactly("assets.assignment.changed", "inventory.stock-in.confirmed")
                .doesNotContain("platform.command.executed");
        assertThatThrownBy(() -> catalog.requireExternal("platform.command.executed", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.requireExternal("assets.assignment.changed", 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("version");
    }

    @Test
    void envelopeProjectsOnlyAllowListedPublicFields() {
        var descriptor = catalog.requireExternal("assets.assignment.changed", 1);
        Map<String, Object> envelope = catalog.envelope(descriptor, 1, "asset-1", Map.of(
                "pid", "asset-1", "assetCode", "A-1", "status", "in_use",
                "internalNote", "secret"));
        assertThat(envelope).containsEntry("type", "assets.assignment.changed")
                .containsEntry("schemaVersion", 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertThat(data)
                .containsEntry("assetCode", "A-1").doesNotContainKey("internalNote");
    }
}
