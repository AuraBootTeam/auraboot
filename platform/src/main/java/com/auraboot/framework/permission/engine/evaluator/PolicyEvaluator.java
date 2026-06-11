package com.auraboot.framework.permission.engine.evaluator;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.rule.ConditionSpec;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleEvaluationContext;
import com.auraboot.framework.decision.rule.RuleEvaluationService;
import com.auraboot.framework.decision.rule.RuleEvaluationTrace;
import com.auraboot.framework.permission.engine.model.EvaluationStep;
import com.auraboot.framework.permission.engine.model.EvaluationVerdict;
import com.auraboot.framework.permission.engine.policy.PolicyExpressionEvaluator;
import com.auraboot.framework.permission.engine.policy.PolicyViolation;
import com.auraboot.framework.permission.service.PermissionPolicyService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Policy evaluator -- checks parameter limits and business rules.
 *
 * <p>Evaluates ABAC-style policies such as amount limits, discount caps, etc.
 * Policies are configured per role+permission in ab_role_permission.conditions JSONB,
 * and merged across roles using permissive rules (max for limits, OR for booleans).
 *
 * <p>Supports two evaluation modes:
 * <ul>
 *   <li><b>Expression-based</b>: when policy_schema defines operator + field, delegates
 *       to {@link PolicyExpressionEvaluator} for operator evaluation</li>
 *   <li><b>Legacy convention</b>: when no schema or no operator defined, uses
 *       maxXxx/minXxx naming convention</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PolicyEvaluator {

    private static final String NAME = "Policy";

    private final PermissionPolicyService policyService;
    private final PolicyExpressionEvaluator expressionEvaluator;
    private final ObjectProvider<RuleEvaluationService> ruleEvaluationServiceProvider;
    private final ObjectMapper objectMapper;

    /**
     * Evaluate whether the operation satisfies policy constraints.
     *
     * @param memberId member (user) ID
     * @param resource resource identifier
     * @param action   action identifier
     * @param record   the target record (Map for field-level validation)
     * @return evaluation step with verdict
     */
    public EvaluationStep evaluate(Long memberId, String resource, String action, Object record) {
        String permissionCode = resource + ":" + action;
        Map<String, Object> policy = policyService.getEffectivePolicy(memberId, permissionCode);

        if (policy == null || policy.isEmpty()) {
            return new EvaluationStep(NAME, EvaluationVerdict.NOT_APPLICABLE, "No policy configured");
        }

        Map<?, ?> recordMap = record instanceof Map<?, ?> map ? map : Map.of();
        Map<String, Object> policySchema = policyService.getPolicySchema(permissionCode);
        List<String> violations = validateRecord(
                memberId,
                permissionCode,
                recordMap,
                policy,
                policySchema,
                new LinkedHashMap<>());
        if (!violations.isEmpty()) {
            return new EvaluationStep(NAME, EvaluationVerdict.DENY,
                    "Policy violations: " + String.join(", ", violations));
        }

        return new EvaluationStep(NAME, EvaluationVerdict.ALLOW, "Policy satisfied");
    }

    /**
     * Validate a record against policy constraints.
     *
     * <p>For each policy key:
     * <ol>
     *   <li>If policySchema defines a rule with operator + field for this key,
     *       delegate to {@link PolicyExpressionEvaluator}</li>
     *   <li>Otherwise, fall back to legacy maxXxx/minXxx naming convention</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private List<String> validateRecord(
            Long memberId,
            String permissionCode,
            Map<?, ?> record,
            Map<String, Object> policy,
            Map<String, Object> policySchema,
            Map<String, RuleEvaluationTrace> ruleEvaluationCache) {
        List<String> violations = new ArrayList<>();

        for (Map.Entry<String, Object> entry : policy.entrySet()) {
            String key = entry.getKey();
            Object policyValue = entry.getValue();

            // Try expression-based evaluation if schema defines this rule
            Map<String, Object> rule = getSchemaRule(policySchema, key);

            Optional<RuleAbacPolicy> abac = resolveRuleAbacPolicy(rule, policyValue);
            PolicyViolation violation = abac
                    .map(abacPolicy -> evaluateRuleCenterAbac(
                            memberId, permissionCode, key, abacPolicy, record, ruleEvaluationCache))
                    .orElseGet(() -> expressionEvaluator.evaluate(key, rule, policyValue, record));
            if (violation != null) {
                violations.add(violation.message());
            }
        }

        return violations;
    }

    /**
     * Evaluate rule-center-backed ABAC policies.
     *
     * <p>Supported JSON shapes, either under {@code ab_role_permission.conditions}
     * or under {@code ab_permission.policy_schema}:
     * <pre>
     * {
     *   "dynamicAbac": {
     *     "decisionBinding": { "decisionCode": "permission_guard", "timeoutMs": 50 },
     *     "expectedMatched": true
     *   }
     * }
     * </pre>
     *
     * <p>Permission ABAC is read-only. It does not apply decision output mappings;
     * it only reads the returned trace and fails closed on missing runtime, fallback,
     * error, timeout, or non-matching decision.
     */
    private PolicyViolation evaluateRuleCenterAbac(
            Long memberId,
            String permissionCode,
            String ruleKey,
            RuleAbacPolicy policy,
            Map<?, ?> record,
            Map<String, RuleEvaluationTrace> ruleEvaluationCache) {
        RuleEvaluationService ruleEvaluationService = ruleEvaluationServiceProvider.getIfAvailable();
        if (ruleEvaluationService == null) {
            return new PolicyViolation(ruleKey,
                    ruleKey + ": Rule center runtime unavailable for permission ABAC");
        }

        try {
            RuleEvaluationContext context = buildPermissionRuleContext(memberId, permissionCode, ruleKey, record);
            RuleEvaluationTrace trace = evaluatePolicyRule(
                    policy, context, ruleEvaluationService, record, ruleEvaluationCache);
            String violation = violationFromTrace(policy, trace);
            return violation == null ? null : new PolicyViolation(ruleKey, ruleKey + ": " + violation);
        } catch (RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new PolicyViolation(ruleKey,
                    ruleKey + ": Rule center ABAC failed closed: " + message);
        }
    }

    private RuleEvaluationContext buildPermissionRuleContext(
            Long memberId,
            String permissionCode,
            String ruleKey,
            Map<?, ?> record) {
        Map<Scope, Map<String, Object>> scopes = new EnumMap<>(Scope.class);
        scopes.put(Scope.RECORD, copyStringKeyMap(record));
        Map<String, Object> actor = new LinkedHashMap<>();
        if (memberId != null) {
            actor.put("memberId", memberId);
        }
        scopes.put(Scope.ACTOR, actor);
        scopes.put(Scope.TENANT, Map.of("tenantId", safeTenantId()));
        scopes.put(Scope.META, Map.of(
                "permissionCode", permissionCode,
                "policyKey", ruleKey,
                "readOnly", true));
        return new RuleEvaluationContext(
                scopes,
                "PERMISSION",
                permissionCode,
                ruleKey,
                null,
                null,
                null);
    }

    private RuleEvaluationTrace evaluatePolicyRule(
            RuleAbacPolicy policy,
            RuleEvaluationContext context,
            RuleEvaluationService ruleEvaluationService,
            Map<?, ?> record,
            Map<String, RuleEvaluationTrace> ruleEvaluationCache) {
        String cacheKey = policy.cacheKey(objectMapper, record);
        RuleEvaluationTrace cached = ruleEvaluationCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        RuleEvaluationTrace trace;
        if (policy.ruleBinding() != null) {
            RuleConsumerBinding binding = policy.ruleBinding();
            if (!binding.active()) {
                trace = disabledTrace(context, binding.decisionBinding());
            } else if (binding.bindingKind() == RuleBindingKind.CONDITION && binding.conditionSpec() != null) {
                trace = ruleEvaluationService.evaluateCondition(binding.conditionSpec(), context);
            } else {
                trace = evaluateCombinedRuleBinding(binding, context, ruleEvaluationService);
            }
        } else if (policy.conditionSpec() != null) {
            trace = ruleEvaluationService.evaluateCondition(policy.conditionSpec(), context);
        } else {
            trace = ruleEvaluationService.evaluateDecisionBinding(policy.decisionBinding(), context);
        }

        ruleEvaluationCache.put(cacheKey, trace);
        return trace;
    }

    private RuleEvaluationTrace evaluateCombinedRuleBinding(
            RuleConsumerBinding binding,
            RuleEvaluationContext context,
            RuleEvaluationService ruleEvaluationService) {
        if (binding.conditionSpec() != null) {
            RuleEvaluationTrace condition = ruleEvaluationService.evaluateCondition(binding.conditionSpec(), context);
            if (!condition.matched() || condition.errorCode() != null || condition.fallbackApplied()) {
                return condition;
            }
        }
        if (binding.decisionBinding() == null) {
            return disabledTrace(context, null);
        }
        return ruleEvaluationService.evaluateDecisionBinding(binding.decisionBinding(), context);
    }

    private RuleEvaluationTrace disabledTrace(RuleEvaluationContext context, DecisionBinding binding) {
        return new RuleEvaluationTrace(
                context.traceId(),
                context.consumerType(),
                context.consumerCode(),
                context.consumerNodeId(),
                binding == null ? RuleBindingKind.CONDITION : RuleBindingKind.DECISION_REF,
                binding == null ? null : binding.decisionCode(),
                null,
                binding == null ? null : binding.versionPolicy(),
                null,
                null,
                false,
                Map.of(),
                Map.of(),
                false,
                0L,
                "RULE_BINDING_DISABLED",
                List.of("Permission ABAC rule binding is disabled"),
                List.of(),
                List.of(),
                binding == null ? List.of() : List.of(binding.decisionCode()));
    }

    private String violationFromTrace(RuleAbacPolicy policy, RuleEvaluationTrace trace) {
        if (trace == null) {
            return "Rule center ABAC returned no trace";
        }
        int timeoutMs = policy.effectiveTimeoutMs();
        if (trace.durationMs() > timeoutMs) {
            return policy.label() + " timed out after " + trace.durationMs() + "ms"
                    + " (limit " + timeoutMs + "ms)";
        }
        if (trace.fallbackApplied()) {
            return policy.label() + " used fallback; permission ABAC is fail-closed";
        }
        if (trace.errorCode() != null) {
            return policy.label() + " returned error " + trace.errorCode();
        }
        if (trace.matched() != policy.expectedMatched()) {
            return policy.label() + " expected matched=" + policy.expectedMatched()
                    + " but was " + trace.matched();
        }
        return null;
    }

    private Map<String, Object> copyStringKeyMap(Map<?, ?> record) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (record != null) {
            record.forEach((key, value) -> {
                if (key != null) {
                    result.put(String.valueOf(key), value);
                }
            });
        }
        return result;
    }

    private Long safeTenantId() {
        try {
            Long tenantId = MetaContext.getCurrentTenantId();
            return tenantId == null ? -1L : tenantId;
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private Optional<RuleAbacPolicy> resolveRuleAbacPolicy(Map<String, Object> schemaRule, Object policyValue) {
        Map<String, Object> valueMap = mapValue(policyValue);
        if (valueMap != null && Boolean.FALSE.equals(valueMap.get("enabled"))) {
            return Optional.empty();
        }

        RuleConsumerBinding ruleBinding = firstRuleBinding(valueMap, schemaRule);
        DecisionBinding decisionBinding = ruleBinding == null ? firstDecisionBinding(valueMap, schemaRule) : null;
        ConditionSpec conditionSpec = ruleBinding == null ? firstConditionSpec(valueMap, schemaRule) : null;
        if (ruleBinding == null && decisionBinding == null && conditionSpec == null) {
            return Optional.empty();
        }

        boolean expectedMatched = booleanValue(valueMap, "expectedMatched",
                booleanValue(schemaRule, "expectedMatched", true));
        Integer timeoutMs = intValue(valueMap, "timeoutMs", intValue(schemaRule, "timeoutMs", null));
        return Optional.of(new RuleAbacPolicy(
                decisionBinding,
                conditionSpec,
                ruleBinding,
                expectedMatched,
                timeoutMs));
    }

    private RuleConsumerBinding firstRuleBinding(Map<String, Object> valueMap, Map<String, Object> schemaRule) {
        Object source = firstNested(valueMap, schemaRule, "ruleBinding");
        return source == null ? null : convertPolicyValue(source, RuleConsumerBinding.class);
    }

    private DecisionBinding firstDecisionBinding(Map<String, Object> valueMap, Map<String, Object> schemaRule) {
        Object source = firstNested(valueMap, schemaRule, "decisionBinding");
        if (source == null) {
            source = firstDirectDecisionBinding(valueMap, schemaRule);
        }
        return source == null ? null : convertPolicyValue(source, DecisionBinding.class);
    }

    private ConditionSpec firstConditionSpec(Map<String, Object> valueMap, Map<String, Object> schemaRule) {
        Object source = firstNested(valueMap, schemaRule, "conditionSpec");
        return source == null ? null : convertPolicyValue(source, ConditionSpec.class);
    }

    private Object firstNested(Map<String, Object> valueMap, Map<String, Object> schemaRule, String key) {
        if (valueMap != null && valueMap.get(key) != null) {
            return valueMap.get(key);
        }
        if (schemaRule != null && schemaRule.get(key) != null) {
            return schemaRule.get(key);
        }
        return null;
    }

    private Object firstDirectDecisionBinding(Map<String, Object> valueMap, Map<String, Object> schemaRule) {
        if (valueMap != null && valueMap.get("decisionCode") != null) {
            return valueMap;
        }
        if (schemaRule != null && schemaRule.get("decisionCode") != null) {
            return schemaRule;
        }
        return null;
    }

    private boolean booleanValue(Map<String, Object> source, String key, boolean fallback) {
        if (source == null || !source.containsKey(key)) {
            return fallback;
        }
        Object value = source.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    private Integer intValue(Map<String, Object> source, String key, Integer fallback) {
        if (source == null || !source.containsKey(key)) {
            return fallback;
        }
        Object value = source.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private <T> T convertPolicyValue(Object value, Class<T> type) {
        ObjectMapper mapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
        return mapper.convertValue(value, type);
    }

    private record RuleAbacPolicy(
            DecisionBinding decisionBinding,
            ConditionSpec conditionSpec,
            RuleConsumerBinding ruleBinding,
            boolean expectedMatched,
            Integer timeoutMs
    ) {
        private String label() {
            DecisionBinding binding = effectiveDecisionBinding();
            if (binding != null && binding.decisionCode() != null) {
                return binding.decisionCode();
            }
            return "Rule center ABAC";
        }

        private DecisionBinding effectiveDecisionBinding() {
            if (decisionBinding != null) {
                return decisionBinding;
            }
            return ruleBinding == null ? null : ruleBinding.decisionBinding();
        }

        private int effectiveTimeoutMs() {
            if (timeoutMs != null) {
                return Math.max(1, timeoutMs);
            }
            DecisionBinding binding = effectiveDecisionBinding();
            return binding == null ? 200 : Math.max(1, binding.timeoutMs());
        }

        private String cacheKey(ObjectMapper mapper, Map<?, ?> record) {
            try {
                return mapper.writeValueAsString(this) + "|" + mapper.writeValueAsString(record);
            } catch (Exception e) {
                return String.valueOf(hashCode()) + "|" + String.valueOf(record.hashCode());
            }
        }
    }

    /**
     * Get the schema rule definition for a given policy key.
     * Returns an empty map if no schema or no rule defined (triggers legacy evaluation).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> getSchemaRule(Map<String, Object> policySchema, String key) {
        if (policySchema != null) {
            Object ruleDef = policySchema.get(key);
            if (ruleDef instanceof Map<?, ?> ruleMap) {
                return (Map<String, Object>) ruleMap;
            }
        }
        // No schema or no rule for this key -- return empty map to trigger legacy evaluation
        return Map.of();
    }
}
