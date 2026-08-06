package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.ForecastSubmissionEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Validates and submits a sales forecast (CRM gap #5b).
 *
 * <p>Wired as the {@code handler} of {@code crm:submit_forecast} (state transition
 * draft -&gt; submitted). Reads the submission record, runs the pure
 * {@link ForecastSubmissionEngine}, and on success stamps status + submitted_at.
 * Fail-loud (red line §8): an invalid submission aborts with the violation list
 * rather than silently transitioning.
 */
@Extension
public class SubmitForecastHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(SubmitForecastHandler.class);

    public static final String COMMAND_TYPE = "crm:submit_forecast";

    private static final String MODEL = "crm_forecast_submission";

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
            throw new IllegalStateException("DataAccessor unavailable; cannot submit forecast");
        }
        String recordId = context.recordId();
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalStateException("No forecast submission record id on context");
        }
        Map<String, Object> rec = db.getById(MODEL, recordId);
        if (rec == null) {
            throw new IllegalStateException("Forecast submission not found: " + recordId);
        }

        ForecastSubmissionEngine.Submission submission = new ForecastSubmissionEngine.Submission(
                bd(rec.get("crm_fcst_commit_amount")),
                bd(rec.get("crm_fcst_best_case_amount")),
                bd(rec.get("crm_fcst_pipeline_amount")),
                str(rec.get("crm_fcst_period")));

        ForecastSubmissionEngine.Result result = ForecastSubmissionEngine.validate(submission);
        if (!result.valid()) {
            throw new IllegalStateException(
                    "Forecast submission " + recordId + " invalid (red line §8 — no silent submit): "
                            + String.join("; ", result.violations()));
        }

        Map<String, Object> update = new HashMap<>();
        update.put("crm_fcst_status", "submitted");
        update.put("crm_fcst_submitted_at", Instant.now().toString());
        db.update(MODEL, recordId, update);

        log.info("Forecast submission {} period={} submitted (commit={} best={} pipeline={})",
                recordId, submission.period(), submission.commit(), submission.bestCase(), submission.pipeline());

        return Map.of(
                "submissionId", recordId,
                "period", submission.period() == null ? "" : submission.period(),
                "status", "submitted");
    }

    private static BigDecimal bd(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
