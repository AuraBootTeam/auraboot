package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.bpm.rule.DroolsEngineService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DynamicQueryRequest;
import com.auraboot.framework.meta.dto.PaginationResult;
import com.auraboot.framework.meta.dto.QueryCondition;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPhase;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import com.auraboot.framework.meta.service.impl.pipeline.PreActionConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs pre-flight actions declared in {@code CommandDefinition.preActions} before
 * the payload is persisted. Sits between {@link AssertPhase} and
 * {@link PreInvariantPhase} so it executes inside the guarded-phase block but
 * before any state transition / roll-up / field-map work.
 *
 * <p>Currently supports a single action type, {@link PreActionConstants#TYPE_RUN_RULE}
 * ({@code "bpm:run-rule"}):
 * <pre>
 * {
 *   "type": "bpm:run-rule",
 *   "ruleCode": "wd_leave_validation",
 *   "contextLookup": [
 *     {
 *       "modelCode": "wd_leave_balance",
 *       "filters": [{"field": "wd_bal_employee", "op": "=", "value": "${payload.wd_req_applicant}"}],
 *       "exposeAs": "balance"
 *     }
 *   ],
 *   "facts": {
 *     "type": "${payload.wd_req_type}",
 *     "days": "${payload.wd_req_days}",
 *     "balanceRemaining": "${balance.wd_bal_annual_remaining}"
 *   }
 * }
 * </pre>
 *
 * <p>Placeholders {@code ${payload.*}} / {@code ${currentRecord.*}} /
 * {@code ${<exposeAs>.*}} are resolved against the current payload, any loaded
 * {@code currentRecord} snapshot, and the results of each {@code contextLookup}.
 * If the rule engine reports {@code valid == false}, a {@link BusinessException}
 * is raised with the reason as the i18n key — aborting the command before any
 * DB write happens.
 *
 * @since 7.3.0
 */
@Slf4j
@Component
@Order(750)
@RequiredArgsConstructor
public class PreActionsPhase implements CommandPhase {

    private static final String PLACEHOLDER_PREFIX = "${";
    private static final String PLACEHOLDER_SUFFIX = "}";
    private static final String SCOPE_PAYLOAD = "payload";
    private static final String SCOPE_CURRENT_RECORD = "currentRecord";
    private static final String RULE_RESULT_KEY_VALID = "valid";
    private static final String RULE_RESULT_KEY_REASON = "reason";
    private static final String FALLBACK_REASON_KEY = "bpm.rule.execution_failed";

    private final DroolsEngineService droolsEngineService;
    private final DynamicDataService dynamicDataService;

    @Override
    public String name() {
        return "pre_actions";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void execute(CommandPipelineContext ctx) {
        Map<String, Object> execConfig = ctx.getExecConfig();
        if (execConfig == null) {
            return;
        }
        Object raw = execConfig.get("preActions");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return;
        }

        Map<String, Object> payload = ctx.getPayload() != null ? ctx.getPayload() : Map.of();
        Map<String, Object> currentRecord = ctx.getBeforeSnapshot();

        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> action = (Map<String, Object>) rawMap;
            String type = String.valueOf(action.get("type"));

            if (PreActionConstants.TYPE_RUN_RULE.equals(type)) {
                executeRunRule(action, payload, currentRecord);
            } else {
                log.warn("Unknown preAction type: {}", type);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void executeRunRule(Map<String, Object> action,
                                Map<String, Object> payload,
                                Map<String, Object> currentRecord) {
        String ruleCode = asString(action.get("ruleCode"));
        if (ruleCode == null) {
            throw new BusinessException("bpm.rule.rule_code_required");
        }

        // Resolve contextLookup first, building scope map for placeholders.
        Map<String, Map<String, Object>> lookupScope = new HashMap<>();
        Object lookups = action.get("contextLookup");
        if (lookups instanceof List<?> lookupList) {
            for (Object l : lookupList) {
                if (!(l instanceof Map<?, ?> rawLookup)) continue;
                Map<String, Object> lookup = (Map<String, Object>) rawLookup;
                String exposeAs = asString(lookup.get("exposeAs"));
                String modelCode = asString(lookup.get("modelCode"));
                if (exposeAs == null || modelCode == null) continue;

                Map<String, Object> scope = new HashMap<>();
                scope.put(SCOPE_PAYLOAD, payload);
                if (currentRecord != null) scope.put(SCOPE_CURRENT_RECORD, currentRecord);

                List<QueryCondition> conditions =
                        resolveFilters((List<Map<String, Object>>) lookup.get("filters"), scope);
                DynamicQueryRequest request = DynamicQueryRequest.builder()
                        .pageNum(1).pageSize(1).conditions(conditions).build();
                PaginationResult<Map<String, Object>> result = dynamicDataService.list(modelCode, request);
                if (result != null && result.getRecords() != null && !result.getRecords().isEmpty()) {
                    lookupScope.put(exposeAs, result.getRecords().get(0));
                } else {
                    lookupScope.put(exposeAs, Map.of());
                }
            }
        }

        // Build combined scope for facts resolution
        Map<String, Object> fullScope = new HashMap<>();
        fullScope.put(SCOPE_PAYLOAD, payload);
        if (currentRecord != null) {
            fullScope.put(SCOPE_CURRENT_RECORD, currentRecord);
        }
        fullScope.putAll(lookupScope);

        Map<String, Object> factsTemplate = action.get("facts") instanceof Map
                ? (Map<String, Object>) action.get("facts")
                : new HashMap<>();
        Map<String, Object> facts = new HashMap<>();
        for (Map.Entry<String, Object> e : factsTemplate.entrySet()) {
            facts.put(e.getKey(), resolveValue(e.getValue(), fullScope));
        }

        Map<String, Object> ruleResult;
        try {
            ruleResult = droolsEngineService.evaluate(ruleCode, facts);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("preAction bpm:run-rule failed: ruleCode={}, error={}",
                    ruleCode, e.getMessage(), e);
            throw new BusinessException(FALLBACK_REASON_KEY);
        }

        if (ruleResult != null && Boolean.FALSE.equals(ruleResult.get(RULE_RESULT_KEY_VALID))) {
            Object reason = ruleResult.get(RULE_RESULT_KEY_REASON);
            String messageKey = (reason != null && !reason.toString().isBlank())
                    ? reason.toString()
                    : FALLBACK_REASON_KEY;
            throw new BusinessException(messageKey);
        }
    }

    private List<QueryCondition> resolveFilters(List<Map<String, Object>> filters,
                                                 Map<String, Object> scope) {
        List<QueryCondition> conditions = new ArrayList<>();
        if (filters == null) return conditions;
        for (Map<String, Object> f : filters) {
            String field = asString(f.get("field"));
            String op = asString(f.get("op"));
            Object resolvedValue = resolveValue(f.get("value"), scope);
            if (field == null || op == null) continue;
            conditions.add(QueryCondition.builder()
                    .fieldName(field)
                    .operator(mapOperator(op))
                    .value(resolvedValue)
                    .build());
        }
        return conditions;
    }

    private QueryCondition.Operator mapOperator(String op) {
        return switch (op) {
            case "=", "EQ", "eq" -> QueryCondition.Operator.EQ;
            case "!=", "NEQ", "ne" -> QueryCondition.Operator.NE;
            case ">", "GT", "gt" -> QueryCondition.Operator.GT;
            case ">=", "GE", "gte" -> QueryCondition.Operator.GE;
            case "<", "LT", "lt" -> QueryCondition.Operator.LT;
            case "<=", "LE", "lte" -> QueryCondition.Operator.LE;
            case "IN", "in" -> QueryCondition.Operator.IN;
            case "LIKE", "like" -> QueryCondition.Operator.LIKE;
            default -> QueryCondition.Operator.EQ;
        };
    }

    /**
     * Resolve a value that may be a {@code "${scope.field}"} placeholder string.
     * Non-string values pass through unchanged.
     */
    private Object resolveValue(Object raw, Map<String, Object> scope) {
        if (!(raw instanceof String s)) return raw;
        if (!(s.startsWith(PLACEHOLDER_PREFIX) && s.endsWith(PLACEHOLDER_SUFFIX))) return s;
        String expr = s.substring(PLACEHOLDER_PREFIX.length(), s.length() - PLACEHOLDER_SUFFIX.length()).trim();
        int dot = expr.indexOf('.');
        if (dot < 0) {
            return scope.get(expr);
        }
        String scopeKey = expr.substring(0, dot);
        String field = expr.substring(dot + 1);
        Object scopeObj = scope.get(scopeKey);
        if (scopeObj instanceof Map<?, ?> m) {
            return m.get(field);
        }
        return null;
    }

    private String asString(Object v) {
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }
}
