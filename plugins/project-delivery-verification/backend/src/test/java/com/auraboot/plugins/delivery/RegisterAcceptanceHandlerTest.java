package com.auraboot.plugins.delivery;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RegisterAcceptanceHandlerTest {
    private final RegisterAcceptanceHandler handler = new RegisterAcceptanceHandler();
    private Map<String, Object> acceptance;
    private Map<String, Object> project;
    private List<Map<String, Object>> checks;
    private DataAccessor db;
    private boolean failCas;

    @BeforeEach void fixture() {
        acceptance = new HashMap<>(Map.of("pid", "acceptance", "tpm_ac_name", "Delivery",
                "tpm_ac_project", "project", "tpm_ac_status", "accepted", "tpm_ac_registered", false,
                "tpm_ac_amount", new BigDecimal("68000.00")));
        project = new HashMap<>(Map.of("pid", "project", "tpm_pj_accepted_total", BigDecimal.ZERO));
        checks = new ArrayList<>();
        db = (DataAccessor) Proxy.newProxyInstance(DataAccessor.class.getClassLoader(), new Class<?>[]{DataAccessor.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getById" -> switch ((String) args[0]) {
                        case "tpm_project" -> "project".equals(args[1]) ? project : null;
                        case "tpm_acceptance" -> "acceptance".equals(args[1]) ? acceptance : null;
                        default -> throw new AssertionError("Unexpected model");
                    };
                    case "query" -> "tdv_check".equals(args[0]) ? checks : List.of(acceptance,
                            Map.of("tpm_ac_amount", new BigDecimal("12000.00")));
                    case "compareAndSet" -> {
                        var target = "tpm_project".equals(args[0]) ? project : acceptance;
                        String field = (String) args[2];
                        if (failCas || !Objects.equals(target.get(field), args[3])) yield false;
                        target.put(field, args[4]); yield true;
                    }
                    case "create" -> {
                        assertEquals("tdv_check", args[0]);
                        Map<String, Object> row = new HashMap<>((Map<String, Object>) args[1]);
                        row.put("pid", "verification-" + (checks.size() + 1)); checks.add(row); yield row;
                    }
                    default -> throw new AssertionError("Unexpected operation: " + method.getName());
                });
    }

    private CommandHandlerExtension.CommandContext context(boolean dryRun) {
        return new CommandHandlerExtension.CommandContext(7L, "com.auraboot.project-delivery-verification", "tdv",
                "tdv:register_acceptance", "tpm_project", "project", Map.of("acceptancePid", "acceptance"),
                Map.of(CommandHandlerExtension.DATA_ACCESSOR_KEY, db), dryRun);
    }

    @Test void registersApprovedDeliveryAndRecomputesTotal() {
        handler.execute(context(false));
        assertEquals(new BigDecimal("80000.00"), project.get("tpm_pj_accepted_total"));
        assertEquals(true, acceptance.get("tpm_ac_registered"));
        assertEquals(1, checks.size());
        assertEquals("acceptance", checks.getFirst().get("tdv_ck_acceptance"));
        assertEquals("pending", checks.getFirst().get("tdv_ck_status"));
    }

    @Test void replayWithAnotherRequestDoesNotIncrementOrCreateAgain() {
        handler.execute(context(false)); handler.execute(context(false));
        assertEquals(new BigDecimal("80000.00"), project.get("tpm_pj_accepted_total"));
        assertEquals(1, checks.size());
    }

    @Test void draftAndWrongProjectCannotBeRegistered() {
        acceptance.put("tpm_ac_status", "draft");
        assertThrows(IllegalArgumentException.class, () -> handler.execute(context(false)));
        acceptance.put("tpm_ac_status", "accepted"); acceptance.put("tpm_ac_project", "other-project");
        assertThrows(IllegalArgumentException.class, () -> handler.execute(context(false)));
        assertEquals(false, acceptance.get("tpm_ac_registered")); assertTrue(checks.isEmpty());
    }

    @Test void dryRunDoesNotRegisterOrWriteProjection() {
        handler.execute(context(true));
        assertEquals(BigDecimal.ZERO, project.get("tpm_pj_accepted_total"));
        assertEquals(false, acceptance.get("tpm_ac_registered")); assertTrue(checks.isEmpty());
    }

    @Test void concurrentRegistrationCannotContinueWithPartialBusinessActions() {
        failCas = true;
        assertThrows(IllegalStateException.class, () -> handler.execute(context(false)));
        assertTrue(checks.isEmpty()); assertEquals(BigDecimal.ZERO, project.get("tpm_pj_accepted_total"));
    }
}
