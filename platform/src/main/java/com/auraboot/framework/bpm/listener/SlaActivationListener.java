package com.auraboot.framework.bpm.listener;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.SlaConfigEntity;
import com.auraboot.framework.bpm.event.BpmEvent;
import com.auraboot.framework.bpm.service.SlaConfigService;
import com.auraboot.framework.bpm.service.SlaRecordService;
import com.auraboot.framework.decision.ast.Scope;
import com.auraboot.framework.decision.rule.DecisionBinding;
import com.auraboot.framework.decision.rule.DecisionVersionPolicy;
import com.auraboot.framework.decision.rule.RuleEvaluationContext;
import com.auraboot.framework.decision.rule.RuleEvaluationService;
import com.auraboot.framework.decision.rule.RuleEvaluationTrace;
import com.auraboot.framework.decision.rule.RuleValueSource;
import com.auraboot.framework.decision.rule.RuleConsumerBinding;
import com.auraboot.framework.decision.rule.RuleBindingKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Listens to {@code task_assigned} BPM events and creates an SLA record for each
 * matching {@link SlaConfigEntity} targeting the activated node.
 *
 * <p>Lookup strategy: {@code targetType="NODE"} + {@code targetKey=activityId}.
 * If no matching SLA config exists the event is silently ignored (SLA is optional).
 * Deadline is computed as {@code now + deadlineValue} (ISO-8601 duration, e.g. {@code PT30S}).
 *
 * <p>Transaction boundary: called within the same transaction as the task-creation event.
 * The record insert joins the outer transaction (REQUIRED propagation).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlaActivationListener {

    private final SlaConfigService slaConfigService;
    private final SlaRecordService slaRecordService;

    /**
     * Optional DecisionRuntime integration (M5): when an SLA config uses {@code deadlineMode=RULE},
     * the deadline minutes are computed by evaluating the decision named in {@code deadlineValue}.
     * Field injection (not constructor) keeps the @RequiredArgsConstructor + existing tests unchanged;
     * null when the decision bean is absent.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.auraboot.framework.decision.service.DecisionEvaluationService decisionEvaluationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RuleEvaluationService ruleEvaluationService;

    @EventListener
    public void onBpmEvent(BpmEvent event) {
        if (!"task_assigned".equals(event.getBpmEventType())) {
            return;
        }

        String activityId = event.getNodeId();
        String processInstanceId = event.getInstanceId();
        String taskId = extractTaskId(event);

        if (activityId == null || processInstanceId == null) {
            log.debug("SlaActivationListener: missing activityId or processInstanceId in task_assigned event, skipping");
            return;
        }

        Long tenantId = event.getTenantId();
        if (tenantId == null || tenantId == 0L) {
            log.debug("SlaActivationListener: no tenantId in task_assigned event for activityId={}", activityId);
            return;
        }

        // Only set MetaContext if not already initialised by the outer request thread.
        // If it IS already set (normal HTTP request path), we must NOT clear it on exit
        // — doing so would corrupt the context for subsequent filters/handlers.
        boolean contextOwner = false;
        try {
            MetaContext.getCurrentTenantId(); // throws if not initialised
        } catch (Exception e) {
            // MetaContext not set — we own it for this invocation
            MetaContext.setContext(tenantId, 0L, null, "system");
            contextOwner = true;
        }

        try {
            // Look up SLA configs targeting this node. Target type may be stored
            // as "NODE" or "node", so resolve all variants in one mapper call.
            List<SlaConfigEntity> configs = slaConfigService.findByTargetAnyCase("NODE", activityId);

            if (configs.isEmpty()) {
                log.debug("No SLA config found for NODE/{} — skipping SLA record creation", activityId);
                return;
            }

            for (SlaConfigEntity config : configs) {
                if (!Boolean.TRUE.equals(config.getEnabled())) {
                    continue;
                }
                try {
                    Instant deadline = computeDeadline(config);
                    slaRecordService.createRecord(config, processInstanceId, taskId, activityId, deadline);
                    log.info("SLA record created at task activation: activityId={}, configPid={}, deadline={}",
                            activityId, config.getPid(), deadline);
                } catch (Exception e) {
                    // CATCH: non-critical — SLA record creation must not block task activation
                    log.error("Failed to create SLA record for config={}, activityId={}: {}",
                            config.getPid(), activityId, e.getMessage(), e);
                }
            }
        } finally {
            // Only clear if we were the ones who set it — never clear the caller's context
            if (contextOwner) {
                MetaContext.clear();
            }
        }
    }

    /**
     * F3 — record-level SLA activation. When a dynamic record is created, activate any SLA config
     * targeting that model via {@code targetType="RECORD"} + {@code targetKey=<modelCode>}. Mirrors the
     * BPM-node path but keyed on the record instead of a process node, reusing the same
     * FIXED/RULE/decision deadline engine ({@link #computeDeadline}). The created SLA record carries
     * the record pid in {@code processInstanceId} and the modelCode in {@code nodeId}, so the existing
     * scheduler / overdue / escalation logic works unchanged.
     *
     * <p>Called directly from {@code DynamicDataServiceImpl} (the record-create hook), so it runs in the
     * caller's MetaContext + transaction. Non-blocking: an SLA failure must never fail the record create.
     */
    public void onRecordCreate(String modelCode, String recordPid, Map<String, Object> recordData) {
        if (modelCode == null || recordPid == null) {
            return;
        }
        List<SlaConfigEntity> configs = slaConfigService.findByTargetAnyCase("RECORD", modelCode);
        if (configs.isEmpty()) {
            log.debug("No RECORD-level SLA config found for model {} — skipping SLA record creation", modelCode);
            return;
        }
        for (SlaConfigEntity config : configs) {
            if (!Boolean.TRUE.equals(config.getEnabled())) {
                continue;
            }
            try {
                Instant deadline = computeDeadline(config, recordData);
                slaRecordService.createRecord(config, recordPid, null, modelCode, deadline);
                log.info("SLA record created at record creation: modelCode={}, recordPid={}, configPid={}, deadline={}",
                        modelCode, recordPid, config.getPid(), deadline);
            } catch (Exception e) {
                // CATCH: non-critical — record-level SLA activation must not block the record create
                log.error("Failed to create record-level SLA record for config={}, model={}: {}",
                        config.getPid(), modelCode, e.getMessage(), e);
            }
        }
    }

    private String extractTaskId(BpmEvent event) {
        if (event.getPayload() == null) return null;
        Object v = event.getPayload().get("taskInstanceId");
        return v != null ? v.toString() : null;
    }

    /**
     * Compute the SLA deadline from the config.
     * Currently supports {@code deadlineMode=FIXED} with an ISO-8601 duration string
     * (e.g. {@code PT30S}, {@code PT8H}).
     * Falls back to 24 hours for unknown modes.
     */
    private Instant computeDeadline(SlaConfigEntity config) {
        return computeDeadline(config, null);
    }

    private Instant computeDeadline(SlaConfigEntity config, Map<String, Object> recordData) {
        Instant now = Instant.now();
        String mode = config.getDeadlineMode();
        String value = config.getDeadlineValue();

        RuleConsumerBinding ruleBinding = config.getRuleBinding();
        if (ruleEvaluationService != null
                && ruleBinding != null
                && ruleBinding.active()
                && ruleBinding.bindingKind() == RuleBindingKind.DECISION_REF
                && ruleBinding.decisionBinding() != null) {
            Long minutes = resolveRuleDeadlineMinutesWithBinding(config, ruleBinding.decisionBinding(), recordData);
            if (minutes != null && minutes > 0) {
                return now.plus(Duration.ofMinutes(minutes));
            }
            log.warn("SLA rule binding did not yield deadlineMinutes for config={} — falling back to legacy/default",
                    config.getPid());
        }

        if ("FIXED".equalsIgnoreCase(mode) && value != null && !value.isBlank()) {
            try {
                Duration duration = Duration.parse(value);
                return now.plus(duration);
            } catch (Exception e) {
                log.warn("Cannot parse SLA deadlineValue '{}' for config={}: {}",
                        value, config.getPid(), e.getMessage());
            }
        }

        // M5: deadlineMode=RULE — the deadline minutes come from a DecisionRuntime decision (deadlineValue)
        if ("RULE".equalsIgnoreCase(mode) && value != null && !value.isBlank()) {
            Long minutes = resolveRuleDeadlineMinutes(config, value);
            if (minutes != null && minutes > 0) {
                return now.plus(Duration.ofMinutes(minutes));
            }
            log.warn("SLA decision '{}' did not yield deadlineMinutes for config={} — falling back to PT24H",
                    value, config.getPid());
        }

        // Fallback: 24 hours
        log.debug("SLA deadlineMode='{}' not handled or value missing — defaulting to PT24H for config={}",
                mode, config.getPid());
        return now.plus(Duration.ofHours(24));
    }

    /**
     * M5 — evaluate the decision named {@code decisionCode} to obtain the SLA deadline minutes
     * (decision output {@code deadlineMinutes}). Returns null when the decision module is absent, the
     * decision does not match, or no numeric deadlineMinutes is produced (caller falls back).
     * Package-private for unit testing. Degrades on failure (logged) rather than crashing activation.
     */
    Long resolveRuleDeadlineMinutes(SlaConfigEntity config, String decisionCode) {
        if (ruleEvaluationService != null) {
            return resolveRuleDeadlineMinutesWithBinding(config, decisionCode);
        }
        if (decisionEvaluationService == null) {
            return null;
        }
        try {
            com.auraboot.framework.decision.dto.DrtEvaluateRequest req =
                    new com.auraboot.framework.decision.dto.DrtEvaluateRequest();
            req.setDecisionCode(decisionCode);
            java.util.Map<String, Object> sla = new java.util.HashMap<>();
            sla.put("targetType", config.getTargetType());
            sla.put("targetKey", config.getTargetKey());
            req.setContext(java.util.Map.of("record", java.util.Map.of("data", sla)));
            com.auraboot.framework.decision.model.DecisionResult result = decisionEvaluationService.evaluate(req);
            if (!result.matched() || result.outputs() == null) {
                return null;
            }
            Object m = result.outputs().get("deadlineMinutes");
            return parseDeadlineMinutes(m);
        } catch (RuntimeException e) {
            log.warn("SLA deadline decision '{}' evaluation failed for config={}: {}",
                    decisionCode, config.getPid(), e.getMessage());
            return null;
        }
    }

    private Long resolveRuleDeadlineMinutesWithBinding(SlaConfigEntity config, String decisionCode) {
        DecisionBinding binding = new DecisionBinding(
                decisionCode,
                DecisionVersionPolicy.LATEST_PUBLISHED,
                null,
                null,
                null,
                List.of(
                        new DecisionBinding.InputMapping(
                                "targetType",
                                RuleValueSource.field(Scope.RECORD, "data.targetType")),
                        new DecisionBinding.InputMapping(
                                "targetKey",
                                RuleValueSource.field(Scope.RECORD, "data.targetKey"))),
                List.of(),
                DecisionBinding.FallbackPolicy.failClosed(),
                200,
                DecisionBinding.TraceMode.SAMPLED,
                true,
                null,
                null);
        return resolveRuleDeadlineMinutesWithBinding(config, binding);
    }

    private Long resolveRuleDeadlineMinutesWithBinding(SlaConfigEntity config, DecisionBinding binding) {
        return resolveRuleDeadlineMinutesWithBinding(config, binding, null);
    }

    private Long resolveRuleDeadlineMinutesWithBinding(
            SlaConfigEntity config,
            DecisionBinding binding,
            Map<String, Object> runtimeRecordData) {
        try {
            Map<String, Object> recordData = buildRuleRecordData(config, runtimeRecordData);
            Map<Scope, Map<String, Object>> scopes = new EnumMap<>(Scope.class);
            scopes.put(Scope.RECORD, Map.of("data", recordData));
            Map<String, Object> meta = extractMeta(runtimeRecordData);
            if (!meta.isEmpty()) {
                scopes.put(Scope.META, meta);
            }
            RuleEvaluationTrace trace = ruleEvaluationService.evaluateDecisionBinding(
                    binding,
                    new RuleEvaluationContext(
                            scopes,
                            "SLA",
                            config.getPid(),
                            config.getTargetKey(),
                            null,
                            null,
                            null));
            if (!trace.matched()) {
                return null;
            }
            return parseDeadlineMinutes(trace.outputSnapshot().get("deadlineMinutes"));
        } catch (RuntimeException e) {
            log.warn("SLA rule binding decision '{}' evaluation failed for config={}: {}",
                    binding == null ? null : binding.decisionCode(), config.getPid(), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildRuleRecordData(
            SlaConfigEntity config,
            Map<String, Object> runtimeRecordData) {
        Map<String, Object> recordData = new LinkedHashMap<>();
        if (runtimeRecordData != null) {
            runtimeRecordData.forEach((field, value) -> {
                if (field != null && !isMetaField(field)) {
                    recordData.put(field, value);
                }
            });
        }
        recordData.put("targetType", config.getTargetType());
        recordData.put("targetKey", config.getTargetKey());
        recordData.put("deadlineMode", config.getDeadlineMode());
        recordData.put("deadlineValue", config.getDeadlineValue());
        recordData.put("domainCode", config.getDomainCode());
        recordData.put("modelCode", config.getModelCode());
        return recordData;
    }

    private Map<String, Object> extractMeta(Map<String, Object> runtimeRecordData) {
        if (runtimeRecordData == null || runtimeRecordData.isEmpty()) {
            return Map.of();
        }
        for (String field : List.of("meta", "_meta", "ruleMeta")) {
            Object value = runtimeRecordData.get(field);
            if (value instanceof Map<?, ?> raw && !raw.isEmpty()) {
                Map<String, Object> copy = new LinkedHashMap<>();
                raw.forEach((key, item) -> {
                    if (key != null) {
                        copy.put(String.valueOf(key), item);
                    }
                });
                return copy;
            }
        }
        return Map.of();
    }

    private boolean isMetaField(String field) {
        return "meta".equals(field) || "_meta".equals(field) || "ruleMeta".equals(field);
    }

    private Long parseDeadlineMinutes(Object minutes) {
        if (minutes instanceof Number n) {
            return n.longValue();
        }
        if (minutes != null) {
            try {
                return Long.parseLong(String.valueOf(minutes).trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }
}
