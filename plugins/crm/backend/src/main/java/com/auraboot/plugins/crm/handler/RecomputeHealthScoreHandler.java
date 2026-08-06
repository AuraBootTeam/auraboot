package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.HealthScoreEngine;
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
 * Recomputes a customer account's health score (CRM gap #10).
 *
 * <p>Wired as the {@code handler} of {@code crm:recompute_health} (type=custom).
 * Gathers the account's signals via {@link DataAccessor} (open complaints, won /
 * open opportunities; recency proxied by whether any open opportunity exists),
 * delegates to the pure {@link HealthScoreEngine}, and writes the resulting score
 * + band onto the account. Fail-loud (red line §8) on a missing account.
 */
@Extension
public class RecomputeHealthScoreHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(RecomputeHealthScoreHandler.class);

    public static final String COMMAND_TYPE = "crm:recompute_health";

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
            throw new IllegalStateException("DataAccessor unavailable; cannot recompute health");
        }
        String accountId = context.recordId();
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalStateException("No account record id on context");
        }

        List<Map<String, Object>> complaints = db.query("crm_complaint",
                Map.of("crm_cmp_account_id", accountId));
        int openComplaints = 0;
        if (complaints != null) {
            for (Map<String, Object> c : complaints) {
                String st = str(c.get("crm_cmp_status"));
                if ("new".equals(st) || "investigating".equals(st)) {
                    openComplaints++;
                }
            }
        }

        List<Map<String, Object>> opps = db.query("crm_opportunity_common",
                Map.of("crm_opp_account_id", accountId));
        int won = 0;
        int open = 0;
        if (opps != null) {
            for (Map<String, Object> o : opps) {
                String st = str(o.get("crm_opp_stage"));
                if ("closed_won".equals(st)) {
                    won++;
                } else if (!"closed_lost".equals(st)) {
                    open++;
                }
            }
        }

        int days = open > 0 ? 0 : -1;
        HealthScoreEngine.Result r = HealthScoreEngine.compute(
                new HealthScoreEngine.Signals(openComplaints, won, open, days));

        Map<String, Object> update = new HashMap<>();
        update.put("crm_acc_health_score", r.score());
        update.put("crm_acc_health_band", r.band());
        db.update("crm_account_common", accountId, update);

        log.info("Account {} health recomputed: score={} band={} (openComplaints={} won={} open={})",
                accountId, r.score(), r.band(), openComplaints, won, open);

        return Map.of("accountId", accountId, "healthScore", r.score(), "healthBand", r.band());
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
