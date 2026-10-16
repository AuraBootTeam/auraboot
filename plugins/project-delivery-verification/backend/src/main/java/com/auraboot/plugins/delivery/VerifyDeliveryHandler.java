package com.auraboot.plugins.delivery;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.pf4j.Extension;

/** Recomputes an advisory snapshot through the host's tenant-scoped, transactional bridge. */
@Extension
public final class VerifyDeliveryHandler implements CommandHandlerExtension {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Clock clock;
    public VerifyDeliveryHandler() { this(Clock.systemUTC()); }
    VerifyDeliveryHandler(Clock clock) { this.clock = Objects.requireNonNull(clock); }
    @Override public String getCommandType() { return "tdv:verify_delivery"; }
    @Override public boolean supportsDryRun() { return true; }

    @Override public Object execute(CommandContext context) {
        if (context == null || context.tenantId() == null || context.dataAccessor() == null
                || !"tdv_check".equals(context.modelCode()) || blank(context.recordId())) {
            throw new IllegalArgumentException("An authorized verification target is required");
        }
        DataAccessor db = context.dataAccessor();
        Map<String, Object> check = required(db, "tdv_check", context.recordId());
        String acceptanceId = reference(check.get("tdv_ck_acceptance"));
        Map<String, Object> acceptance = required(db, "tpm_acceptance", acceptanceId);
        String projectId = reference(acceptance.get("tpm_ac_project"));
        Map<String, Object> project = required(db, "tpm_project", projectId);

        BigDecimal amount = money(acceptance.get("tpm_ac_amount"));
        BigDecimal budget = money(project.get("tpm_pj_budget"));
        BigDecimal accepted = BigDecimal.ZERO.setScale(2);
        // Accepted amounts only; drafts and the current acceptance never count twice.
        for (Map<String, Object> other : db.query("tpm_acceptance",
                Map.of("tpm_ac_project", projectId, "tpm_ac_status", "accepted"))) {
            if (acceptanceId.equals(other.get("pid"))) continue;
            BigDecimal value = money(other.get("tpm_ac_amount"));
            if (value == null || value.signum() < 0) {
                throw new IllegalStateException("An accepted amount is invalid; no snapshot was written");
            }
            accepted = accepted.add(value);
        }
        BigDecimal available = budget == null ? null : budget.subtract(accepted);
        Object files = acceptance.get("tpm_ac_files");
        boolean materialsOk = hasMaterials(files);
        boolean scopeOk = !blank(acceptance.get("tpm_ac_scope"));
        boolean amountOk = amount != null && amount.signum() > 0;
        boolean budgetKnown = budget != null && budget.signum() > 0;
        boolean budgetOk = budgetKnown && amountOk && amount.compareTo(available) <= 0;
        int passed = (materialsOk ? 1 : 0) + (scopeOk ? 1 : 0) + (amountOk ? 1 : 0) + (budgetOk ? 1 : 0);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("tdv_ck_status", passed == 4 ? "passed" : "blocked");
        snapshot.put("tdv_ck_materials", materialsOk ? "passed" : "blocked");
        snapshot.put("tdv_ck_scope", scopeOk ? "passed" : "blocked");
        snapshot.put("tdv_ck_amount_check", amountOk ? "passed" : "blocked");
        snapshot.put("tdv_ck_budget_check", !budgetKnown ? "missing" : budgetOk ? "passed" : "blocked");
        snapshot.put("tdv_ck_amount", amount);
        snapshot.put("tdv_ck_budget", budget);
        snapshot.put("tdv_ck_accepted", accepted);
        snapshot.put("tdv_ck_available", available);
        snapshot.put("tdv_ck_passed", passed);
        snapshot.put("tdv_ck_checked_at", Instant.now(clock).toString());
        if (!context.dryRun()) db.update("tdv_check", context.recordId(), snapshot);
        // Keep result fields nested: the host must not persist them a second time.
        return Map.of("verificationSnapshot", Collections.unmodifiableMap(snapshot));
    }

    private static Map<String, Object> required(DataAccessor db, String model, String id) {
        Map<String, Object> record = db.getById(model, id);
        if (record == null || !id.equals(record.get("pid"))) {
            throw new IllegalArgumentException("The linked business record is unavailable");
        }
        return record;
    }
    private static String reference(Object value) {
        if (!(value instanceof String id) || id.isBlank()) {
            throw new IllegalArgumentException("A linked business record is required");
        }
        return id;
    }
    private static boolean blank(Object value) { return value == null || value.toString().isBlank(); }
    private static boolean hasMaterials(Object value) {
        if (value instanceof Collection<?> list) return !list.isEmpty();
        // The public DataAccessor read contract serializes json/jsonb as strings.
        if (value instanceof String text) {
            try {
                var parsed = JSON.readTree(text);
                return parsed != null && parsed.isArray() && !parsed.isEmpty();
            } catch (JsonProcessingException invalid) {
                return false;
            }
        }
        return false;
    }
    private static BigDecimal money(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        try {
            BigDecimal amount = new BigDecimal(value.toString());
            return amount.precision() <= 20 ? amount.setScale(2, RoundingMode.UNNECESSARY) : null;
        } catch (NumberFormatException | ArithmeticException invalid) {
            return null;
        }
    }
}
