package com.auraboot.framework.plugin.extension.integration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationEventEnvelopeTest {

    private static final String T04_RELEASE_V2_SHA256 =
            "e66272dce5cf36f64e1d8df70584c98ffdaf3af76cc553857d431ea59711ccdf";
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    @Test
    void loadsExactT04FixtureWithJsonNullAndTakesADeepImmutableSnapshot() throws Exception {
        byte[] fixtureBytes;
        try (InputStream input = getClass().getResourceAsStream(
                "/contracts/amos-t04-2280ce79/release-v2.json")) {
            assertThat(input).as("T04 PR #296 exact fixture resource").isNotNull();
            fixtureBytes = input.readAllBytes();
        }
        assertThat(sha256(fixtureBytes)).isEqualTo(T04_RELEASE_V2_SHA256);

        Map<String, Object> fixture = objectMapper.readValue(
                new String(fixtureBytes, StandardCharsets.UTF_8), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callerIdentities =
                (List<Map<String, Object>>) fixture.get("identities");
        Map<String, String> callerHeaders = new LinkedHashMap<>();
        callerHeaders.put("optionalTrace", null);

        IntegrationEventEnvelope envelope = new IntegrationEventEnvelope(
                IntegrationEventEnvelope.VERSION_1,
                fixture.get("eventId").toString(),
                fixture.get("eventType").toString(),
                "quality",
                "inspection/" + fixture.get("inspectionId"),
                Instant.parse(fixture.get("occurredAt").toString()),
                Long.parseLong(fixture.get("tenantId").toString()),
                fixture.get("decisionId").toString(),
                fixture.get("sourceFactId").toString(),
                "inspection/" + fixture.get("inspectionId"),
                ((Number) fixture.get("sourceSequence")).longValue(),
                fixture,
                callerHeaders);

        JsonNode envelopePayload = objectMapper.valueToTree(envelope.payload());
        assertThat(envelopePayload).isEqualTo(objectMapper.readTree(fixtureBytes));

        fixture.put("specificationVersion", "caller-mutated");
        callerIdentities.getFirst().put("value", "caller-mutated");
        callerIdentities.add(Map.of("type", "LOT", "value", "caller-added"));
        callerHeaders.put("optionalTrace", "caller-mutated");

        assertThat(envelope.payload()).containsEntry("specificationVersion", null);
        assertThat(envelope.headers()).containsEntry("optionalTrace", null);
        assertThat(identities(envelope)).hasSize(3);
        assertThat(firstIdentity(envelope)).containsEntry("value", "LOT-20260823-A");
        assertThatThrownBy(() -> envelope.payload().put("newField", null))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> firstIdentity(envelope).put("value", "envelope-mutated"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> identities(envelope).add(Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> identities(IntegrationEventEnvelope envelope) {
        return (List<Map<String, Object>>) envelope.payload().get("identities");
    }

    private static Map<String, Object> firstIdentity(IntegrationEventEnvelope envelope) {
        return identities(envelope).getFirst();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
