package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.SubscriptionEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Recomputes a subscription's MRR / ARR / status (CRM gap #15).
 *
 * <p>Wired as the {@code handler} of {@code crm:recompute_subscription} (custom).
 * Derives days-until-end and started flag from the subscription dates, delegates
 * to the pure {@link SubscriptionEngine}, and writes mrr/arr/status back.
 * Fail-loud (red line §8) on a missing record.
 */
@Extension
public class RecomputeSubscriptionHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(RecomputeSubscriptionHandler.class);

    public static final String COMMAND_TYPE = "crm:recompute_subscription";

    private static final String MODEL = "crm_subscription";

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
            throw new IllegalStateException("DataAccessor unavailable; cannot recompute subscription");
        }
        String recordId = context.recordId();
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalStateException("No subscription record id on context");
        }
        Map<String, Object> rec = db.getById(MODEL, recordId);
        if (rec == null) {
            throw new IllegalStateException("Subscription not found: " + recordId);
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate start = parseDate(rec.get("crm_sub_start_date"));
        LocalDate end = parseDate(rec.get("crm_sub_end_date"));
        boolean started = start == null || !today.isBefore(start);
        int daysUntilEnd = end == null ? Integer.MAX_VALUE
                : (int) ChronoUnit.DAYS.between(today, end);

        SubscriptionEngine.Result r = SubscriptionEngine.compute(
                new SubscriptionEngine.Input(bd(rec.get("crm_sub_monthly_amount")), daysUntilEnd, started));

        Map<String, Object> update = new HashMap<>();
        update.put("crm_sub_mrr", r.mrr());
        update.put("crm_sub_arr", r.arr());
        update.put("crm_sub_status", r.status());
        db.update(MODEL, recordId, update);

        log.info("Subscription {} recomputed: mrr={} arr={} status={} (daysUntilEnd={} started={})",
                recordId, r.mrr(), r.arr(), r.status(), daysUntilEnd, started);

        return Map.of("subscriptionId", recordId, "mrr", r.mrr(), "arr", r.arr(), "status", r.status());
    }

    private static LocalDate parseDate(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            if (s.length() >= 10 && s.charAt(4) == '-') {
                return LocalDate.parse(s.substring(0, 10));
            }
            return Instant.parse(s).atZone(ZoneOffset.UTC).toLocalDate();
        } catch (Exception e) {
            return null;
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
}
