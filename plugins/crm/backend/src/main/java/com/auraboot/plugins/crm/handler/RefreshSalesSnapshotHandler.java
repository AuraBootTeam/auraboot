package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.AnalyticsAggregationEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Materializes the CRM sales snapshot from opportunities (CRM gap #17 DWH M1).
 *
 * <p>Wired as the {@code handler} of {@code crm:refresh_sales_snapshot} (custom).
 * Reads the L0 transactional opportunities, rolls them up via the pure
 * {@link AnalyticsAggregationEngine}, and upserts the L1 {@code crm_sales_snapshot}
 * row keyed by {@code period} (default {@code ALL}). Idempotent: re-running
 * updates the same period row rather than duplicating. This is the manual
 * refresh trigger; a production scheduler invokes the same logic on a timer.
 */
@Extension
public class RefreshSalesSnapshotHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(RefreshSalesSnapshotHandler.class);

    public static final String COMMAND_TYPE = "crm:refresh_sales_snapshot";

    private static final String SNAPSHOT = "crm_sales_snapshot";
    private static final String OPP = "crm_opportunity_common";
    private static final String DEFAULT_PERIOD = "ALL";

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
            throw new IllegalStateException("DataAccessor unavailable; cannot refresh snapshot");
        }
        String period = DEFAULT_PERIOD;
        if (context.payload() != null && context.payload().get("crm_ss_period") != null) {
            String p = context.payload().get("crm_ss_period").toString().trim();
            if (!p.isEmpty()) {
                period = p;
            }
        }

        List<Map<String, Object>> opps = db.query(OPP, new HashMap<>());
        List<AnalyticsAggregationEngine.Opp> rows = new ArrayList<>();
        if (opps != null) {
            for (Map<String, Object> o : opps) {
                rows.add(new AnalyticsAggregationEngine.Opp(
                        str(o.get("crm_opp_stage")), bd(o.get("crm_opp_expected_amount"))));
            }
        }
        AnalyticsAggregationEngine.Result r = AnalyticsAggregationEngine.aggregate(rows);

        Map<String, Object> data = new HashMap<>();
        data.put("crm_ss_total_opps", r.totalOpps());
        data.put("crm_ss_won_count", r.wonCount());
        data.put("crm_ss_won_amount", r.wonAmount());
        data.put("crm_ss_pipeline_amount", r.pipelineAmount());
        data.put("crm_ss_conversion_rate", r.conversionRate());
        data.put("crm_ss_refreshed_at", Instant.now().toString());

        // Upsert by period (idempotent re-materialization).
        Map<String, Object> filter = new HashMap<>();
        filter.put("crm_ss_period", period);
        List<Map<String, Object>> existing = db.query(SNAPSHOT, filter);
        String snapshotId;
        if (existing != null && !existing.isEmpty()) {
            snapshotId = str(existing.get(0).get("pid"));
            db.update(SNAPSHOT, snapshotId, data);
        } else {
            data.put("crm_ss_code", "SS-" + period);
            data.put("crm_ss_period", period);
            Map<String, Object> created = db.create(SNAPSHOT, data);
            snapshotId = created != null ? str(created.getOrDefault("pid", created.get("id"))) : null;
        }

        log.info("Sales snapshot refreshed: period={} total={} won={} wonAmt={} pipeline={} conv={}",
                period, r.totalOpps(), r.wonCount(), r.wonAmount(), r.pipelineAmount(), r.conversionRate());

        return Map.of("period", period, "snapshotId", snapshotId == null ? "" : snapshotId,
                "totalOpps", r.totalOpps(), "wonCount", r.wonCount(),
                "wonAmount", r.wonAmount(), "conversionRate", r.conversionRate());
    }

    private static BigDecimal bd(Object v) {
        if (v == null) {
            return BigDecimal.ZERO;
        }
        if (v instanceof BigDecimal b) {
            return b;
        }
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
