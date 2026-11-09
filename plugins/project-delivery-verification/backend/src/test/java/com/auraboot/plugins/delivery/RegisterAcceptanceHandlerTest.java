package com.auraboot.plugins.delivery;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Hermetic stub-driven unit tests for {@link RegisterAcceptanceHandler} (testing-layering §2.1 layering).
 *
 * <p>The DataAccessor collaborator is a controlled Mockito stub — no in-memory fake DB.
 * Decision assertions (D) are stub-driven; write assertions (W) verify the exact CAS and
 * create arguments instead of reading back a mutated in-memory map. Replay state is
 * expressed by re-pointing the stubbed reads at the persisted outcome of the first run.
 * The former concurrent-CAS conflict-rollback scenario was removed: its "conflict" and
 * "no partial business actions" conclusions were implemented by the fake CAS itself and
 * belong to the host transactional bridge, not to a stub.
 * owed: real-stack CAS/unique-key coverage per testing-layering §2.1 (registry owed-auraboot)
 */
class RegisterAcceptanceHandlerTest {
    private final RegisterAcceptanceHandler handler = new RegisterAcceptanceHandler();
    private Map<String, Object> acceptance;
    private Map<String, Object> project;
    private List<Map<String, Object>> checks;
    private DataAccessor db;

    @BeforeEach void fixture() {
        acceptance = new HashMap<>(Map.of("pid", "acceptance", "tpm_ac_name", "Delivery",
                "tpm_ac_project", "project", "tpm_ac_status", "accepted", "tpm_ac_registered", false,
                "tpm_ac_amount", new BigDecimal("68000.00")));
        project = new HashMap<>(Map.of("pid", "project", "tpm_pj_accepted_total", BigDecimal.ZERO));
        checks = new ArrayList<>();
        db = mock(DataAccessor.class);
        // Read decisions: the stub decides which stored records the handler sees.
        when(db.getById("tpm_acceptance", "acceptance")).thenReturn(acceptance);
        when(db.getById("tpm_project", "project")).thenReturn(project);
        when(db.query("tpm_acceptance",
                Map.of("tpm_ac_project", "project", "tpm_ac_status", "accepted")))
                .thenReturn(List.of(acceptance, Map.of("tpm_ac_amount", new BigDecimal("12000.00"))));
        when(db.query("tdv_check", Map.of("tdv_ck_acceptance", "acceptance"))).thenReturn(checks);
        // Write decisions: both CAS transitions and the host-returned created row succeed.
        when(db.compareAndSet("tpm_acceptance", "acceptance", "tpm_ac_registered", false, true))
                .thenReturn(true);
        when(db.compareAndSet("tpm_project", "project", "tpm_pj_accepted_total",
                BigDecimal.ZERO, new BigDecimal("80000.00"))).thenReturn(true);
        when(db.create(eq("tdv_check"), anyMap())).thenReturn(Map.of("pid", "verification-1"));
    }

    private CommandHandlerExtension.CommandContext context(boolean dryRun) {
        return new CommandHandlerExtension.CommandContext(7L, "com.auraboot.project-delivery-verification", "tdv",
                "tdv:register_acceptance", "tpm_project", "project", Map.of("acceptancePid", "acceptance"),
                Map.of(CommandHandlerExtension.DATA_ACCESSOR_KEY, db), dryRun);
    }

    @Test void registersApprovedDeliveryAndRecomputesTotal() {
        Map<?, ?> result = (Map<?, ?>) handler.execute(context(false));
        verify(db).compareAndSet("tpm_acceptance", "acceptance", "tpm_ac_registered", false, true);
        verify(db).compareAndSet("tpm_project", "project", "tpm_pj_accepted_total",
                BigDecimal.ZERO, new BigDecimal("80000.00"));
        ArgumentCaptor<Map<String, Object>> created = ArgumentCaptor.forClass(Map.class);
        verify(db).create(eq("tdv_check"), created.capture());
        assertEquals("acceptance", created.getValue().get("tdv_ck_acceptance"));
        assertEquals("pending", created.getValue().get("tdv_ck_status"));
        assertEquals(new BigDecimal("80000.00"), result.get("acceptedTotal"));
        assertEquals(List.of("verification-1"), result.get("verificationPids"));
        assertEquals(false, result.get("alreadyRegistered"));
    }

    @Test void replayWithAnotherRequestDoesNotIncrementOrCreateAgain() {
        handler.execute(context(false));
        ArgumentCaptor<Map<String, Object>> first = ArgumentCaptor.forClass(Map.class);
        verify(db, times(1)).create(eq("tdv_check"), first.capture());
        assertEquals("acceptance", first.getValue().get("tdv_ck_acceptance"));
        // The host persists run 1: the acceptance is registered, the project total advanced,
        // and the created verification row now answers the tdv_check query.
        acceptance.put("tpm_ac_registered", true);
        project.put("tpm_pj_accepted_total", new BigDecimal("80000.00"));
        checks.add(Map.of("pid", "verification-1", "tdv_ck_acceptance", "acceptance"));
        when(db.compareAndSet("tpm_project", "project", "tpm_pj_accepted_total",
                new BigDecimal("80000.00"), new BigDecimal("80000.00"))).thenReturn(true);
        Map<?, ?> replay = (Map<?, ?>) handler.execute(context(false));
        assertEquals(true, replay.get("alreadyRegistered"));
        verify(db, times(1)).create(anyString(), anyMap());
        // any(Object.class) pins the 5-arg Object overload — any() alone would bind the
        // 5-arg Map overload (a default method) and never match the recorded CAS call.
        verify(db, times(1)).compareAndSet(eq("tpm_acceptance"), anyString(), anyString(),
                any(Object.class), any(Object.class));
        verify(db).compareAndSet("tpm_project", "project", "tpm_pj_accepted_total",
                new BigDecimal("80000.00"), new BigDecimal("80000.00"));
    }

    @Test void draftAndWrongProjectCannotBeRegistered() {
        acceptance.put("tpm_ac_status", "draft");
        assertThrows(IllegalArgumentException.class, () -> handler.execute(context(false)));
        acceptance.put("tpm_ac_status", "accepted"); acceptance.put("tpm_ac_project", "other-project");
        assertThrows(IllegalArgumentException.class, () -> handler.execute(context(false)));
        verify(db, never()).compareAndSet(anyString(), anyString(), anyString(),
                any(Object.class), any(Object.class));
        verify(db, never()).create(anyString(), anyMap());
    }

    @Test void dryRunDoesNotRegisterOrWriteProjection() {
        Map<?, ?> result = (Map<?, ?>) handler.execute(context(true));
        assertEquals(new BigDecimal("80000.00"), result.get("acceptedTotal"));
        assertEquals(true, result.get("dryRun"));
        verify(db, never()).compareAndSet(anyString(), anyString(), anyString(),
                any(Object.class), any(Object.class));
        verify(db, never()).create(anyString(), anyMap());
    }

    // Removed concurrentRegistrationCannotContinueWithPartialBusinessActions: its conflict
    // rollback and partial-write conclusions were produced by the fake CAS implementation
    // (in-memory compare-and-swap), not by anything a stub can prove about the host
    // transactional bridge.
    // owed: real-stack CAS/unique-key coverage per testing-layering §2.1 (registry owed-auraboot)
}
