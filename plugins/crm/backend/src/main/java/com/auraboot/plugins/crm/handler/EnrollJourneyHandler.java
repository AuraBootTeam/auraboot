package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.JourneyProgressionEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enrolls a contact into a journey (CRM gap #6 M1).
 *
 * <p>Wired as the {@code handler} of {@code crm:enroll_journey} (custom). Reads
 * journey + contact from the payload, enforces the no-double-enrollment conflict
 * rule (a contact may have at most one active enrollment per journey), and
 * creates the enrollment at step 0 / active. Fail-loud (red line §8) on missing
 * inputs or a conflicting active enrollment.
 */
@Extension
public class EnrollJourneyHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(EnrollJourneyHandler.class);

    public static final String COMMAND_TYPE = "crm:enroll_journey";

    private static final String MODEL = "crm_journey_enrollment";

    @Override
    public String getCommandType() {
        return COMMAND_TYPE;
    }

    @Override
    public Set<String> getSupportedCommandTypes() {
        return Set.of(COMMAND_TYPE);
    }

    @Override
    public boolean supports(String commandType) {
        return COMMAND_TYPE.equals(commandType);
    }

    @Override
    public Object execute(CommandContext context) throws Exception {
        DataAccessor db = context.dataAccessor();
        if (db == null) {
            throw new IllegalStateException("DataAccessor unavailable; cannot enroll journey");
        }
        Map<String, Object> payload = context.payload();
        if (payload == null) {
            throw new IllegalStateException("No payload on context; journey + contact required");
        }
        String journeyId = str(payload.get("crm_jne_journey_id"));
        String contactId = str(payload.get("crm_jne_contact_id"));
        if (journeyId == null || journeyId.isBlank()) {
            throw new IllegalStateException("crm_jne_journey_id is required to enroll");
        }
        if (contactId == null || contactId.isBlank()) {
            throw new IllegalStateException("crm_jne_contact_id is required to enroll");
        }

        // Conflict rule: at most one ACTIVE enrollment per (journey, contact).
        Map<String, Object> filter = new HashMap<>();
        filter.put("crm_jne_journey_id", journeyId);
        filter.put("crm_jne_contact_id", contactId);
        filter.put("crm_jne_status", JourneyProgressionEngine.ACTIVE);
        List<Map<String, Object>> existing = db.query(MODEL, filter);
        if (existing != null && !existing.isEmpty()) {
            throw new IllegalStateException(
                    "Contact " + contactId + " already has an active enrollment in journey " + journeyId
                            + " (red line §8 — no duplicate enrollment)");
        }

        Map<String, Object> rec = new HashMap<>();
        rec.put("crm_jne_code", "JNE-" + journeyId + "-" + contactId);
        rec.put("crm_jne_journey_id", journeyId);
        rec.put("crm_jne_contact_id", contactId);
        rec.put("crm_jne_current_step", 0);
        rec.put("crm_jne_status", JourneyProgressionEngine.ACTIVE);
        rec.put("crm_jne_last_advanced_at", java.time.Instant.now().toString());
        Map<String, Object> created = db.create(MODEL, rec);

        Object newId = created != null ? created.getOrDefault("pid", created.get("id")) : null;
        log.info("Journey enrollment created: journey={} contact={} -> {}", journeyId, contactId, newId);

        return Map.of("enrollmentId", newId == null ? "" : newId,
                "journeyId", journeyId, "contactId", contactId, "status", JourneyProgressionEngine.ACTIVE);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
