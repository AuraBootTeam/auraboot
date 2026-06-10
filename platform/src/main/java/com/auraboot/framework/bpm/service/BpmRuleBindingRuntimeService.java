package com.auraboot.framework.bpm.service;

import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleEvaluationContext;
import com.auraboot.framework.decision.rule.RuleEvaluationService;
import com.auraboot.framework.decision.rule.RuleEvaluationTrace;
import com.auraboot.framework.decision.rule.RuleMappingTarget;
import com.auraboot.smart.framework.engine.constant.RequestMapSpecialKeyConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime bridge between BPM SmartEngine execution and the platform rule-center
 * contract. BPM keeps its existing engine/designer kernel; this service only
 * evaluates node-level {@link RuleConsumerBinding} payloads carried in
 * {@code aura.ruleBinding}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BpmRuleBindingRuntimeService {

    private final RuleEvaluationService ruleEvaluationService;

    public Optional<RuleEvaluationTrace> evaluateAndApply(
            RuleConsumerBinding binding,
            String processKey,
            String nodeId,
            String processInstanceId,
            Map<String, Object> request) {
        Optional<RuleEvaluationTrace> trace = evaluate(binding, processKey, nodeId, processInstanceId, request);
        trace.ifPresent(t -> applyTrace(binding, nodeId, t, request));
        return trace;
    }

    public Optional<RuleEvaluationTrace> evaluate(
            RuleConsumerBinding binding,
            String processKey,
            String nodeId,
            String processInstanceId,
            Map<String, Object> request) {
        if (binding == null || !binding.active()) {
            return Optional.empty();
        }
        RuleEvaluationContext context = buildContext(binding, processKey, nodeId, processInstanceId, request);

        if (binding.conditionSpec() != null) {
            RuleEvaluationTrace conditionTrace =
                    ruleEvaluationService.evaluateCondition(binding.conditionSpec(), context);
            if (binding.bindingKind() == RuleBindingKind.CONDITION || binding.decisionBinding() == null) {
                return Optional.of(conditionTrace);
            }
            if (!conditionTrace.matched()) {
                return Optional.of(conditionTrace);
            }
        }

        if (binding.decisionBinding() != null) {
            return Optional.of(ruleEvaluationService.evaluateDecisionBinding(binding.decisionBinding(), context));
        }
        return Optional.empty();
    }

    public List<String> resolveTaskAssignees(
            RuleConsumerBinding binding,
            String processKey,
            String nodeId,
            String processInstanceId,
            Map<String, Object> request) {
        Optional<RuleEvaluationTrace> trace = evaluateAndApply(binding, processKey, nodeId, processInstanceId, request);
        if (trace.isEmpty() || !trace.get().matched()) {
            return List.of();
        }
        return extractAssigneeIds(binding, trace.get().outputSnapshot());
    }

    private RuleEvaluationContext buildContext(
            RuleConsumerBinding binding,
            String processKey,
            String nodeId,
            String processInstanceId,
            Map<String, Object> request) {
        Map<Scope, Map<String, Object>> scopes = new EnumMap<>(Scope.class);
        Map<String, Object> variables = request == null ? Map.of() : new LinkedHashMap<>(request);
        Map<String, Object> record = recordScope(variables);
        scopes.put(Scope.RECORD, record);
        scopes.put(Scope.PROCESS, Map.of(
                "processKey", processKey == null ? "" : processKey,
                "processInstanceId", processInstanceId == null ? "" : processInstanceId,
                "nodeId", nodeId == null ? "" : nodeId,
                "variables", variables));
        scopes.put(Scope.TASK, Map.of("nodeId", nodeId == null ? "" : nodeId));

        Object actorId = firstNonNull(
                variables.get(RequestMapSpecialKeyConstant.PROCESS_INSTANCE_START_USER_ID),
                variables.get("_startUserId"),
                variables.get("startUserId"));
        if (actorId != null) {
            scopes.put(Scope.ACTOR, Map.of("userId", actorId.toString()));
        }

        Object tenantId = variables.get(RequestMapSpecialKeyConstant.TENANT_ID);
        if (tenantId != null) {
            scopes.put(Scope.TENANT, Map.of("tenantId", tenantId.toString()));
        }

        return new RuleEvaluationContext(
                scopes,
                nonBlank(binding.consumerType(), "BPM"),
                nonBlank(binding.consumerCode(), processKey),
                nonBlank(binding.consumerNodeId(), nodeId),
                processInstanceId == null ? null : "bpm-" + processInstanceId + "-" + nodeId,
                stringValue(variables.get("traceId")),
                stringValue(variables.get("tenantSegment")));
    }

    private Map<String, Object> recordScope(Map<String, Object> variables) {
        Map<String, Object> record = new LinkedHashMap<>();
        Object recordObj = variables.get("record");
        if (recordObj instanceof Map<?, ?> recordMap) {
            record.putAll(castMap(recordMap));
        } else {
            record.putAll(variables);
        }
        if (!record.containsKey("businessKey")) {
            Object businessKey = variables.get(RequestMapSpecialKeyConstant.PROCESS_BIZ_UNIQUE_ID);
            if (businessKey != null) {
                record.put("businessKey", businessKey);
            }
        }
        record.putIfAbsent("data", new LinkedHashMap<>(record));
        return record;
    }

    private void applyTrace(
            RuleConsumerBinding binding,
            String nodeId,
            RuleEvaluationTrace trace,
            Map<String, Object> request) {
        if (request == null) {
            return;
        }
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("matched", trace.matched());
        decision.put("status", trace.decisionStatus() == null ? "UNKNOWN" : trace.decisionStatus().name());
        decision.put("outputs", trace.outputSnapshot());
        decision.put("traceId", trace.traceId() == null ? "" : trace.traceId());
        decision.put("fallbackApplied", trace.fallbackApplied());
        decision.put("errorCode", trace.errorCode() == null ? "" : trace.errorCode());

        putIfPossible(request, "decision", decision);
        putIfPossible(request, "_rule_" + safeKey(nodeId), Map.of(
                "bindingKind", trace.bindingKind() == null ? "" : trace.bindingKind().name(),
                "decisionCode", trace.decisionCode() == null ? "" : trace.decisionCode(),
                "matched", trace.matched(),
                "outputs", trace.outputSnapshot(),
                "traceId", trace.traceId() == null ? "" : trace.traceId()));

        DecisionBinding decisionBinding = binding == null ? null : binding.decisionBinding();
        if (decisionBinding == null || decisionBinding.outputMappings().isEmpty()) {
            return;
        }
        for (DecisionBinding.OutputMapping mapping : decisionBinding.outputMappings()) {
            if (mapping == null || mapping.output() == null || mapping.target() == null) {
                continue;
            }
            if (mapping.target().kind() != RuleMappingTarget.Kind.PROCESS_VARIABLE) {
                continue;
            }
            Object value = trace.outputSnapshot().get(mapping.output());
            if (value != null) {
                setPath(request, mapping.target().path(), value);
            }
        }
    }

    private List<String> extractAssigneeIds(RuleConsumerBinding binding, Map<String, Object> outputs) {
        if (outputs == null || outputs.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (binding != null && binding.decisionBinding() != null) {
            for (DecisionBinding.OutputMapping mapping : binding.decisionBinding().outputMappings()) {
                if (mapping == null || mapping.output() == null || mapping.target() == null) continue;
                String path = mapping.target().path();
                if (path != null && isAssigneeTarget(path)) {
                    addValues(result, outputs.get(mapping.output()));
                }
            }
        }
        for (String key : List.of(
                "assigneeUserId", "assigneeUserIds", "assigneeId", "assigneeIds",
                "assignee", "candidateUserIds", "candidateUsers", "userIds", "users")) {
            addValues(result, outputs.get(key));
        }
        return result.stream().filter(s -> !s.isBlank()).distinct().toList();
    }

    private boolean isAssigneeTarget(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.contains("assignee") || normalized.contains("candidateuser")
                || normalized.endsWith("userids") || normalized.endsWith("users");
    }

    private void addValues(List<String> target, Object value) {
        if (value == null) return;
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) target.add(item.toString().trim());
            }
            return;
        }
        String text = value.toString();
        for (String part : text.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isBlank()) {
                target.add(trimmed);
            }
        }
    }

    private void putIfPossible(Map<String, Object> request, String key, Object value) {
        try {
            request.put(key, value);
        } catch (UnsupportedOperationException e) {
            log.debug("BPM request map is immutable; cannot write rule binding variable {}", key);
        }
    }

    @SuppressWarnings("unchecked")
    private void setPath(Map<String, Object> request, String path, Object value) {
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            String[] parts = path.split("\\.");
            Map<String, Object> current = request;
            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                Object existing = current.get(part);
                if (!(existing instanceof Map<?, ?>)) {
                    Map<String, Object> next = new LinkedHashMap<>();
                    current.put(part, next);
                    current = next;
                } else {
                    current = (Map<String, Object>) existing;
                }
            }
            current.put(parts[parts.length - 1], value);
        } catch (UnsupportedOperationException e) {
            log.debug("BPM request map is immutable; cannot write rule binding output {}", path);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            if (key != null) result.put(key.toString(), value);
        });
        return result;
    }

    private Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private String stringValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String safeKey(String nodeId) {
        return nodeId == null ? "node" : nodeId.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
