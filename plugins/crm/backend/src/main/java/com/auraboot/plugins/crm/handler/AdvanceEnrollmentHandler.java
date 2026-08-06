package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.JourneyProgressionEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Advances a journey enrollment one step (CRM gap #6 M1).
 *
 * <p>Wired as the {@code handler} of {@code crm:advance_enrollment} (custom).
 * Reads the enrollment + its journey's step count, delegates to the pure
 * {@link JourneyProgressionEngine}, and persists the next step / status
 * (advance, complete at the last step, or no-op for terminal enrollments).
 * Fail-loud (red line §8) on a missing enrollment.
 */
@Extension
public class AdvanceEnrollmentHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(AdvanceEnrollmentHandler.class);

    public static final String COMMAND_TYPE = "crm:advance_enrollment";

    private static final String MODEL = "crm_journey_enrollment";
    private static final String JOURNEY_MODEL = "crm_journey";

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
            throw new IllegalStateException("DataAccessor unavailable; cannot advance enrollment");
        }
        String recordId = context.recordId();
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalStateException("No enrollment record id on context");
        }
        Map<String, Object> enrollment = db.getById(MODEL, recordId);
        if (enrollment == null) {
            throw new IllegalStateException("Enrollment not found: " + recordId);
        }

        int currentStep = toInt(enrollment.get("crm_jne_current_step"));
        String status = str(enrollment.get("crm_jne_status"));
        int totalSteps = resolveTotalSteps(db, str(enrollment.get("crm_jne_journey_id")));

        JourneyProgressionEngine.Result r = JourneyProgressionEngine.progress(
                new JourneyProgressionEngine.Input(currentStep, totalSteps, status));

        if (r.action() == JourneyProgressionEngine.Action.NOOP) {
            log.info("Enrollment {} not advanced (terminal status={})", recordId, status);
            return Map.of("enrollmentId", recordId, "action", "noop", "status", r.nextStatus());
        }

        Map<String, Object> update = new HashMap<>();
        update.put("crm_jne_current_step", r.nextStep());
        update.put("crm_jne_status", r.nextStatus());
        update.put("crm_jne_last_advanced_at", java.time.Instant.now().toString());
        db.update(MODEL, recordId, update);

        log.info("Enrollment {} {} -> step={} status={} (total={})",
                recordId, r.action(), r.nextStep(), r.nextStatus(), totalSteps);

        return Map.of("enrollmentId", recordId, "action", r.action().name(),
                "currentStep", r.nextStep(), "status", r.nextStatus());
    }

    private int resolveTotalSteps(DataAccessor db, String journeyId) {
        if (journeyId == null || journeyId.isBlank()) {
            return 0;
        }
        Map<String, Object> journey = db.getById(JOURNEY_MODEL, journeyId);
        return journey == null ? 0 : toInt(journey.get("crm_jny_total_steps"));
    }

    private static int toInt(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
