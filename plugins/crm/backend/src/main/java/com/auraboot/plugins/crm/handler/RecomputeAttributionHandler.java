package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.AttributionEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recomputes a marketing-attribution record (CRM gap #13).
 *
 * <p>Wired as the {@code handler} of {@code crm:recompute_attribution} (custom).
 * Reads the ordered touchpoint campaign ids + revenue + model, delegates to the
 * pure {@link AttributionEngine}, and writes the per-touchpoint result JSON plus
 * the top-attributed campaign for display. Fail-loud (red line §8) on a missing
 * record or unparseable touchpoints.
 */
@Extension
public class RecomputeAttributionHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(RecomputeAttributionHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String COMMAND_TYPE = "crm:recompute_attribution";

    private static final String MODEL = "crm_attribution";

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
            throw new IllegalStateException("DataAccessor unavailable; cannot recompute attribution");
        }
        String recordId = context.recordId();
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalStateException("No attribution record id on context");
        }
        Map<String, Object> rec = db.getById(MODEL, recordId);
        if (rec == null) {
            throw new IllegalStateException("Attribution record not found: " + recordId);
        }

        List<String> touchpoints = parseTouchpoints(rec.get("crm_attr_touchpoints"));
        BigDecimal revenue = bd(rec.get("crm_attr_revenue"));
        String model = str(rec.get("crm_attr_model"));

        Map<String, BigDecimal> dist = AttributionEngine.distribute(touchpoints, revenue, model);

        String topCampaign = null;
        BigDecimal topAmount = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> e : dist.entrySet()) {
            if (topCampaign == null || e.getValue().compareTo(topAmount) > 0) {
                topCampaign = e.getKey();
                topAmount = e.getValue();
            }
        }

        Map<String, Object> update = new java.util.HashMap<>();
        update.put("crm_attr_result", MAPPER.writeValueAsString(dist));
        update.put("crm_attr_top_campaign", topCampaign);
        update.put("crm_attr_top_amount", topAmount);
        db.update(MODEL, recordId, update);

        log.info("Attribution {} recomputed: model={} touchpoints={} top={}({})",
                recordId, model, touchpoints.size(), topCampaign, topAmount);

        return Map.of("attributionId", recordId, "topCampaign", topCampaign == null ? "" : topCampaign,
                "topAmount", topAmount);
    }

    private static List<String> parseTouchpoints(Object raw) {
        if (raw == null) {
            return List.of();
        }
        String json = raw.toString().trim();
        if (json.isEmpty() || "[]".equals(json) || "null".equals(json)) {
            return List.of();
        }
        try {
            List<Object> rows = MAPPER.readValue(json, MAPPER.getTypeFactory()
                    .constructCollectionType(List.class, Object.class));
            List<String> out = new ArrayList<>();
            for (Object o : rows) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("Attribution touchpoints JSON is invalid (red line §8): "
                    + e.getMessage(), e);
        }
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
