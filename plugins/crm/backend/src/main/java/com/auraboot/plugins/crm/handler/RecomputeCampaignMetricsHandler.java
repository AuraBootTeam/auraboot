package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.CampaignMetricsEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recomputes a marketing campaign's metrics (CRM gap #8).
 *
 * <p>Wired as the {@code handler} of {@code crm:recompute_campaign_metrics}
 * (custom). Counts campaign members by status, delegates to the pure
 * {@link CampaignMetricsEngine}, and writes member count + response/conversion
 * rate + cost-per-member back to the campaign. Fail-loud (red line §8) on a
 * missing record.
 */
@Extension
public class RecomputeCampaignMetricsHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(RecomputeCampaignMetricsHandler.class);

    public static final String COMMAND_TYPE = "crm:recompute_campaign_metrics";

    private static final String MODEL = "crm_campaign";
    private static final String MEMBER = "crm_campaign_member";

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
            throw new IllegalStateException("DataAccessor unavailable; cannot recompute campaign metrics");
        }
        String recordId = context.recordId();
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalStateException("No campaign record id on context");
        }
        Map<String, Object> campaign = db.getById(MODEL, recordId);
        if (campaign == null) {
            throw new IllegalStateException("Campaign not found: " + recordId);
        }

        List<Map<String, Object>> members = db.query(MEMBER, Map.of("crm_cm_campaign_id", recordId));
        int total = 0;
        int responded = 0;
        int converted = 0;
        if (members != null) {
            for (Map<String, Object> m : members) {
                total++;
                String st = str(m.get("crm_cm_status"));
                boolean hasResponded = m.get("crm_cm_responded_date") != null
                        && !str(m.get("crm_cm_responded_date")).isBlank();
                if ("converted".equals(st)) {
                    converted++;
                    responded++;
                } else if ("responded".equals(st) || hasResponded) {
                    responded++;
                }
            }
        }

        CampaignMetricsEngine.Result r = CampaignMetricsEngine.compute(
                new CampaignMetricsEngine.Input(total, responded, converted, bd(campaign.get("crm_cpn_budget"))));

        Map<String, Object> update = new HashMap<>();
        update.put("crm_cpn_member_count", total);
        update.put("crm_cpn_response_rate", r.responseRate());
        update.put("crm_cpn_conversion_rate", r.conversionRate());
        update.put("crm_cpn_cost_per_member", r.costPerMember());
        db.update(MODEL, recordId, update);

        log.info("Campaign {} metrics: members={} responded={} converted={} respRate={} convRate={} cpm={}",
                recordId, total, responded, converted, r.responseRate(), r.conversionRate(), r.costPerMember());

        return Map.of("campaignId", recordId, "memberCount", total,
                "responseRate", r.responseRate(), "conversionRate", r.conversionRate());
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
        return v == null ? "" : v.toString();
    }
}
