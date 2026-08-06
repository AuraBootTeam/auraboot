package com.auraboot.plugins.crm.handler;

import com.auraboot.plugins.crm.engine.OmniRoutingEngine;
import com.auraboot.framework.plugin.extension.CommandHandlerExtension;
import com.auraboot.framework.plugin.extension.DataAccessor;
import org.pf4j.Extension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Routes an omnichannel session to a queue + priority (CRM gap #16).
 *
 * <p>Wired as the {@code handler} of {@code crm:route_session} (custom). Reads
 * the session channel + VIP flag, delegates to the pure
 * {@link OmniRoutingEngine}, and writes the resolved queue + priority back.
 * Fail-loud (red line §8) on a missing record.
 */
@Extension
public class RouteSessionHandler implements CommandHandlerExtension {

    private static final Logger log = LoggerFactory.getLogger(RouteSessionHandler.class);

    public static final String COMMAND_TYPE = "crm:route_session";

    private static final String MODEL = "crm_omni_session";

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
            throw new IllegalStateException("DataAccessor unavailable; cannot route session");
        }
        String recordId = context.recordId();
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalStateException("No session record id on context");
        }
        Map<String, Object> rec = db.getById(MODEL, recordId);
        if (rec == null) {
            throw new IllegalStateException("Omni session not found: " + recordId);
        }

        String channel = str(rec.get("crm_oss_channel"));
        boolean vip = truthy(rec.get("crm_oss_vip"));
        OmniRoutingEngine.Result r = OmniRoutingEngine.route(channel, vip);

        Map<String, Object> update = new HashMap<>();
        update.put("crm_oss_queue", r.queue());
        update.put("crm_oss_priority", r.priority());
        db.update(MODEL, recordId, update);

        log.info("Omni session {} routed: channel={} vip={} -> queue={} priority={}",
                recordId, channel, vip, r.queue(), r.priority());

        return Map.of("sessionId", recordId, "queue", r.queue(), "priority", r.priority());
    }

    private static boolean truthy(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = v.toString().trim().toLowerCase();
        return "true".equals(s) || "1".equals(s) || "yes".equals(s) || "y".equals(s);
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
