package com.auraboot.plugins.delivery;

import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Registers an accepted delivery through the host's tenant-scoped transactional bridge. */
@Extension
public final class RegisterAcceptanceHandler implements CommandHandlerExtension {
    @Override public String getCommandType() { return "tdv:register_acceptance"; }
    @Override public boolean supportsDryRun() { return true; }

    @Override public Object execute(CommandContext context) {
        if (context == null || context.tenantId() == null || context.dataAccessor() == null
                || !"tpm_project".equals(context.modelCode()) || context.recordId() == null) {
            throw new IllegalArgumentException("An authorized project target is required");
        }
        Object input = context.payload().get("acceptancePid");
        if (!(input instanceof String acceptancePid) || acceptancePid.isBlank()) {
            throw new IllegalArgumentException("Select the accepted delivery record");
        }
        DataAccessor db = context.dataAccessor();
        Map<String, Object> acceptance = required(db, "tpm_acceptance", acceptancePid);
        Map<String, Object> project = required(db, "tpm_project", context.recordId());
        if (!context.recordId().equals(acceptance.get("tpm_ac_project"))) {
            throw new IllegalArgumentException("The acceptance does not belong to the selected project");
        }
        if (!"accepted".equals(acceptance.get("tpm_ac_status"))) {
            throw new IllegalArgumentException("Only an approved acceptance can be registered");
        }
        List<Map<String, Object>> accepted = db.query("tpm_acceptance", Map.of(
                "tpm_ac_project", context.recordId(), "tpm_ac_status", "accepted"));
        BigDecimal total = accepted.stream().map(row -> money(row.get("tpm_ac_amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (context.dryRun()) return Map.of("acceptedTotal", total, "dryRun", true);

        // CAS locks the acceptance row until the command transaction commits. A distinct
        // replay event must not create another verification even with a new request ID.
        Object previousRegistration = acceptance.get("tpm_ac_registered");
        boolean registered = Boolean.TRUE.equals(previousRegistration);
        if (!registered && !db.compareAndSet("tpm_acceptance", acceptancePid,
                "tpm_ac_registered", previousRegistration, true)) {
            throw new IllegalStateException("Acceptance registration changed concurrently; retry this event");
        }
        List<Map<String, Object>> checks = db.query("tdv_check", Map.of("tdv_ck_acceptance", acceptancePid));
        if (checks.isEmpty()) {
            if (registered) throw new IllegalStateException("Registered acceptance has no verification record");
            checks = List.of(db.create("tdv_check", Map.of("tdv_ck_acceptance", acceptancePid,
                    "tdv_ck_name", String.valueOf(acceptance.get("tpm_ac_name")) + " · 交付核验",
                    "tdv_ck_status", "pending")));
        }
        Object previousTotal = project.get("tpm_pj_accepted_total");
        if (!db.compareAndSet("tpm_project", context.recordId(), "tpm_pj_accepted_total", previousTotal, total)) {
            throw new IllegalStateException("Project registration changed concurrently; retry this event");
        }
        return Map.of("projectPid", context.recordId(), "acceptancePid", acceptancePid,
                "acceptedTotal", total, "verificationPids", checks.stream().map(row -> row.get("pid")).toList(),
                "alreadyRegistered", registered);
    }

    private static Map<String, Object> required(DataAccessor db, String model, String pid) {
        Map<String, Object> row = db.getById(model, pid);
        if (row == null || !pid.equals(row.get("pid"))) {
            throw new IllegalArgumentException("The related business record is unavailable");
        }
        return row;
    }

    private static BigDecimal money(Object value) {
        if (value == null) throw new IllegalArgumentException("Acceptance amount is required");
        BigDecimal amount = new BigDecimal(String.valueOf(value));
        if (amount.signum() < 0) throw new IllegalArgumentException("Acceptance amount cannot be negative");
        return amount;
    }
}
