package com.auraboot.framework.plugin.extension.integration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationEventEnvelopeTest {

    @Test
    void freezesVersionedProcurementInventoryContract() {
        IntegrationEventEnvelope envelope = new IntegrationEventEnvelope(
                IntegrationEventEnvelope.VERSION_1,
                "evt-001",
                "procurement.purchase-order.receipt-requested.v1",
                "procurement",
                "purchase-order/PO-001",
                Instant.parse("2026-08-23T12:00:00Z"),
                42L,
                "corr-001",
                "cmd-001",
                "purchase-order/PO-001",
                7L,
                Map.of("purchaseOrderPid", "PO-001", "receiptRequestPid", "RR-001"),
                Map.of("contentType", "application/json")
        );

        assertThat(envelope.schemaVersion()).isEqualTo("1.0");
        assertThat(envelope.eventType()).endsWith(".v1");
        assertThat(envelope.payload()).containsEntry("receiptRequestPid", "RR-001");
        assertThatThrownBy(() -> envelope.payload().put("owner", "inventory"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsUnversionedOrUnscopedIdentityFields() {
        assertThatThrownBy(() -> new IntegrationEventEnvelope(
                "1.0", "evt-001", "", "procurement", "purchase-order/PO-001",
                Instant.EPOCH, 0L, "corr-001", null, "purchase-order/PO-001", -1L,
                Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
