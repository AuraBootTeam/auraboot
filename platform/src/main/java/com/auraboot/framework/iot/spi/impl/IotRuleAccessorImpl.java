package com.auraboot.framework.iot.spi.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.plugin.extension.iot.BackgroundRuleAccessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Default {@link BackgroundRuleAccessor}: reads from {@code iot_rule}, filters
 * by scope and {@code iot_r_enabled=true} for the active query, and orders the
 * result deterministically (severity desc, code asc) for replay safety.
 *
 * @since 2.6.0
 */
@Slf4j
@Service
public class IotRuleAccessorImpl implements BackgroundRuleAccessor {

    static final String MODEL_CODE = "iot_rule";
    static final String COL_CODE = "iot_r_code";
    static final String COL_SCOPE = "iot_r_scope";
    static final String COL_SCOPE_KEY = "iot_r_scope_key";
    static final String COL_KIND = "iot_r_kind";
    static final String COL_EXPRESSION = "iot_r_expression";
    static final String COL_ACTIONS = "iot_r_actions_json";
    static final String COL_SEVERITY = "iot_r_severity";
    static final String COL_COOLDOWN = "iot_r_cooldown_seconds";
    static final String COL_ENABLED = "iot_r_enabled";

    private static final long SYSTEM_USER_ID = 0L;
    private static final int MAX_PER_SCOPE = 1000;

    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
            "CRITICAL", 4,
            "MAJOR", 3,
            "MINOR", 2,
            "WARNING", 1);

    private final DynamicDataService dynamicDataService;

    public IotRuleAccessorImpl(DynamicDataService dynamicDataService) {
        this.dynamicDataService = dynamicDataService;
    }

    @Override
    public List<RuleView> findActiveByScope(long tenantId, RuleScope scope, String scopeKey) {
        if (tenantId <= 0 || scope == null) {
            return List.of();
        }
        return withTenant(tenantId, () -> {
            List<QueryCondition> conds = new ArrayList<>();
            conds.add(eq(COL_SCOPE, scope.name()));
            conds.add(eq(COL_ENABLED, true));
            if (scope == RuleScope.TENANT) {
                conds.add(QueryCondition.builder()
                        .fieldName(COL_SCOPE_KEY)
                        .operator(QueryCondition.Operator.IS_NULL)
                        .build());
            } else {
                if (scopeKey == null || scopeKey.isBlank()) {
                    return List.<RuleView>of();
                }
                conds.add(eq(COL_SCOPE_KEY, scopeKey));
            }

            DynamicQueryRequest req = DynamicQueryRequest.builder()
                    .pageNum(1)
                    .pageSize(MAX_PER_SCOPE)
                    .conditions(conds)
                    .build();

            PaginationResult<Map<String, Object>> page;
            try {
                page = dynamicDataService.list(MODEL_CODE, req);
            } catch (RuntimeException e) {
                log.debug("[iot-rule-accessor] findActiveByScope failed scope={} key={}: {}",
                        scope, scopeKey, e.getMessage());
                return List.<RuleView>of();
            }
            List<Map<String, Object>> rows = page != null ? page.getRecords() : null;
            if (rows == null || rows.isEmpty()) {
                return List.<RuleView>of();
            }
            return rows.stream()
                    .map(this::toView)
                    .sorted(Comparator
                            .comparingInt((RuleView v) -> -severityRank(v.severity()))
                            .thenComparing(RuleView::code, Comparator.nullsLast(String::compareTo)))
                    .toList();
        });
    }

    @Override
    public Optional<RuleView> findByCode(long tenantId, String ruleCode) {
        if (tenantId <= 0 || ruleCode == null || ruleCode.isBlank()) {
            return Optional.empty();
        }
        return withTenant(tenantId, () -> {
            DynamicQueryRequest req = DynamicQueryRequest.builder()
                    .pageNum(1)
                    .pageSize(2)
                    .conditions(List.of(eq(COL_CODE, ruleCode)))
                    .build();
            try {
                PaginationResult<Map<String, Object>> page = dynamicDataService.list(MODEL_CODE, req);
                List<Map<String, Object>> rows = page != null ? page.getRecords() : null;
                if (rows == null || rows.isEmpty()) {
                    return Optional.<RuleView>empty();
                }
                return Optional.of(toView(rows.get(0)));
            } catch (RuntimeException e) {
                log.debug("[iot-rule-accessor] findByCode failed code={}: {}", ruleCode, e.getMessage());
                return Optional.<RuleView>empty();
            }
        });
    }

    private RuleView toView(Map<String, Object> r) {
        return new RuleView(
                IotDeviceAccessorImpl.asString(r.get(COL_CODE)),
                parseScope(r.get(COL_SCOPE)),
                IotDeviceAccessorImpl.asString(r.get(COL_SCOPE_KEY)),
                parseKind(r.get(COL_KIND)),
                strOrEmpty(r.get(COL_EXPRESSION)),
                strOrEmpty(r.get(COL_ACTIONS)),
                IotDeviceAccessorImpl.asString(r.get(COL_SEVERITY)),
                (int) IotDeviceAccessorImpl.asLong(r.get(COL_COOLDOWN)),
                asBoolean(r.get(COL_ENABLED)),
                IotDeviceAccessorImpl.asLong(r.get("tenant_id")));
    }

    private static String strOrEmpty(Object v) {
        if (v == null) {
            return "";
        }
        return v.toString();
    }

    private static RuleScope parseScope(Object v) {
        if (v == null) {
            return RuleScope.TENANT;
        }
        try {
            return RuleScope.valueOf(v.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RuleScope.TENANT;
        }
    }

    private static RuleKind parseKind(Object v) {
        if (v == null) {
            return RuleKind.SQL;
        }
        try {
            return RuleKind.valueOf(v.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RuleKind.SQL;
        }
    }

    private static int severityRank(String severity) {
        if (severity == null) {
            return 0;
        }
        return SEVERITY_RANK.getOrDefault(severity.toUpperCase(), 0);
    }

    private static QueryCondition eq(String field, Object value) {
        return QueryCondition.builder()
                .fieldName(field)
                .operator(QueryCondition.Operator.EQ)
                .value(value)
                .build();
    }

    private static boolean asBoolean(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof Number n) {
            return n.intValue() != 0;
        }
        String s = v.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
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
