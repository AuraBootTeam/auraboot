package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.JourneyProgressionEngine;
import com.auraboot.plugins.crm.engine.JourneyScheduleEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Journey scheduler tick: auto-advances due enrollments (CRM gap #6 M2).
 *
 * <p>Wired as the {@code handler} of {@code crm:run_journey_scheduler} (custom).
 * Scans active enrollments and, for each whose current step's delay has elapsed
 * ({@link JourneyScheduleEngine}), advances it one step / completes it
 * ({@link JourneyProgressionEngine}) and re-stamps the advance time. This is the
 * same logic a production background timer would invoke on a schedule; exposing
 * it as a command makes auto-advance deterministically testable.
 */
@Extension
public class RunJourneySchedulerHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(RunJourneySchedulerHandler.class);

    public static final String COMMAND_TYPE = "crm:run_journey_scheduler";

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
            throw new IllegalStateException("DataAccessor unavailable; cannot run journey scheduler");
        }
        long nowMin = Instant.now().getEpochSecond() / 60L;

        Map<String, Object> filter = new HashMap<>();
        filter.put("crm_jne_status", JourneyProgressionEngine.ACTIVE);
        List<Map<String, Object>> active = db.query(MODEL, filter);

        Map<String, Integer> journeySteps = new HashMap<>();
        List<String> advanced = new ArrayList<>();
        int scanned = 0;
        if (active != null) {
            for (Map<String, Object> e : active) {
                scanned++;
                String journeyId = str(e.get("crm_jne_journey_id"));
                int stepDelay = journeyStepDelay(db, journeySteps, journeyId);
                long lastAdvanced = epochMin(e.get("crm_jne_last_advanced_at"), nowMin);

                boolean due = JourneyScheduleEngine.isDue(new JourneyScheduleEngine.Input(
                        JourneyProgressionEngine.ACTIVE, lastAdvanced, stepDelay, nowMin));
                if (!due) {
                    continue;
                }

                int totalSteps = journeySteps.getOrDefault(journeyId, 0);
                int currentStep = toInt(e.get("crm_jne_current_step"));
                JourneyProgressionEngine.Result r = JourneyProgressionEngine.progress(
                        new JourneyProgressionEngine.Input(currentStep, totalSteps, JourneyProgressionEngine.ACTIVE));
                if (r.action() == JourneyProgressionEngine.Action.NOOP) {
                    continue;
                }
                Map<String, Object> update = new HashMap<>();
                update.put("crm_jne_current_step", r.nextStep());
                update.put("crm_jne_status", r.nextStatus());
                update.put("crm_jne_last_advanced_at", Instant.now().toString());
                db.update(MODEL, str(e.get("pid")), update);
                advanced.add(str(e.get("pid")));
            }
        }

        log.info("Journey scheduler tick: scanned={} advanced={}", scanned, advanced.size());
        return Map.of("scanned", scanned, "advanced", advanced.size(), "advancedIds", advanced);
    }

    private int journeyStepDelay(DataAccessor db, Map<String, Integer> cache, String journeyId) {
        if (journeyId == null || journeyId.isBlank()) {
            return 0;
        }
        if (cache.containsKey(journeyId)) {
            return delayOf(db, journeyId);
        }
        Map<String, Object> journey = db.getById(JOURNEY_MODEL, journeyId);
        cache.put(journeyId, journey == null ? 0 : toInt(journey.get("crm_jny_total_steps")));
        return journey == null ? 0 : toInt(journey.get("crm_jny_step_delay_minutes"));
    }

    private int delayOf(DataAccessor db, String journeyId) {
        Map<String, Object> journey = db.getById(JOURNEY_MODEL, journeyId);
        return journey == null ? 0 : toInt(journey.get("crm_jny_step_delay_minutes"));
    }

    private static long epochMin(Object datetime, long fallbackNow) {
        if (datetime == null) {
            return 0L; // never advanced -> treat as long-ago (due immediately if delay small)
        }
        if (datetime instanceof Instant inst) {
            return inst.getEpochSecond() / 60L;
        }
        if (datetime instanceof java.util.Date d) {
            return d.toInstant().getEpochSecond() / 60L;
        }
        String s = datetime.toString().trim();
        if (s.isEmpty()) {
            return 0L;
        }
        // ISO-8601 instant, e.g. "2026-06-06T16:07:13Z"
        try {
            return Instant.parse(s).getEpochSecond() / 60L;
        } catch (Exception ignore) {
            // ignore, try Postgres timestamptz form below
        }
        // Postgres timestamptz, e.g. "2026-06-06 16:07:13.155238+00" (space + short offset)
        try {
            String iso = s.replace(' ', 'T').replaceFirst("([+-]\\d{2})$", "$1:00");
            return java.time.OffsetDateTime.parse(iso).toInstant().getEpochSecond() / 60L;
        } catch (Exception ignore) {
            return 0L;
        }
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
