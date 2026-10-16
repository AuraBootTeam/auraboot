package com.auraboot.plugins.delivery;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VerifyDeliveryHandlerTest {
    private final VerifyDeliveryHandler handler = new VerifyDeliveryHandler(
            Clock.fixed(Instant.parse("2026-10-16T08:00:00Z"), ZoneOffset.UTC));
    private Map<String, Object> target, acceptance, project;
    private List<Map<String, Object>> accepted;
    private int writes;
    private RuntimeException readFailure;
    private DataAccessor db;

    @BeforeEach void setup() {
        target = new HashMap<>(Map.of("pid", "check", "tdv_ck_acceptance", "acceptance"));
        acceptance = new HashMap<>(Map.of("pid", "acceptance", "tpm_ac_project", "project",
                "tpm_ac_amount", "68000.00", "tpm_ac_scope", "Migration and training",
                "tpm_ac_files", List.of(Map.of("fileId", "uploaded-evidence")), "tpm_ac_status", "draft"));
        project = new HashMap<>(Map.of("pid", "project", "tpm_pj_budget", "100000.00"));
        accepted = new ArrayList<>(); writes = 0; readFailure = null;
        db = (DataAccessor) Proxy.newProxyInstance(DataAccessor.class.getClassLoader(),
                new Class<?>[]{DataAccessor.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getById" -> {
                        if (readFailure != null) throw readFailure;
                        String id = (String) args[1];
                        yield switch ((String) args[0]) {
                            case "tdv_check" -> id.equals("check") ? target : null;
                            case "tpm_acceptance" -> id.equals("acceptance") ? acceptance : null;
                            case "tpm_project" -> id.equals("project") ? project : null;
                            default -> throw new AssertionError("Unexpected model");
                        };
                    }
                    case "query" -> {
                        assertEquals("tpm_acceptance", args[0]);
                        assertEquals(Map.of("tpm_ac_project", "project", "tpm_ac_status", "accepted"), args[1]);
                        yield accepted;
                    }
                    case "update" -> {
                        assertEquals("tdv_check", args[0]); assertEquals("check", args[1]);
                        writes++; target.putAll((Map<String, Object>) args[2]); yield target;
                    }
                    default -> throw new AssertionError("Unexpected accessor operation: " + method.getName());
                });
    }
    private CommandHandlerExtension.CommandContext context(boolean dryRun) {
        return new CommandHandlerExtension.CommandContext(7L, "com.auraboot.project-delivery-verification",
                "tdv", "tdv:verify_delivery", "tdv_check", "check",
                Map.of("tdv_ck_status", "passed", "tpm_ac_amount", 1),
                Map.of(CommandHandlerExtension.DATA_ACCESSOR_KEY, db), dryRun);
    }
    private void run() { handler.execute(context(false)); }

    @Test void computesFromStoredRecordsAndLeavesApprovalUntouched() {
        assertEquals("tdv:verify_delivery", handler.getCommandType());
        run(); assertEquals("passed", target.get("tdv_ck_status"));
        assertEquals(4, target.get("tdv_ck_passed"));
        assertEquals(new BigDecimal("68000.00"), target.get("tdv_ck_amount"));
        assertEquals("draft", acceptance.get("tpm_ac_status"));
        assertEquals("2026-10-16T08:00:00Z", target.get("tdv_ck_checked_at"));
        assertEquals(1, writes);
    }
    @Test void emptyEvidenceAndScopeProduceActionableCorrectionCodes() {
        acceptance.put("tpm_ac_files", List.of()); acceptance.put("tpm_ac_scope", " "); run();
        assertEquals("blocked", target.get("tdv_ck_status")); assertEquals(2, target.get("tdv_ck_passed"));
        assertEquals("blocked", target.get("tdv_ck_materials")); assertEquals("blocked", target.get("tdv_ck_scope"));
    }
    @Test void readsSerializedJsonbAndRejectsEmptyMalformedOrNonArrayValues() {
        acceptance.put("tpm_ac_files", "[{\"fileId\":\"uploaded-evidence\"}]"); run();
        assertEquals("passed", target.get("tdv_ck_materials"));
        for (Object value : List.of("[]", "bad-json", "{}", "null", "", 42)) {
            acceptance.put("tpm_ac_files", value); run(); assertEquals("blocked", target.get("tdv_ck_materials"));
        }
        acceptance.remove("tpm_ac_files"); run(); assertEquals("blocked", target.get("tdv_ck_materials"));
    }
    @Test void acceptedAmountsReduceBudgetAndCurrentRecordIsNotDoubleCounted() {
        accepted.add(Map.of("pid", "other", "tpm_ac_amount", "32000.00"));
        accepted.add(acceptance); run();
        assertEquals("passed", target.get("tdv_ck_budget_check"));
        assertEquals(new BigDecimal("68000.00"), target.get("tdv_ck_available"));
        acceptance.put("tpm_ac_amount", "68000.01"); run();
        assertEquals("blocked", target.get("tdv_ck_budget_check")); assertEquals(3, target.get("tdv_ck_passed"));
    }
    @Test void missingNonpositiveOrInvalidBudgetDoesNotInventAnAllowance() {
        for (Object value : List.of("", "invalid", "0", "-1", "0.001")) {
            project.put("tpm_pj_budget", value); run(); assertEquals("missing", target.get("tdv_ck_budget_check"));
        }
        project.remove("tpm_pj_budget"); run(); assertNull(target.get("tdv_ck_budget"));
        assertNull(target.get("tdv_ck_available"));
    }
    @Test void invalidAmountIncludingFractionalCentsFailsClosed() {
        for (Object value : List.of("", "broken", "0", "-0.01", "1.001", "999999999999999999999")) {
            acceptance.put("tpm_ac_amount", value); run();
            assertEquals("blocked", target.get("tdv_ck_amount_check"));
            assertNotEquals("passed", target.get("tdv_ck_status"));
        }
        acceptance.remove("tpm_ac_amount"); run(); assertEquals("blocked", target.get("tdv_ck_amount_check"));
    }
    @Test void corruptAcceptedAmountRollsBackInsteadOfUnderstatingConsumption() {
        accepted.add(Map.of("pid", "other", "tpm_ac_amount", "bad"));
        assertThrows(IllegalStateException.class, this::run); assertEquals(0, writes);
        accepted.clear(); accepted.add(Map.of("pid", "other", "tpm_ac_amount", "-1"));
        assertThrows(IllegalStateException.class, this::run); assertEquals(0, writes);
    }
    @Test void rerunRecomputesTheSameRecordWithoutCreatingAnotherOutcome() {
        acceptance.put("tpm_ac_files", List.of()); run(); assertEquals("blocked", target.get("tdv_ck_status"));
        acceptance.put("tpm_ac_files", List.of(Map.of("fileId", "file"))); run();
        assertEquals("passed", target.get("tdv_ck_status")); run(); assertEquals(3, writes);
        assertEquals("check", target.get("pid"));
    }
    @Test void dryRunCalculatesWithoutWriting() {
        assertTrue(handler.supportsDryRun()); Object result = handler.execute(context(true));
        assertTrue(result instanceof Map<?, ?>); assertEquals(0, writes);
        assertNull(target.get("tdv_ck_checked_at"));
    }
    @Test void linkedRecordsAndTrustedContextAreMandatory() {
        assertThrows(IllegalArgumentException.class, () -> handler.execute(null));
        target.remove("tdv_ck_acceptance"); assertThrows(IllegalArgumentException.class, this::run);
        target.put("tdv_ck_acceptance", "foreign-record"); assertThrows(IllegalArgumentException.class, this::run);
        target.put("tdv_ck_acceptance", "acceptance"); acceptance.put("tpm_ac_project", "foreign-project");
        assertThrows(IllegalArgumentException.class, this::run); assertEquals(0, writes);
    }
    @Test void permissionOrInfrastructureFailureCannotWriteAPassingSnapshot() {
        readFailure = new SecurityException("Denied"); assertThrows(SecurityException.class, this::run);
        assertEquals(0, writes); assertNull(target.get("tdv_ck_status"));
    }
}
