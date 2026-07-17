package com.auraboot.framework.eventpolicy.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.decision.ast.DecisionContext;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.ast.Truth;
import com.auraboot.framework.decision.model.VersionStatus;
import com.auraboot.framework.decision.rule.RuleEvaluationContext;
import com.auraboot.framework.decision.rule.RuleEvaluationService;
import com.auraboot.framework.decision.rule.RuleEvaluationTrace;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyDefinitionEntity;
import com.auraboot.framework.eventpolicy.entity.DrtPolicyVersionEntity;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyDefinitionMapper;
import com.auraboot.framework.eventpolicy.mapper.DrtPolicyVersionMapper;
import com.auraboot.framework.eventpolicy.model.ConflictStrategy;
import com.auraboot.framework.eventpolicy.model.DedupStrategy;
import com.auraboot.framework.eventpolicy.model.EventPolicy;
import com.auraboot.framework.eventpolicy.model.EventPolicyResult;
import com.auraboot.framework.eventpolicy.model.ExecutionMode;
import com.auraboot.framework.eventpolicy.model.FailureStrategy;
import com.auraboot.framework.eventpolicy.model.MatchMode;
import com.auraboot.framework.eventpolicy.model.PolicyPhase;
import com.auraboot.framework.eventpolicy.model.PolicyRule;
import com.auraboot.framework.eventpolicy.runtime.EventPolicyEvaluator;
import com.auraboot.framework.eventpolicy.service.EventPolicyRuntimeService;
import com.auraboot.framework.exception.ValidationException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * EventPolicy runtime service: resolves the PUBLISHED policy, builds the domain object,
 * evaluates it via {@link EventPolicyEvaluator}, and returns the result.
 *
 * <p>§8 compliance: no catch-and-swallow; missing policy returns NOT_MATCHED (not a system error).
 * Parse errors on rules_json surface as errors (a published version should already be valid).
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventPolicyRuntimeServiceImpl implements EventPolicyRuntimeService {

    private final DrtPolicyDefinitionMapper definitionMapper;
    private final DrtPolicyVersionMapper versionMapper;
    private final ObjectMapper objectMapper;
    private final com.auraboot.framework.eventpolicy.executor.PolicyExecutor policyExecutor;
    private final ObjectProvider<RuleEvaluationService> ruleEvaluationServiceProvider;

    private final EventPolicyEvaluator evaluator = new EventPolicyEvaluator();

    // ─── public API ──────────────────────────────────────────────────────────

    @Override
    public EventPolicyResult run(String eventType, String targetType, String targetKey,
                                  Map<String, Map<String, Object>> context) {
        Long tid = requireTenant();

        // 1. Resolve the policy definition
        List<DrtPolicyDefinitionEntity> defs = definitionMapper.findByEventAndTarget(
                tid, eventType, targetType, targetKey);

        if (defs.isEmpty()) {
            log.debug("No event policy found for eventType={}, targetType={}, targetKey={}",
                    eventType, targetType, targetKey);
            // Return a synthetic NOT_MATCHED rather than throwing — no policy is a valid state
            return new EventPolicyResult("__none__", EventPolicyResult.Status.NOT_MATCHED,
                    List.of(), List.of(), List.of(), List.of());
        }

        // Use first matching policy (one active policy per event+target by convention)
        DrtPolicyDefinitionEntity def = defs.get(0);

        // 2. Resolve the PUBLISHED version
        DrtPolicyVersionEntity ver = versionMapper.findPublished(tid, def.getPolicyCode());
        if (ver == null) {
            log.debug("No published version for policy={}", def.getPolicyCode());
            return new EventPolicyResult(def.getPolicyCode(), EventPolicyResult.Status.NOT_MATCHED,
                    List.of(), List.of(), List.of(), List.of());
        }

        // Sanity check — findPublished already filters by status='PUBLISHED'
        VersionStatus status = VersionStatus.valueOf(ver.getStatus());
        if (!status.isBindable()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Event policy version is not bindable (status=" + status + ")");
        }

        // 3. Deserialize rules_json → List<PolicyRule>
        List<PolicyRule> rules;
        try {
            rules = objectMapper.convertValue(
                    ver.getRulesJson(),
                    new TypeReference<List<PolicyRule>>() {});
        } catch (Exception e) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Failed to deserialize rules for policy " + def.getPolicyCode() + ": " + e.getMessage());
        }

        // 4. Build EventPolicy domain object from version columns + rules
        EventPolicy policy = new EventPolicy(
                def.getPolicyCode(),
                def.getPolicyName(),
                def.getEventType(),
                def.getTargetType(),
                def.getTargetKey(),
                PolicyPhase.valueOf(ver.getPhase()),
                MatchMode.valueOf(ver.getMatchMode()),
                ExecutionMode.valueOf(ver.getExecutionMode()),
                FailureStrategy.valueOf(ver.getFailureStrategy()),
                ConflictStrategy.valueOf(ver.getConflictStrategy()),
                DedupStrategy.valueOf(ver.getDedupStrategy()),
                def.getEnabled() != null && def.getEnabled(),
                rules
        );

        // 5. Build DecisionContext from context map (mirroring DecisionEvaluationServiceImpl.buildContext)
        DecisionContext ctx = buildContext(context);

        // 6. Evaluate
        String correlationId = "ep-" + UniqueIdGenerator.generate();
        List<String> decisionTraceIds = new ArrayList<>();
        EventPolicyResult result = evaluatorFor(context, correlationId, decisionTraceIds)
                .evaluate(policy, ctx)
                .withRuntimeTrace(correlationId, decisionTraceIds);

        log.info("EventPolicy evaluated: code={}, version={}, status={}",
                def.getPolicyCode(), ver.getVersion(), result.status());

        return result;
    }

    @Override
    public com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult runAndExecute(
            String eventType, String targetType, String targetKey, Map<String, Map<String, Object>> context) {
        EventPolicyResult result = run(eventType, targetType, targetKey, context);
        if (result.status() != EventPolicyResult.Status.MATCHED
                || result.actionPlans() == null || result.actionPlans().isEmpty()) {
            return new com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult(result,
                    new com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult(
                            result.policyCode(),
                            com.auraboot.framework.eventpolicy.executor.PolicyExecutionResult.OverallStatus.NOTHING_TO_DO,
                            List.of()));
        }
        Long tid = requireTenant();
        FailureStrategy fs = resolveFailureStrategy(tid, eventType, targetType, targetKey);
        DecisionContext ctx = buildContext(context);
        var exec = policyExecutor.execute(
                result,
                ctx,
                fs,
                tid,
                result.primaryDecisionTraceId(),
                result.correlationId());
        log.info("EventPolicy executed: code={}, overall={}", result.policyCode(), exec.overallStatus());
        return new com.auraboot.framework.eventpolicy.model.EventPolicyExecutionResult(result, exec);
    }

    /** Re-resolve the published version's FailureStrategy (defaults to CONTINUE_ON_ERROR). */
    private FailureStrategy resolveFailureStrategy(Long tid, String eventType, String targetType, String targetKey) {
        List<DrtPolicyDefinitionEntity> defs = definitionMapper.findByEventAndTarget(tid, eventType, targetType, targetKey);
        if (defs.isEmpty()) {
            return FailureStrategy.CONTINUE_ON_ERROR;
        }
        DrtPolicyVersionEntity ver = versionMapper.findPublished(tid, defs.get(0).getPolicyCode());
        return ver != null && ver.getFailureStrategy() != null
                ? FailureStrategy.valueOf(ver.getFailureStrategy())
                : FailureStrategy.CONTINUE_ON_ERROR;
    }

    // ─── context building ────────────────────────────────────────────────────

    /**
     * Mirror of DecisionEvaluationServiceImpl.buildContext: iterate context map,
     * parse scope keys via Scope.valueOf(key.toUpperCase()), silently skip unknown scopes.
     */
    private DecisionContext buildContext(Map<String, Map<String, Object>> raw) {
        Map<Scope, Map<String, Object>> scopes = buildScopes(raw);
        if (scopes.isEmpty()) {
            return DecisionContext.of(Map.of());
        }
        DecisionContext.Builder builder = DecisionContext.builder();
        scopes.forEach(builder::scope);
        return builder.build();
    }

    private Map<Scope, Map<String, Object>> buildScopes(Map<String, Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<Scope, Map<String, Object>> scopes = new EnumMap<>(Scope.class);
        for (Map.Entry<String, Map<String, Object>> entry : raw.entrySet()) {
            try {
                Scope scope = Scope.valueOf(entry.getKey().toUpperCase());
                scopes.put(scope, entry.getValue() == null ? Map.of() : new LinkedHashMap<>(entry.getValue()));
            } catch (IllegalArgumentException ignored) {
                log.debug("Skipping unknown context scope: {}", entry.getKey());
            }
        }
        return Map.copyOf(scopes);
    }

    private EventPolicyEvaluator evaluatorFor(Map<String, Map<String, Object>> rawContext,
                                              String correlationId,
                                              List<String> decisionTraceIds) {
        RuleEvaluationService ruleEvaluationService = ruleEvaluationServiceProvider.getIfAvailable();
        if (ruleEvaluationService == null) {
            return evaluator;
        }
        Map<Scope, Map<String, Object>> scopes = buildScopes(rawContext);
        return new EventPolicyEvaluator((policy, rule, context) ->
                evaluateDecisionBinding(ruleEvaluationService, policy, rule, scopes, correlationId, decisionTraceIds));
    }

    private Truth evaluateDecisionBinding(RuleEvaluationService ruleEvaluationService,
                                          EventPolicy policy,
                                          PolicyRule rule,
                                          Map<Scope, Map<String, Object>> scopes,
                                          String correlationId,
                                          List<String> decisionTraceIds) {
        if (rule.decisionBinding() == null) {
            return Truth.UNKNOWN;
        }
        try {
            RuleEvaluationTrace trace = ruleEvaluationService.evaluateDecisionBinding(
                    rule.decisionBinding(),
                    new RuleEvaluationContext(
                            scopes,
                            "EVENT_POLICY",
                            policy.policyCode(),
                            rule.ruleCode(),
                            correlationId,
                            null,
                            null));
            if (StringUtils.hasText(trace.traceId()) && !decisionTraceIds.contains(trace.traceId())) {
                decisionTraceIds.add(trace.traceId());
            }
            return trace.matched() ? Truth.TRUE : Truth.FALSE;
        } catch (RuntimeException e) {
            log.warn("EventPolicy decision binding failed: policy={}, rule={}, decision={}, error={}",
                    policy.policyCode(), rule.ruleCode(), rule.decisionBinding().decisionCode(), e.getMessage());
            return Truth.FALSE;
        }
    }

    // ─── tenant guard ────────────────────────────────────────────────────────

    private Long requireTenant() {
        Long tid = MetaContext.exists() ? MetaContext.getCurrentTenantId() : null;
        if (tid == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Tenant context required for EventPolicy runtime");
        }
        return tid;
    }
}
