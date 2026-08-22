package com.auraboot.plugins.crm.handler;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension.CommandContext;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IntakeCustomerRequestHandlerTest {

    private final IntakeCustomerRequestHandler handler = new IntakeCustomerRequestHandler();

    @Test
    void createsOneAuthoritativeRequestWithImmutableSourceAndFieldEvidence() {
        FakeDataAccessor db = new FakeDataAccessor();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.execute(
                context(db, payload("mailbox-42", "Motor controller RFQ"), "delivery-1"));

        assertEquals(false, result.get("idempotent"));
        assertEquals("submitted", result.get("status"));
        assertEquals(1, db.createCount);
        Map<String, Object> request = db.rows(IntakeCustomerRequestHandler.MODEL).getFirst();
        assertEquals("email", request.get("crm_cr_source_channel"));
        assertEquals("gmail.sales", request.get("crm_cr_source_system"));
        assertEquals("mailbox-42", request.get("crm_cr_source_message_ref"));
        assertEquals("2026-08-13T01:02:03Z", request.get("crm_cr_source_received_at"));
        assertEquals("Motor controller RFQ", request.get("crm_cr_title"));
        assertEquals("inquiry", request.get("crm_cr_type"));
        assertEquals("medium", request.get("crm_cr_priority"));
        assertEquals(2, request.get("crm_cr_field_evidence_count"));
        assertEquals("delivery-1", request.get("crm_cr_intake_client_request_id"));
        assertFalse(request.containsKey("crm_cr_ingested_by"));
        assertFalse(request.containsKey("crm_cr_owner"));

        String businessKey = String.valueOf(request.get("crm_cr_source_business_key"));
        String contentHash = String.valueOf(request.get("crm_cr_source_content_hash"));
        assertTrue(businessKey.matches("[a-f0-9]{64}"));
        assertTrue(contentHash.matches("[a-f0-9]{64}"));
        assertNotEquals(businessKey, contentHash);

        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) request.get("crm_cr_intake_snapshot");
        assertEquals(1, snapshot.get("schemaVersion"));
        @SuppressWarnings("unchecked")
        Map<String, Object> received = (Map<String, Object>) snapshot.get("receivedFields");
        assertEquals("Motor controller RFQ", received.get("crm_cr_title"));
        assertEquals("Need 5k units", received.get("crm_cr_summary"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence =
                (List<Map<String, Object>>) request.get("crm_cr_field_evidence");
        assertEquals(List.of("crm_cr_summary", "crm_cr_title"),
                evidence.stream().map(item -> String.valueOf(item.get("field"))).toList());
        assertTrue(evidence.stream().allMatch(item ->
                String.valueOf(item.get("valueHash")).matches("[a-f0-9]{64}")
                        && "mailbox-42".equals(item.get("sourceMessageRef"))));

        @SuppressWarnings("unchecked")
        Map<String, Object> provenance =
                (Map<String, Object>) request.get("crm_cr_source_provenance");
        assertEquals("delivery-1", provenance.get("clientRequestId"));
        assertEquals(contentHash, provenance.get("contentHash"));
        assertTrue(String.valueOf(provenance.get("ingestedAt")).contains("T"));
        assertFalse(provenance.containsKey("ingestedBy"));
    }

    @Test
    void exactBusinessReplayReturnsExistingRequestWithoutAnotherWrite() {
        FakeDataAccessor db = new FakeDataAccessor();
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) handler.execute(
                context(db, payload("mailbox-42", "Motor controller RFQ"), "delivery-1"));

        Map<String, Object> reordered = payload("mailbox-42", "Motor controller RFQ");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> evidence =
                (List<Map<String, Object>>) reordered.get("crm_cr_field_evidence");
        reordered.put("crm_cr_field_evidence", List.of(evidence.get(1), evidence.get(0)));
        @SuppressWarnings("unchecked")
        Map<String, Object> replay = (Map<String, Object>) handler.execute(
                context(db, reordered, "delivery-2"));

        assertEquals(true, replay.get("idempotent"));
        assertEquals(first.get("customerRequestId"), replay.get("customerRequestId"));
        assertEquals(first.get("contentHash"), replay.get("contentHash"));
        assertEquals(1, db.createCount);
    }

    @Test
    void theSameExternalMessageHasAnIndependentBusinessFencePerTenant() {
        FakeDataAccessor firstTenant = new FakeDataAccessor();
        FakeDataAccessor secondTenant = new FakeDataAccessor();

        handler.execute(context(firstTenant, payload("mailbox-42", "Motor controller RFQ"),
                "delivery-1", 101L));
        handler.execute(context(secondTenant, payload("mailbox-42", "Motor controller RFQ"),
                "delivery-1", 202L));

        assertNotEquals(
                firstTenant.rows(IntakeCustomerRequestHandler.MODEL).getFirst()
                        .get("crm_cr_source_business_key"),
                secondTenant.rows(IntakeCustomerRequestHandler.MODEL).getFirst()
                        .get("crm_cr_source_business_key"));
        assertEquals(1, firstTenant.createCount);
        assertEquals(1, secondTenant.createCount);
    }

    @Test
    void dryRunValidatesTheEnvelopeWithoutWriting() {
        FakeDataAccessor db = new FakeDataAccessor();
        CommandContext dryRun = CommandContext.builder()
                .tenantId(101L)
                .pluginId("com.auraboot.crm")
                .namespace("crm")
                .commandType(IntakeCustomerRequestHandler.COMMAND_TYPE)
                .modelCode(IntakeCustomerRequestHandler.MODEL)
                .payload(payload("mailbox-dry-run", "Dry-run motor controller RFQ"))
                .settings(Map.of(
                        "__dataAccessor", db,
                        "__currentUser", "501",
                        "__clientRequestId", "delivery-dry-run"))
                .dryRun(true)
                .build();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.execute(dryRun);

        assertEquals(true, result.get("dryRun"));
        assertEquals(false, result.get("idempotent"));
        assertEquals("intake_validated", result.get("status"));
        assertFalse(result.containsKey("customerRequestId"));
        assertEquals(0, db.createCount);
    }

    @Test
    void changedContentUnderTheSameSourceIdentityFailsClosed() {
        FakeDataAccessor db = new FakeDataAccessor();
        handler.execute(context(db, payload("mailbox-42", "Motor controller RFQ"), "delivery-1"));

        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> handler.execute(context(db,
                        payload("mailbox-42", "Changed motor controller RFQ"), "delivery-2")));

        assertTrue(conflict.getMessage().contains("changed"));
        assertEquals(1, db.createCount);
        assertEquals("Motor controller RFQ",
                db.rows(IntakeCustomerRequestHandler.MODEL).getFirst().get("crm_cr_title"));
    }

    @Test
    void rejectsMissingOrUntrustedSourceAndEvidenceFacts() {
        FakeDataAccessor db = new FakeDataAccessor();

        Map<String, Object> noEvidence = payload("mailbox-42", "Motor controller RFQ");
        noEvidence.remove("crm_cr_field_evidence");
        assertThrows(IllegalArgumentException.class,
                () -> handler.execute(context(db, noEvidence, "delivery-1")));

        Map<String, Object> incomplete = payload("mailbox-42", "Motor controller RFQ");
        incomplete.put("crm_cr_field_evidence", List.of(evidence("crm_cr_title", "subject")));
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> handler.execute(context(db, incomplete, "delivery-1")));
        assertTrue(missing.getMessage().contains("crm_cr_summary"));

        Map<String, Object> unsupportedChannel = payload("mailbox-42", "Motor controller RFQ");
        unsupportedChannel.put("crm_cr_source_channel", "visit");
        assertThrows(IllegalArgumentException.class,
                () -> handler.execute(context(db, unsupportedChannel, "delivery-1")));

        Map<String, Object> spoofed = payload("mailbox-42", "Motor controller RFQ");
        spoofed.put("crm_cr_status", "qualified");
        IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                () -> handler.execute(context(db, spoofed, "delivery-1")));
        assertTrue(rejected.getMessage().contains("server-owned"));

        assertEquals(0, db.createCount);
    }

    @Test
    void requiresTenantActorAndCommandBoundaryIdempotencyIdentity() {
        FakeDataAccessor db = new FakeDataAccessor();
        Map<String, Object> payload = payload("mailbox-42", "Motor controller RFQ");

        CommandContext noClientKey = CommandContext.builder()
                .tenantId(101L)
                .commandType(IntakeCustomerRequestHandler.COMMAND_TYPE)
                .modelCode(IntakeCustomerRequestHandler.MODEL)
                .payload(payload)
                .settings(Map.of("__dataAccessor", db, "__currentUser", "501"))
                .build();
        assertThrows(IllegalArgumentException.class, () -> handler.execute(noClientKey));

        CommandContext noActor = CommandContext.builder()
                .tenantId(101L)
                .commandType(IntakeCustomerRequestHandler.COMMAND_TYPE)
                .modelCode(IntakeCustomerRequestHandler.MODEL)
                .payload(payload)
                .settings(Map.of(
                        "__dataAccessor", db,
                        "__clientRequestId", "delivery-1"))
                .build();
        assertThrows(IllegalArgumentException.class, () -> handler.execute(noActor));

        CommandContext noTenant = CommandContext.builder()
                .tenantId(null)
                .commandType(IntakeCustomerRequestHandler.COMMAND_TYPE)
                .modelCode(IntakeCustomerRequestHandler.MODEL)
                .payload(payload)
                .settings(Map.of(
                        "__dataAccessor", db,
                        "__currentUser", "501",
                        "__clientRequestId", "delivery-1"))
                .build();
        assertThrows(IllegalStateException.class, () -> handler.execute(noTenant));
        assertEquals(0, db.createCount);
    }

    private static CommandContext context(
            FakeDataAccessor db, Map<String, Object> payload, String clientRequestId) {
        return context(db, payload, clientRequestId, 101L);
    }

    private static CommandContext context(
            FakeDataAccessor db,
            Map<String, Object> payload,
            String clientRequestId,
            Long tenantId) {
        return CommandContext.builder()
                .tenantId(tenantId)
                .pluginId("com.auraboot.crm")
                .namespace("crm")
                .commandType(IntakeCustomerRequestHandler.COMMAND_TYPE)
                .modelCode(IntakeCustomerRequestHandler.MODEL)
                .payload(payload)
                .settings(Map.of(
                        "__dataAccessor", db,
                        "__currentUser", "501",
                        "__clientRequestId", clientRequestId))
                .dryRun(false)
                .build();
    }

    private static Map<String, Object> payload(String messageRef, String title) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("crm_cr_source_channel", "email");
        payload.put("crm_cr_source_system", "GMAIL.SALES");
        payload.put("crm_cr_source_message_ref", messageRef);
        payload.put("crm_cr_source_received_at", "2026-08-13T09:02:03+08:00");
        payload.put("crm_cr_title", title);
        payload.put("crm_cr_summary", "Need 5k units");
        payload.put("crm_cr_field_evidence", List.of(
                evidence("crm_cr_title", "headers.subject"),
                evidence("crm_cr_summary", "mime.text/plain[0]")));
        return payload;
    }

    private static Map<String, Object> evidence(String field, String locator) {
        return Map.of(
                "field", field,
                "locator", locator,
                "evidenceRef", "archive://mailbox-42");
    }

    private static final class FakeDataAccessor implements DataAccessor {
        private final Map<String, List<Map<String, Object>>> store = new HashMap<>();
        private int sequence;
        private int createCount;

        List<Map<String, Object>> rows(String model) {
            return store.getOrDefault(model, List.of());
        }

        @Override
        public Map<String, Object> getById(String modelCode, String recordId) {
            return rows(modelCode).stream()
                    .filter(row -> recordId.equals(row.get("pid")))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<Map<String, Object>> query(String modelCode, Map<String, Object> filters) {
            return rows(modelCode).stream()
                    .filter(row -> filters.entrySet().stream()
                            .allMatch(entry -> entry.getValue().equals(row.get(entry.getKey()))))
                    .toList();
        }

        @Override
        public Map<String, Object> create(String modelCode, Map<String, Object> data) {
            Map<String, Object> record = new LinkedHashMap<>(data);
            record.put("pid", "request-" + (++sequence));
            store.computeIfAbsent(modelCode, ignored -> new ArrayList<>()).add(record);
            createCount += 1;
            return record;
        }

        @Override
        public Map<String, Object> update(
                String modelCode, String recordId, Map<String, Object> data) {
            Map<String, Object> record = getById(modelCode, recordId);
            if (record != null) record.putAll(data);
            return record;
        }

        @Override
        public List<Map<String, Object>> batchCreate(
                String modelCode, List<Map<String, Object>> dataList) {
            return dataList.stream().map(data -> create(modelCode, data)).toList();
        }

        @Override
        public void delete(String modelCode, String recordId) {
            rows(modelCode).removeIf(row -> recordId.equals(row.get("pid")));
        }
    }
}
