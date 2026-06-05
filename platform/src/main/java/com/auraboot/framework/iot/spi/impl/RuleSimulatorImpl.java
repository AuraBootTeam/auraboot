package com.auraboot.framework.iot.spi.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.iot.broker.EmqxRuleTestService;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.iot.BackgroundDeviceAccessor.DeviceView;
import com.auraboot.framework.plugin.extension.iot.BackgroundProductAccessor;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleScope;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor.RuleView;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleSimulator;
import com.auraboot.framework.plugin.extension.iot.QueryParams;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default {@link BackgroundRuleSimulator}: faithful, side-effect-free dry-run of
 * an {@code iot_rule} over archived telemetry.
 *
 * <p>For {@code kind=SQL} rules it reconstructs each archived telemetry frame
 * (datapoint samples grouped by timestamp) and replays it through EMQX's own
 * synchronous {@code rule_test} endpoint ({@link EmqxRuleTestService}) — the
 * broker evaluates the exact stored rule SQL, so the would-fire count is the
 * real production verdict, not a divergent Java re-implementation (§8). No
 * alarm/Kafka/BPM is ever written.
 *
 * <p>{@code SMART_ENGINE}/{@code CHAIN} kinds are not production-functional today
 * (no driver loop / sink — see the BPM-external-events backlog); they throw a
 * structured {@code iot.error.rule_kind_not_production_evaluated:<kind>} rather
 * than fabricating a result.
 *
 * @since 2.6.0
 */
@Slf4j
@Service
public class RuleSimulatorImpl implements BackgroundRuleSimulator {

    private static final long SYSTEM_USER_ID = 0L;
    /** Upper bound on devices enumerated for PRODUCT/TENANT scope (cost guard). */
    private static final int MAX_DEVICES = 1000;
    private static final String SMART_ENGINE_BACKLOG =
            " (SMART_ENGINE has no production driver loop/sink; see docs/backlog/2026-06-05-bpm-external-events-smartengine.md)";
    /** Extract {@code payload.<field>} references from the rule SQL. */
    private static final Pattern PAYLOAD_FIELD = Pattern.compile("payload\\.([A-Za-z_][A-Za-z0-9_]*)");
    /** Extract the {@code FROM "<topic-filter>"} clause from the rule SQL. */
    private static final Pattern FROM_TOPIC = Pattern.compile("FROM\\s+\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    private final DynamicDataService dynamicDataService;
    private final BackgroundRuleAccessor ruleAccessor;
    private final BackgroundProductAccessor productAccessor;
    private final TimeSeriesPort timeSeriesPort;
    private final EmqxRuleTestService emqxRuleTest;
    private final ObjectMapper objectMapper;

    public RuleSimulatorImpl(DynamicDataService dynamicDataService,
                             BackgroundRuleAccessor ruleAccessor,
                             BackgroundProductAccessor productAccessor,
                             TimeSeriesPort timeSeriesPort,
                             EmqxRuleTestService emqxRuleTest,
                             ObjectMapper objectMapper) {
        this.dynamicDataService = dynamicDataService;
        this.ruleAccessor = ruleAccessor;
        this.productAccessor = productAccessor;
        this.timeSeriesPort = timeSeriesPort;
        this.emqxRuleTest = emqxRuleTest;
        this.objectMapper = objectMapper;
    }

    @Override
    public SimResult simulate(long tenantId, String ruleCode, SimWindow window) {
        if (tenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be > 0");
        }
        if (ruleCode == null || ruleCode.isBlank()) {
            throw new IllegalArgumentException("ruleCode must not be blank");
        }
        RuleView rule = ruleAccessor.findByCode(tenantId, ruleCode)
                .orElseThrow(() -> new MetaServiceException("iot.error.rule_not_found:" + ruleCode));

        if (rule.kind() != BackgroundRuleAccessor.RuleKind.SQL) {
            String suffix = rule.kind() == BackgroundRuleAccessor.RuleKind.SMART_ENGINE ? SMART_ENGINE_BACKLOG : "";
            throw new MetaServiceException(
                    "iot.error.rule_kind_not_production_evaluated:" + rule.kind() + suffix);
        }

        Set<String> sqlFields = extractPayloadFields(rule.expression());
        String simTopic = topicForRule(rule.expression());

        List<DeviceView> devices = resolveDevices(tenantId, rule.scope(), rule.scopeKey());
        List<WouldFire> fires = new ArrayList<>();
        int checked = 0;
        int devicesWithoutCodes = 0;

        outer:
        for (DeviceView device : devices) {
            if (checked >= window.maxSamples()) {
                break;
            }
            Set<String> codes = new LinkedHashSet<>(sqlFields);
            productAccessor.getSchema(device.productKey())
                    .ifPresent(schema -> schema.properties()
                            .forEach(p -> codes.add(p.identifier())));
            if (codes.isEmpty()) {
                devicesWithoutCodes++;
                continue;
            }

            List<TimeSeriesPoint> points = timeSeriesPort.queryRange(tenantId,
                    new QueryParams.Range(device.deviceCode(), new ArrayList<>(codes),
                            window.from(), window.to(), null));

            for (Map.Entry<Instant, Map<String, Object>> frame : groupByTimestamp(points).entrySet()) {
                if (checked >= window.maxSamples()) {
                    break outer;
                }
                checked++;
                Map<String, Object> payload = frame.getValue();
                if (emqxRuleTest.matches(rule.expression(), simTopic, toJson(payload))) {
                    fires.add(new WouldFire(device.deviceCode(), rule.code(), rule.severity(),
                            frame.getKey(), payload));
                }
            }
        }

        String note = "evaluated " + checked + " telemetry frame(s) across " + devices.size()
                + " device(s) via EMQX rule_test dry-run; "
                + fires.size() + " would fire; no alarm/Kafka/BPM emitted"
                + (devicesWithoutCodes > 0 ? "; " + devicesWithoutCodes + " device(s) skipped (no datapoint codes)" : "");
        log.info("[iot-rule-sim] rule={} tenant={} checked={} wouldFire={}",
                rule.code(), tenantId, checked, fires.size());
        return new SimResult(rule.code(), rule.kind().name(), checked, fires, note);
    }

    /** Enumerate non-DISABLE devices in scope (DEVICE → one; PRODUCT → by product; TENANT → all). */
    private List<DeviceView> resolveDevices(long tenantId, RuleScope scope, String scopeKey) {
        List<QueryCondition> conds = new ArrayList<>();
        conds.add(QueryCondition.builder()
                .fieldName(IotDeviceAccessorImpl.COL_STATUS)
                .operator(QueryCondition.Operator.NE)
                .value("DISABLE")
                .build());
        switch (scope) {
            case DEVICE -> {
                if (scopeKey == null || scopeKey.isBlank()) {
                    return List.of();
                }
                conds.add(eq(IotDeviceAccessorImpl.COL_DEVICE_CODE, scopeKey));
            }
            case PRODUCT -> {
                if (scopeKey == null || scopeKey.isBlank()) {
                    return List.of();
                }
                conds.add(eq(IotDeviceAccessorImpl.COL_PRODUCT_KEY, scopeKey));
            }
            case TENANT -> {
                // all non-DISABLE devices for the tenant
            }
            default -> {
                return List.of();
            }
        }
        DynamicQueryRequest req = DynamicQueryRequest.builder()
                .pageNum(1)
                .pageSize(MAX_DEVICES)
                .conditions(conds)
                .build();
        PaginationResult<Map<String, Object>> page = withTenant(tenantId,
                () -> dynamicDataService.list(IotDeviceAccessorImpl.MODEL_CODE, req));
        List<Map<String, Object>> rows = page != null ? page.getRecords() : null;
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<DeviceView> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            out.add(IotDeviceAccessorImpl.toViewFromRow(row));
        }
        return out;
    }

    /** Group datapoint samples into per-timestamp telemetry frames (insertion order preserved). */
    private static LinkedHashMap<Instant, Map<String, Object>> groupByTimestamp(List<TimeSeriesPoint> points) {
        LinkedHashMap<Instant, Map<String, Object>> byTs = new LinkedHashMap<>();
        if (points == null) {
            return byTs;
        }
        for (TimeSeriesPoint p : points) {
            if (p.ts() == null) {
                continue;
            }
            byTs.computeIfAbsent(p.ts(), k -> new LinkedHashMap<>()).put(p.code(), p.value());
        }
        return byTs;
    }

    static Set<String> extractPayloadFields(String sql) {
        Set<String> out = new LinkedHashSet<>();
        if (sql == null) {
            return out;
        }
        Matcher m = PAYLOAD_FIELD.matcher(sql);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    /**
     * Build a concrete MQTT topic that satisfies the rule's {@code FROM} filter,
     * so EMQX's topic match passes and the {@code WHERE} clause is what decides
     * firing. Wildcards are substituted with concrete segments; if the SQL has no
     * parseable {@code FROM}, a canonical telemetry topic is used.
     */
    static String topicForRule(String sql) {
        Matcher m = sql == null ? null : FROM_TOPIC.matcher(sql);
        if (m == null || !m.find()) {
            return "t/sim/p/sim/d/sim/telemetry";
        }
        String filter = m.group(1).trim();
        // A FROM may list multiple comma-separated topic filters; take the first.
        int comma = filter.indexOf(',');
        if (comma >= 0) {
            filter = filter.substring(0, comma).trim();
        }
        filter = stripQuotes(filter);
        StringBuilder topic = new StringBuilder(filter.length());
        String[] segments = filter.split("/", -1);
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                topic.append('/');
            }
            String seg = segments[i];
            if ("+".equals(seg)) {
                topic.append("sim");
            } else if ("#".equals(seg)) {
                topic.append("telemetry");
            } else {
                topic.append(seg);
            }
        }
        return topic.length() == 0 ? "t/sim/p/sim/d/sim/telemetry" : topic.toString();
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new MetaServiceException("iot.error.rule_sim_payload_encode_failed", e);
        }
    }

    private static QueryCondition eq(String field, Object value) {
        return QueryCondition.builder()
                .fieldName(field)
                .operator(QueryCondition.Operator.EQ)
                .value(value)
                .build();
    }

    private <T> T withTenant(long tenantId, java.util.function.Supplier<T> work) {
        boolean had = MetaContext.exists();
        Long priorTenant = had ? MetaContext.getCurrentTenantId() : null;
        Long priorUser = had ? MetaContext.getCurrentUserId() : null;
        String priorUserPid = had ? MetaContext.getCurrentUserPid() : null;
        String priorUsername = had ? MetaContext.getCurrentUsername() : null;
        java.util.Set<Long> priorRoles = had ? MetaContext.getCurrentRoleIds() : java.util.Set.of();
        MetaContext.setContext(tenantId, SYSTEM_USER_ID, null, "system");
        try {
            return work.get();
        } finally {
            if (had) {
                MetaContext.setContext(priorTenant, priorUser, priorUserPid, priorUsername, priorRoles);
            } else {
                MetaContext.clear();
            }
        }
    }
}
