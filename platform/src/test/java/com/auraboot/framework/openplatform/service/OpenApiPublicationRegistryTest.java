package com.auraboot.framework.openplatform.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenApiPublicationRegistryTest {
    private final OpenApiPublicationRegistry registry = new OpenApiPublicationRegistry();

    @Test
    void exposesStableAliasesWithoutLeakingUnpublishedDslFields() {
        var publication = registry.resource("assets").orElseThrow();
        assertThat(publication.modelCode()).isEqualTo("tasset_asset");
        Map<String, Object> projected = registry.project(publication, Map.of(
                "pid", "asset-1", "tasset_as_code", "A-001", "tasset_as_name", "Laptop",
                "tasset_as_notes", "private note"));

        assertThat(projected).containsEntry("pid", "asset-1").containsEntry("assetCode", "A-001")
                .containsEntry("name", "Laptop");
        assertThat(projected).doesNotContainKeys("tasset_as_code", "tasset_as_notes");
    }

    @Test
    void mapsOnlyPublishedCommandInputs() {
        var publication = registry.command("assets.assign").orElseThrow();
        assertThat(registry.mapInput(publication, Map.of("assignee", "alice")))
                .containsExactly(Map.entry("tasset_as_assigned_to", "alice"));
        assertThatThrownBy(() -> registry.mapInput(publication, Map.of("rawSql", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownResourceAndCommandRemainUnpublished() {
        assertThat(registry.resource("tasset_asset")).isEmpty();
        assertThat(registry.command("tasset:return_asset")).isEmpty();
    }

    @Test
    void publishesInventoryReceiptWithoutInternalAliases() {
        var publication = registry.resource("inventory.stock-ins").orElseThrow();
        assertThat(publication.modelCode()).isEqualTo("tinv_stock_in");
        assertThat(registry.project(publication, Map.of(
                "pid", "receipt-1", "tinv_si_code", "IN-001", "tinv_si_status", "draft",
                "tinv_si_notes", "private")))
                .containsEntry("receiptCode", "IN-001")
                .containsEntry("status", "draft")
                .doesNotContainKeys("tinv_si_code", "tinv_si_notes");
        var command = registry.command("inventory.stock-ins.confirm").orElseThrow();
        assertThat(command.commandCode()).isEqualTo("tinv:confirm_stock_in");
        assertThat(command.permission()).isEqualTo("tinv.stockin.manage");
        assertThat(registry.mapInput(command, Map.of())).isEmpty();
    }
}
