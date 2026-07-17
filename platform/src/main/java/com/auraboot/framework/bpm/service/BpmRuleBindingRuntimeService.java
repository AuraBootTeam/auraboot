package com.auraboot.framework.bpm.service;

import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.model.DecisionStatus;
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
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<ExecutionLogService> executionLogServiceProvider;

    public Optional<RuleEvaluationTrace> evaluateAndApply(
            RuleConsumerBinding binding,
            String processKey,
            String nodeId,
            String processInstanceId,
            Map<String, Object> request) {
        Optional<RuleEvaluationTrace> trace = evaluate(binding, processKey, nodeId, processInstanceId, request);
        trace.ifPresent(t -> {
            applyTrace(binding, nodeId, t, request);
            recordTrace(processInstanceId, nodeId, t);
        });
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
        return resolveTaskAssignment(binding, processKey, nodeId, processInstanceId, request).userIds();
    }

    public TaskAssignmentResult resolveTaskAssignment(
            RuleConsumerBinding binding,
            String processKey,
            String nodeId,
            String processInstanceId,
            Map<String, Object> request) {
        Optional<RuleEvaluationTrace> trace = evaluateAndApply(binding, processKey, nodeId, processInstanceId, request);
        if (trace.isEmpty()) {
            return TaskAssignmentResult.empty();
        }
        RuleEvaluationTrace evaluationTrace = trace.get();
        boolean failClosed = isFailClosedFallback(binding, evaluationTrace);
        if (failClosed) {
            return new TaskAssignmentResult(List.of(), List.of(), true, evaluationTrace);
        }
        if (!outputsUsable(binding, evaluationTrace)) {
            return new TaskAssignmentResult(List.of(), List.of(), false, evaluationTrace);
        }
        return extractTaskAssignment(binding, evaluationTrace.outputSnapshot(), evaluationTrace);
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
        if (variables.get("meta") instanceof Map<?, ?> meta) {
            scopes.put(Scope.META, castMap(meta));
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

    private void recordTrace(String processInstanceId, String nodeId, RuleEvaluationTrace trace) {
        if (processInstanceId == null || processInstanceId.isBlank() || trace == null) {
            return;
        }
        ExecutionLogService executionLogService = executionLogServiceProvider.getIfAvailable();
        if (executionLogService == null) {
            return;
        }
        try {
            executionLogService.logRuleEvaluated(
                    processInstanceId,
                    nodeId,
                    Map.of("ruleBinding", ruleBindingTracePayload(trace)),
                    trace.durationMs());
        } catch (RuntimeException e) {
            log.warn("Failed to persist BPM rule binding trace: processInstanceId={}, nodeId={}, traceId={}",
                    processInstanceId, nodeId, trace.traceId(), e);
        }
    }

    private Map<String, Object> ruleBindingTracePayload(RuleEvaluationTrace trace) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("traceId", trace.traceId() == null ? "" : trace.traceId());
        payload.put("consumerType", trace.consumerType() == null ? "" : trace.consumerType());
        payload.put("consumerCode", trace.consumerCode() == null ? "" : trace.consumerCode());
        payload.put("consumerNodeId", trace.consumerNodeId() == null ? "" : trace.consumerNodeId());
        payload.put("bindingKind", trace.bindingKind() == null ? "" : trace.bindingKind().name());
        payload.put("decisionCode", trace.decisionCode() == null ? "" : trace.decisionCode());
        payload.put("version", trace.decisionVersion());
        payload.put("versionPolicy", trace.versionPolicy() == null ? "" : trace.versionPolicy().name());
        payload.put("status", trace.decisionStatus() == null ? "UNKNOWN" : trace.decisionStatus().name());
        payload.put("matched", trace.matched());
        payload.put("inputs", trace.inputSnapshot() == null ? Map.of() : trace.inputSnapshot());
        payload.put("outputs", trace.outputSnapshot() == null ? Map.of() : trace.outputSnapshot());
        payload.put("fallbackApplied", trace.fallbackApplied());
        payload.put("durationMs", trace.durationMs());
        payload.put("errorCode", trace.errorCode() == null ? "" : trace.errorCode());
        payload.put("errors", trace.errors() == null ? List.of() : trace.errors());
        return payload;
    }

    private TaskAssignmentResult extractTaskAssignment(
            RuleConsumerBinding binding,
            Map<String, Object> outputs,
            RuleEvaluationTrace trace) {
        if (outputs == null || outputs.isEmpty()) {
            return new TaskAssignmentResult(List.of(), List.of(), false, trace);
        }
        List<String> userIds = new ArrayList<>();
        List<String> groupIds = new ArrayList<>();
        if (binding != null && binding.decisionBinding() != null) {
            for (DecisionBinding.OutputMapping mapping : binding.decisionBinding().outputMappings()) {
                if (mapping == null || mapping.output() == null || mapping.target() == null) continue;
                String path = mapping.target().path();
                if (path != null && isUserAssignmentTarget(path)) {
                    addValues(userIds, outputs.get(mapping.output()));
                } else if (path != null && isGroupAssignmentTarget(path)) {
                    addValues(groupIds, outputs.get(mapping.output()));
                }
            }
        }
        for (String key : List.of(
                "assigneeUserId", "assigneeUserIds", "assigneeId", "assigneeIds",
                "assignee", "candidateUserIds", "candidateUsers", "userIds", "users")) {
            addValues(userIds, outputs.get(key));
        }
        for (String key : List.of(
                "candidateGroupIds", "candidateGroups", "candidateGroup",
                "groups", "groupIds", "assigneeGroup", "assigneeGroupId")) {
            addValues(groupIds, outputs.get(key));
        }
        return new TaskAssignmentResult(
                distinctNonBlank(userIds),
                distinctNonBlank(groupIds),
                false,
                trace);
    }

    private boolean outputsUsable(RuleConsumerBinding binding, RuleEvaluationTrace trace) {
        if (trace.matched()) {
            return true;
        }
        DecisionBinding decisionBinding = binding == null ? null : binding.decisionBinding();
        return trace.fallbackApplied()
                && decisionBinding != null
                && decisionBinding.fallbackPolicy().mode() == DecisionBinding.FallbackMode.DEFAULT_VALUE
                && !trace.outputSnapshot().isEmpty();
    }

    private boolean isFailClosedFallback(RuleConsumerBinding binding, RuleEvaluationTrace trace) {
        if (trace == null) {
            return false;
        }
        DecisionBinding decisionBinding = binding == null ? null : binding.decisionBinding();
        DecisionBinding.FallbackMode fallbackMode = decisionBinding == null
                ? DecisionBinding.FallbackMode.FAIL_CLOSED
                : decisionBinding.fallbackPolicy().mode();
        boolean failed = trace.fallbackApplied()
                || trace.decisionStatus() == DecisionStatus.ERROR
                || (trace.errorCode() != null && !trace.errorCode().isBlank());
        return failed && fallbackMode == DecisionBinding.FallbackMode.FAIL_CLOSED;
    }

    private boolean isUserAssignmentTarget(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.equals("assignee")
                || normalized.contains("assigneeuser") || normalized.contains("candidateuser")
                || normalized.endsWith("userids") || normalized.endsWith("users");
    }

    private boolean isGroupAssignmentTarget(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);
        return normalized.contains("assigneegroup") || normalized.contains("candidategroup")
                || normalized.endsWith("groupids") || normalized.endsWith("groups");
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

    private List<String> distinctNonBlank(List<String> values) {
        return values.stream().filter(s -> !s.isBlank()).distinct().toList();
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

    public record TaskAssignmentResult(
            List<String> userIds,
            List<String> groupIds,
            boolean failClosed,
            RuleEvaluationTrace trace
    ) {
        public TaskAssignmentResult {
            userIds = userIds == null ? List.of() : List.copyOf(userIds);
            groupIds = groupIds == null ? List.of() : List.copyOf(groupIds);
        }

        public boolean hasCandidates() {
            return !userIds.isEmpty() || !groupIds.isEmpty();
        }

        public static TaskAssignmentResult empty() {
            return new TaskAssignmentResult(List.of(), List.of(), false, null);
        }
    }
}
