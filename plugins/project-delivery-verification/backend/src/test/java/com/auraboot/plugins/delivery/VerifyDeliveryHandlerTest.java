package com.auraboot.plugins.delivery;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Hermetic stub-driven unit tests for {@link VerifyDeliveryHandler} (testing-layering §2.1 layering).
 *
 * <p>The DataAccessor collaborator is a controlled Mockito stub — no in-memory fake DB.
 * Decision assertions (D) are stub-driven: given the stored records the stub returns, the
 * handler must compute exactly the snapshot asserted below. Write assertions (W) read back
 * the persisted payload through ArgumentCaptor/verify on {@code update}. Persistence
 * semantics a stub cannot prove (tenant scoping, jsonb round-trip, transactional rollback)
 * are not asserted here.
 * owed: real-stack CAS/unique-key coverage per testing-layering §2.1 (registry owed-auraboot)
 */
class VerifyDeliveryHandlerTest {
    private final VerifyDeliveryHandler handler = new VerifyDeliveryHandler(
            Clock.fixed(Instant.parse("2026-10-16T08:00:00Z"), ZoneOffset.UTC));
    private Map<String, Object> target, acceptance, project;
    private List<Map<String, Object>> accepted;
    private DataAccessor db;

    @Test void declaresCoherentSourceReadTransaction() {
        assertEquals(com.auraboot.framework.plugin.extension.BusinessResourceAuthorization.ReadIsolation.REPEATABLE_READ,
                handler.commandReadIsolation());
        assertTrue(handler.supportsDryRun());
    }

    @BeforeEach void setup() {
        target = new HashMap<>(Map.of("pid", "check", "tdv_ck_acceptance", "acceptance"));
        acceptance = new HashMap<>(Map.of("pid", "acceptance", "tpm_ac_project", "project",
                "tpm_ac_amount", "68000.00", "tpm_ac_scope", "Migration and training",
                "tpm_ac_files", List.of(Map.of("fileId", "uploaded-evidence")), "tpm_ac_status", "draft"));
        project = new HashMap<>(Map.of("pid", "project", "tpm_pj_budget", "100000.00"));
        accepted = new ArrayList<>();
        db = mock(DataAccessor.class);
        // Read decisions: the stub decides which stored records the handler sees.
        when(db.getById("tdv_check", "check")).thenReturn(target);
        when(db.getById("tpm_acceptance", "acceptance")).thenReturn(acceptance);
        when(db.getById("tpm_project", "project")).thenReturn(project);
        when(db.query("tpm_acceptance",
                Map.of("tpm_ac_project", "project", "tpm_ac_status", "accepted"))).thenReturn(accepted);
    }
    private CommandHandlerExtension.CommandContext context(boolean dryRun) {
        return new CommandHandlerExtension.CommandContext(7L, "com.auraboot.project-delivery-verification",
                "tdv", "tdv:verify_delivery", "tdv_check", "check",
                Map.of("tdv_ck_status", "passed", "tpm_ac_amount", 1),
                Map.of(CommandHandlerExtension.DATA_ACCESSOR_KEY, db), dryRun);
    }
    private void run() { handler.execute(context(false)); }

    /** Runs once and reads back the single snapshot payload written through db.update. */
    private Map<String, Object> runAndCaptureUpdate() {
        run();
        ArgumentCaptor<Map<String, Object>> snapshot = ArgumentCaptor.forClass(Map.class);
        verify(db).update(eq("tdv_check"), eq("check"), snapshot.capture());
        return snapshot.getValue();
    }
    /** Runs first, then reads back the {@code expectedWrites} snapshot payloads in call order. */
    private List<Map<String, Object>> captureUpdates(int expectedWrites) {
        ArgumentCaptor<Map<String, Object>> snapshots = ArgumentCaptor.forClass(Map.class);
        verify(db, times(expectedWrites)).update(eq("tdv_check"), eq("check"), snapshots.capture());
        return snapshots.getAllValues();
    }

    @Test void computesFromStoredRecordsAndLeavesApprovalUntouched() {
        assertEquals("tdv:verify_delivery", handler.getCommandType());
        Map<String, Object> snapshot = runAndCaptureUpdate();
        assertEquals("passed", snapshot.get("tdv_ck_status"));
        assertEquals(4, snapshot.get("tdv_ck_passed"));
        assertEquals(new BigDecimal("68000.00"), snapshot.get("tdv_ck_amount"));
        assertEquals("2026-10-16T08:00:00Z", snapshot.get("tdv_ck_checked_at"));
        verify(db, never()).update(eq("tpm_acceptance"), anyString(), any());
    }
    @Test void emptyEvidenceAndScopeProduceActionableCorrectionCodes() {
        acceptance.put("tpm_ac_files", List.of()); acceptance.put("tpm_ac_scope", " ");
        Map<String, Object> snapshot = runAndCaptureUpdate();
        assertEquals("blocked", snapshot.get("tdv_ck_status"));
        assertEquals(2, snapshot.get("tdv_ck_passed"));
        assertEquals("blocked", snapshot.get("tdv_ck_materials"));
        assertEquals("blocked", snapshot.get("tdv_ck_scope"));
    }
    @Test void readsSerializedJsonbAndRejectsEmptyMalformedOrNonArrayValues() {
        acceptance.put("tpm_ac_files", "[{\"fileId\":\"uploaded-evidence\"}]"); run();
        for (Object value : List.of("[]", "bad-json", "{}", "null", "", 42)) {
            acceptance.put("tpm_ac_files", value); run();
        }
        acceptance.remove("tpm_ac_files"); run();
        List<Map<String, Object>> snapshots = captureUpdates(8);
        assertEquals("passed", snapshots.get(0).get("tdv_ck_materials"));
        for (int i = 1; i < snapshots.size(); i++) {
            assertEquals("blocked", snapshots.get(i).get("tdv_ck_materials"));
        }
    }
    @Test void acceptedAmountsReduceBudgetAndCurrentRecordIsNotDoubleCounted() {
        accepted.add(Map.of("pid", "other", "tpm_ac_amount", "32000.00"));
        accepted.add(acceptance); run();
        acceptance.put("tpm_ac_amount", "68000.01"); run();
        List<Map<String, Object>> snapshots = captureUpdates(2);
        assertEquals("passed", snapshots.get(0).get("tdv_ck_budget_check"));
        assertEquals(new BigDecimal("68000.00"), snapshots.get(0).get("tdv_ck_available"));
        assertEquals("blocked", snapshots.get(1).get("tdv_ck_budget_check"));
        assertEquals(3, snapshots.get(1).get("tdv_ck_passed"));
    }
    @Test void missingNonpositiveOrInvalidBudgetDoesNotInventAnAllowance() {
        for (Object value : List.of("", "invalid", "0", "-1", "0.001")) {
            project.put("tpm_pj_budget", value); run();
        }
        project.remove("tpm_pj_budget"); run();
        List<Map<String, Object>> snapshots = captureUpdates(6);
        for (int i = 0; i < 5; i++) assertEquals("missing", snapshots.get(i).get("tdv_ck_budget_check"));
        assertNull(snapshots.get(5).get("tdv_ck_budget"));
        assertNull(snapshots.get(5).get("tdv_ck_available"));
    }
    @Test void invalidAmountIncludingFractionalCentsFailsClosed() {
        for (Object value : List.of("", "broken", "0", "-0.01", "1.001", "999999999999999999999")) {
            acceptance.put("tpm_ac_amount", value); run();
        }
        acceptance.remove("tpm_ac_amount"); run();
        List<Map<String, Object>> snapshots = captureUpdates(7);
        for (Map<String, Object> snapshot : snapshots) {
            assertEquals("blocked", snapshot.get("tdv_ck_amount_check"));
            assertNotEquals("passed", snapshot.get("tdv_ck_status"));
        }
    }
    // Renamed from corruptAcceptedAmountRollsBackInsteadOfUnderstatingConsumption: the unit
    // can prove the handler fails closed before any write; the transactional rollback itself
    // lives in the host bridge.
    // owed: real-stack CAS/unique-key coverage per testing-layering §2.1 (registry owed-auraboot)
    @Test void corruptAcceptedAmountFailsClosedWithoutWritingASnapshot() {
        accepted.add(Map.of("pid", "other", "tpm_ac_amount", "bad"));
        assertThrows(IllegalStateException.class, this::run);
        accepted.clear(); accepted.add(Map.of("pid", "other", "tpm_ac_amount", "-1"));
        assertThrows(IllegalStateException.class, this::run);
        verify(db, never()).update(anyString(), anyString(), any());
    }
    @Test void rerunRecomputesTheSameRecordWithoutCreatingAnotherOutcome() {
        acceptance.put("tpm_ac_files", List.of()); run();
        acceptance.put("tpm_ac_files", List.of(Map.of("fileId", "file"))); run();
        run();
        List<Map<String, Object>> snapshots = captureUpdates(3);
        assertEquals("blocked", snapshots.get(0).get("tdv_ck_status"));
        assertEquals("passed", snapshots.get(1).get("tdv_ck_status"));
        assertEquals("passed", snapshots.get(2).get("tdv_ck_status"));
        for (Map<String, Object> snapshot : snapshots) assertFalse(snapshot.containsKey("pid"));
        verify(db, never()).create(anyString(), any());
    }
    @Test void dryRunCalculatesWithoutWriting() {
        assertTrue(handler.supportsDryRun());
        Map<?, ?> result = (Map<?, ?>) handler.execute(context(true));
        assertTrue(result instanceof Map<?, ?>);
        Map<?, ?> snapshot = (Map<?, ?>) result.get("verificationSnapshot");
        assertEquals("passed", snapshot.get("tdv_ck_status"));
        assertEquals("2026-10-16T08:00:00Z", snapshot.get("tdv_ck_checked_at"));
        verify(db, never()).update(anyString(), anyString(), any());
    }
    @Test void linkedRecordsAndTrustedContextAreMandatory() {
        assertThrows(IllegalArgumentException.class, () -> handler.execute(null));
        target.remove("tdv_ck_acceptance"); assertThrows(IllegalArgumentException.class, this::run);
        target.put("tdv_ck_acceptance", "foreign-record"); assertThrows(IllegalArgumentException.class, this::run);
        target.put("tdv_ck_acceptance", "acceptance"); acceptance.put("tpm_ac_project", "foreign-project");
        assertThrows(IllegalArgumentException.class, this::run);
        verify(db, never()).update(anyString(), anyString(), any());
    }
    @Test void permissionOrInfrastructureFailureCannotWriteAPassingSnapshot() {
        when(db.getById(anyString(), anyString())).thenThrow(new SecurityException("Denied"));
        assertThrows(SecurityException.class, this::run);
        verify(db, never()).update(anyString(), anyString(), any());
    }
}
