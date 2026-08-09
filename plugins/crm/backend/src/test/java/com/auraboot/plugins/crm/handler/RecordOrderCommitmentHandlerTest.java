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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordOrderCommitmentHandlerTest {

    private final RecordOrderCommitmentHandler handler = new RecordOrderCommitmentHandler();

    @Test
    void recordsOneAuthoritativeCommitmentFromTheExactReleasedEvidenceChain() {
        FakeDataAccessor db = eligibleChain();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.execute(context(db, false));

        assertEquals("ordered", result.get("status"));
        Map<String, Object> quote = db.getById("crm_quote_summary_common", "quote-1");
        assertEquals("ordered", quote.get("crm_qs_status"));
        assertEquals("qdp-1", quote.get("crm_qs_committed_qdp_revision_id"));
        assertEquals("confirmation-1", quote.get("crm_qs_customer_confirmation_id"));
        assertEquals("sales-manager-1", quote.get("crm_qs_order_committed_by"));
        assertTrue(String.valueOf(quote.get("crm_qs_order_committed_at")).contains("T"));
        assertEquals(1, db.updateCount);
    }

    @Test
    void dryRunValidatesWithoutWriting() {
        FakeDataAccessor db = eligibleChain();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.execute(context(db, true));

        assertEquals(true, result.get("dryRun"));
        assertEquals("order_commitment_validated", result.get("status"));
        assertEquals("accepted", db.getById("crm_quote_summary_common", "quote-1")
                .get("crm_qs_status"));
        assertEquals(0, db.updateCount);
    }

    @Test
    void rejectsTargetSubstitutionStaleVersionAndAlreadyCommittedQuotes() {
        FakeDataAccessor db = eligibleChain();
        Map<String, Object> wrongTarget = new HashMap<>(payload());
        wrongTarget.put("crm_quote_summary_id", "quote-2");
        IllegalArgumentException target = assertThrows(IllegalArgumentException.class,
                () -> handler.execute(context(db, wrongTarget, 4L, false)));
        assertTrue(target.getMessage().contains("does not match"));

        IllegalStateException stale = assertThrows(IllegalStateException.class,
                () -> handler.execute(context(db, payload(), 3L, false)));
        assertTrue(stale.getMessage().contains("stale"));

        db.getById("crm_quote_summary_common", "quote-1")
                .put("crm_qs_committed_qdp_revision_id", "qdp-old");
        IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                () -> handler.execute(context(db, false)));
        assertTrue(duplicate.getMessage().contains("already has"));
        assertEquals(0, db.updateCount);
    }

    @Test
    void rejectsEveryIllegalQuoteBusinessState() {
        for (Map.Entry<String, String> invalid : Map.of(
                "crm_qs_status", "sent",
                "crm_qs_customer_feedback_status", "replied",
                "crm_qs_won_lost_result", "open",
                "crm_qs_approval_status", "pending").entrySet()) {
            FakeDataAccessor db = eligibleChain();
            db.getById("crm_quote_summary_common", "quote-1")
                    .put(invalid.getKey(), invalid.getValue());
            assertThrows(IllegalArgumentException.class, () -> handler.execute(context(db, false)),
                    invalid.getKey() + " must fail closed");
            assertEquals(0, db.updateCount);
        }
    }

    @Test
    void rejectsUnreleasedCrossRequestOrNonUniqueCurrentQdp() {
        FakeDataAccessor draft = eligibleChain();
        draft.getById("crm_qdp_revision_common", "qdp-1")
                .put("crm_qdp_status", "ready_for_review");
        assertThrows(IllegalArgumentException.class, () -> handler.execute(context(draft, false)));

        FakeDataAccessor crossRequest = eligibleChain();
        crossRequest.getById("crm_qdp_revision_common", "qdp-1")
                .put("crm_qdp_customer_request_id", "request-2");
        assertThrows(IllegalArgumentException.class,
                () -> handler.execute(context(crossRequest, false)));

        FakeDataAccessor duplicate = eligibleChain();
        Map<String, Object> qdp2 = new LinkedHashMap<>(
                duplicate.getById("crm_qdp_revision_common", "qdp-1"));
        qdp2.put("pid", "qdp-2");
        duplicate.seed("crm_qdp_revision_common", qdp2);
        IllegalStateException nonUnique = assertThrows(IllegalStateException.class,
                () -> handler.execute(context(duplicate, false)));
        assertTrue(nonUnique.getMessage().contains("exactly one"));
        assertEquals(0, duplicate.updateCount);
    }

    @Test
    void rejectsMissingOrMismatchedCustomerConfirmationEvidence() {
        FakeDataAccessor missing = eligibleChain();
        missing.remove("crm_customer_confirmation_common", "confirmation-1");
        assertThrows(IllegalArgumentException.class,
                () -> handler.execute(context(missing, false)));

        for (Map.Entry<String, String> mismatch : Map.of(
                "crm_cc_status", "revoked",
                "crm_cc_customer_request_id", "request-2",
                "crm_cc_requirement_version_id", "version-2",
                "crm_cc_file_package_id", "package-2",
                "crm_cc_file_package_hash", "hash-2").entrySet()) {
            FakeDataAccessor db = eligibleChain();
            db.getById("crm_customer_confirmation_common", "confirmation-1")
                    .put(mismatch.getKey(), mismatch.getValue());
            IllegalStateException rejected = assertThrows(IllegalStateException.class,
                    () -> handler.execute(context(db, false)), mismatch.getKey());
            assertTrue(rejected.getMessage().contains("does not match"));
            assertEquals(0, db.updateCount);
        }

        FakeDataAccessor hashMismatch = eligibleChain();
        hashMismatch.getById("crm_qdp_revision_common", "qdp-1")
                .put("crm_qdp_customer_confirmed_hash", "hash-2");
        IllegalStateException rejected = assertThrows(IllegalStateException.class,
                () -> handler.execute(context(hashMismatch, false)));
        assertTrue(rejected.getMessage().contains("hash"));
        assertFalse(hashMismatch.getById("crm_quote_summary_common", "quote-1")
                .containsKey("crm_qs_order_committed_at"));
    }

    private static CommandContext context(FakeDataAccessor db, boolean dryRun) {
        return context(db, payload(), 4L, dryRun);
    }

    private static CommandContext context(
            FakeDataAccessor db, Map<String, Object> payload, Long expectedVersion, boolean dryRun) {
        Map<String, Object> settings = new HashMap<>();
        settings.put("__dataAccessor", db);
        settings.put("__currentUser", "sales-manager-1");
        settings.put("__expectedVersion", expectedVersion);
        return CommandContext.builder()
                .tenantId(101L)
                .pluginId("com.auraboot.crm")
                .namespace("crm")
                .commandType(RecordOrderCommitmentHandler.COMMAND_TYPE)
                .modelCode("crm_quote_summary_common")
                .recordId("quote-1")
                .payload(payload)
                .settings(settings)
                .dryRun(dryRun)
                .build();
    }

    private static Map<String, Object> payload() {
        return Map.of(
                "crm_quote_summary_id", "quote-1",
                "crm_qdp_revision_id", "qdp-1");
    }

    private static FakeDataAccessor eligibleChain() {
        FakeDataAccessor db = new FakeDataAccessor();
        db.seed("crm_quote_summary_common", row(
                "pid", "quote-1",
                "row_version", 4L,
                "crm_qs_status", "accepted",
                "crm_qs_customer_feedback_status", "accepted",
                "crm_qs_won_lost_result", "won",
                "crm_qs_approval_status", "approved",
                "crm_qs_customer_request_id", "request-1"));
        db.seed("crm_qdp_revision_common", row(
                "pid", "qdp-1",
                "crm_qdp_status", "released",
                "crm_qdp_gate_verdict", "ready",
                "crm_qdp_customer_request_id", "request-1",
                "crm_qdp_requirement_version_id", "version-1",
                "crm_qdp_file_package_id", "package-1",
                "crm_qdp_file_package_hash", "hash-1",
                "crm_qdp_customer_confirmation_id", "confirmation-1",
                "crm_qdp_customer_confirmed_hash", "hash-1",
                "crm_qdp_released_at", "2026-08-09T10:00:00Z",
                "crm_qdp_released_by", "release-manager-1"));
        db.seed("crm_customer_confirmation_common", row(
                "pid", "confirmation-1",
                "crm_cc_status", "confirmed",
                "crm_cc_customer_request_id", "request-1",
                "crm_cc_requirement_version_id", "version-1",
                "crm_cc_file_package_id", "package-1",
                "crm_cc_file_package_hash", "hash-1"));
        return db;
    }

    private static Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            row.put(String.valueOf(values[index]), values[index + 1]);
        }
        return row;
    }

    private static final class FakeDataAccessor implements DataAccessor {
        private final Map<String, List<Map<String, Object>>> rows = new HashMap<>();
        private int updateCount;

        void seed(String model, Map<String, Object> record) {
            rows.computeIfAbsent(model, ignored -> new ArrayList<>()).add(record);
        }

        void remove(String model, String pid) {
            rows.getOrDefault(model, List.of()).removeIf(row -> pid.equals(row.get("pid")));
        }

        @Override
        public Map<String, Object> getById(String modelCode, String recordId) {
            return rows.getOrDefault(modelCode, List.of()).stream()
                    .filter(row -> recordId.equals(row.get("pid")))
                    .findFirst()
                    .orElse(null);
        }

        @Override
        public List<Map<String, Object>> query(String modelCode, Map<String, Object> filters) {
            return rows.getOrDefault(modelCode, List.of()).stream()
                    .filter(row -> filters.entrySet().stream()
                            .allMatch(entry -> entry.getValue().equals(row.get(entry.getKey()))))
                    .toList();
        }

        @Override
        public Map<String, Object> create(String modelCode, Map<String, Object> data) {
            Map<String, Object> created = new LinkedHashMap<>(data);
            created.putIfAbsent("pid", modelCode + "-" + (rows.size() + 1));
            seed(modelCode, created);
            return created;
        }

        @Override
        public Map<String, Object> update(
                String modelCode, String recordId, Map<String, Object> data) {
            Map<String, Object> record = getById(modelCode, recordId);
            if (record == null) return null;
            Object expected = data.get("_expectedVersion");
            if (expected != null && !expected.equals(record.get("row_version"))) {
                throw new IllegalStateException("optimistic concurrency mismatch");
            }
            data.forEach((key, value) -> {
                if (!key.startsWith("_")) record.put(key, value);
            });
            updateCount += 1;
            return record;
        }

        @Override
        public List<Map<String, Object>> batchCreate(
                String modelCode, List<Map<String, Object>> dataList) {
            return dataList.stream().map(data -> create(modelCode, data)).toList();
        }

        @Override
        public void delete(String modelCode, String recordId) {
            remove(modelCode, recordId);
        }
    }
}
