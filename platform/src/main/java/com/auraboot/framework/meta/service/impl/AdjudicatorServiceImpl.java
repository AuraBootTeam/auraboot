package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.DecisionOutcomeDTO;
import com.auraboot.framework.meta.dto.EvidenceSubmitRequest;
import com.auraboot.framework.meta.dto.InvariantRuleDTO;
import com.auraboot.framework.meta.dto.RequiredEvidenceDTO;
import com.auraboot.framework.meta.entity.DecisionDefinition;
import com.auraboot.framework.meta.entity.DecisionRecord;
import com.auraboot.framework.meta.entity.EvidenceRecord;
import com.auraboot.framework.meta.event.DecisionEvent;
import com.auraboot.framework.meta.mapper.DecisionDefinitionMapper;
import com.auraboot.framework.meta.mapper.DecisionRecordMapper;
import com.auraboot.framework.meta.mapper.EvidenceRecordMapper;
import com.auraboot.framework.meta.service.AdjudicatorService;
import com.auraboot.framework.meta.service.EventStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Adjudicator Service implementation.
 * Collects evidence, evaluates invariants, and produces formal decisions.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdjudicatorServiceImpl implements AdjudicatorService {

    private final DecisionDefinitionMapper definitionMapper;
    private final EvidenceRecordMapper evidenceRecordMapper;
    private final DecisionRecordMapper decisionRecordMapper;
    private final EventStore eventStore;
    private final ObjectMapper objectMapper;

    private final ExpressionParser spelParser = new SpelExpressionParser();

    @Override
    @Transactional
    public EvidenceRecord submitEvidence(EvidenceSubmitRequest request) {
        Long tenantId = MetaContext.getCurrentTenantId();

        EvidenceRecord record = new EvidenceRecord();
        record.setTenantId(tenantId);
        record.setSubjectType(request.getSubjectType());
        record.setSubjectId(request.getSubjectId());
        record.setStage(request.getStage());
        record.setEvidenceCode(request.getEvidenceCode());
        record.setEvidenceData(serializeJson(request.getEvidenceData()));
        record.setSource(request.getSource());
        record.setCollectedAt(Instant.now());
        record.setCreatedAt(Instant.now());

        evidenceRecordMapper.insertIdempotent(record);
        log.info("Evidence submitted: subjectType={}, subjectId={}, stage={}, code={}",
                request.getSubjectType(), request.getSubjectId(), request.getStage(), request.getEvidenceCode());

        // Check for auto-adjudication
        DecisionDefinition definition = definitionMapper.findBySubjectAndStage(
                tenantId, request.getSubjectType(), request.getStage());

        if (definition != null && Boolean.TRUE.equals(definition.getAutoAdjudicate())) {
            if (isEvidenceComplete(tenantId, request.getSubjectType(), request.getSubjectId(), request.getStage())) {
                log.info("All evidence collected, auto-adjudicating: subjectType={}, subjectId={}, stage={}",
                        request.getSubjectType(), request.getSubjectId(), request.getStage());

                String autoOutcome = autoResolveOutcome(tenantId, request.getSubjectType(),
                        request.getSubjectId(), request.getStage(), definition);
                if (autoOutcome != null) {
                    Long userId = MetaContext.getCurrentUserId();
                    adjudicate(tenantId, request.getSubjectType(), request.getSubjectId(),
                            request.getStage(), autoOutcome, userId);
                }
            }
        }

        return record;
    }

    @Override
    @Transactional
    public DecisionRecord adjudicate(Long tenantId, String subjectType, String subjectId,
                                      String stage, String outcome, Long userId) {
        // Check if decision already exists (idempotent)
        DecisionRecord existing = decisionRecordMapper.findBySubject(tenantId, subjectType, subjectId, stage);
        if (existing != null) {
            log.info("Decision already exists: subjectType={}, subjectId={}, stage={}, outcome={}",
                    subjectType, subjectId, stage, existing.getOutcome());
            return existing;
        }

        // Load definition
        DecisionDefinition definition = definitionMapper.findBySubjectAndStage(tenantId, subjectType, stage);
        if (definition == null) {
            throw new BusinessException(ResponseCode.BadParam,
                    "No decision definition found for subjectType=" + subjectType + ", stage=" + stage);
        }

        // Validate outcome
        List<DecisionOutcomeDTO> outcomeOptions = parseOutcomeOptions(definition.getOutcomeOptions());
        boolean validOutcome = outcomeOptions.stream().anyMatch(o -> o.getCode().equals(outcome));
        if (!validOutcome) {
            throw new BusinessException(ResponseCode.BadParam, "Invalid outcome: " + outcome);
        }

        // Load evidence
        List<EvidenceRecord> evidenceList = evidenceRecordMapper.findBySubject(tenantId, subjectType, subjectId, stage);
        Map<String, Object> evidenceMap = buildEvidenceMap(evidenceList);

        // Evaluate invariants
        List<InvariantRuleDTO> invariants = parseInvariants(definition.getInvariants());
        List<Map<String, Object>> invariantResults = evaluateInvariants(invariants, evidenceMap, subjectId, stage);

        // Check for blocking invariant violations
        boolean hasBlockingViolation = invariantResults.stream()
                .anyMatch(r -> "error".equals(r.get("severity")) && Boolean.FALSE.equals(r.get("passed")));
        if (hasBlockingViolation) {
            log.warn("Decision blocked by invariant violation: subjectType={}, subjectId={}, stage={}",
                    subjectType, subjectId, stage);
            throw new BusinessException(ResponseCode.BadParam,
                    "Decision blocked: invariant violations detected");
        }

        // Build evidence summary
        Map<String, Object> evidenceSummary = new HashMap<>();
        for (EvidenceRecord e : evidenceList) {
            evidenceSummary.put(e.getEvidenceCode(), parseJsonSafe(e.getEvidenceData()));
        }

        // Build trace
        long invariantsPassed = invariantResults.stream().filter(r -> Boolean.TRUE.equals(r.get("passed"))).count();
        long invariantsFailed = invariantResults.stream().filter(r -> Boolean.FALSE.equals(r.get("passed"))).count();
        Map<String, Object> trace = new HashMap<>();
        trace.put("decidedBy", userId != null ? userId : 0L);
        trace.put("decidedAt", Instant.now().toString());
        trace.put("method", userId != null ? "manual" : "auto");
        trace.put("invariantsPassed", invariantsPassed);
        trace.put("invariantsFailed", invariantsFailed);

        // Create decision record
        DecisionRecord decision = new DecisionRecord();
        decision.setTenantId(tenantId);
        decision.setSubjectType(subjectType);
        decision.setSubjectId(subjectId);
        decision.setStage(stage);
        decision.setOutcome(outcome);
        decision.setEvidenceSummary(serializeJson(evidenceSummary));
        decision.setInvariantResults(serializeJson(invariantResults));
        decision.setTrace(serializeJson(trace));
        decision.setDecidedBy(userId);
        decision.setDecidedAt(Instant.now());
        decision.setCreatedAt(Instant.now());

        int inserted = decisionRecordMapper.insertIdempotent(decision);
        if (inserted == 0) {
            // Already decided (concurrent)
            return decisionRecordMapper.findBySubject(tenantId, subjectType, subjectId, stage);
        }

        log.info("Decision produced: subjectType={}, subjectId={}, stage={}, outcome={}",
                subjectType, subjectId, stage, outcome);

        // Write to EventStore
        appendDecisionEvent(decision, evidenceSummary, invariantResults, trace, tenantId, userId);

        return decision;
    }

    @Override
    public DecisionRecord getDecision(Long tenantId, String subjectType, String subjectId, String stage) {
        return decisionRecordMapper.findBySubject(tenantId, subjectType, subjectId, stage);
    }

    @Override
    public List<EvidenceRecord> getEvidence(Long tenantId, String subjectType, String subjectId, String stage) {
        return evidenceRecordMapper.findBySubject(tenantId, subjectType, subjectId, stage);
    }

    @Override
    public boolean isEvidenceComplete(Long tenantId, String subjectType, String subjectId, String stage) {
        DecisionDefinition definition = definitionMapper.findBySubjectAndStage(tenantId, subjectType, stage);
        if (definition == null) {
            return false;
        }

        List<RequiredEvidenceDTO> required = parseRequiredEvidence(definition.getRequiredEvidence());
        if (required.isEmpty()) {
            return true;
        }

        List<EvidenceRecord> collected = evidenceRecordMapper.findBySubject(tenantId, subjectType, subjectId, stage);
        Set<String> collectedCodes = collected.stream()
                .map(EvidenceRecord::getEvidenceCode)
                .collect(Collectors.toSet());

        return required.stream().allMatch(r -> collectedCodes.contains(r.getCode()));
    }

    // ==================== Private Helpers ====================

    private String autoResolveOutcome(Long tenantId, String subjectType, String subjectId,
                                       String stage, DecisionDefinition definition) {
        List<EvidenceRecord> evidenceList = evidenceRecordMapper.findBySubject(tenantId, subjectType, subjectId, stage);
        Map<String, Object> evidenceMap = buildEvidenceMap(evidenceList);

        List<InvariantRuleDTO> invariants = parseInvariants(definition.getInvariants());
        List<Map<String, Object>> results = evaluateInvariants(invariants, evidenceMap, subjectId, stage);

        // Check for blocking violations
        boolean hasBlockingViolation = results.stream()
                .anyMatch(r -> "error".equals(r.get("severity")) && Boolean.FALSE.equals(r.get("passed")));
        if (hasBlockingViolation) {
            log.info("Auto-adjudication blocked by ERROR invariant: subjectId={}, stage={}", subjectId, stage);
            return null;
        }

        // All invariants pass → first outcome option
        List<DecisionOutcomeDTO> outcomes = parseOutcomeOptions(definition.getOutcomeOptions());
        return outcomes.isEmpty() ? null : outcomes.get(0).getCode();
    }

    private List<Map<String, Object>> evaluateInvariants(List<InvariantRuleDTO> invariants,
                                                          Map<String, Object> evidenceMap,
                                                          String subjectId, String stage) {
        List<Map<String, Object>> results = new ArrayList<>();

        for (InvariantRuleDTO invariant : invariants) {
            Map<String, Object> result = new HashMap<>();
            result.put("name", invariant.getName());
            result.put("expression", invariant.getExpression());
            result.put("severity", invariant.getSeverity());

            try {
                SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
                context.setVariable("evidence", evidenceMap);
                context.setVariable("subject", subjectId);
                context.setVariable("stage", stage);

                Boolean passed = spelParser.parseExpression(invariant.getExpression()).getValue(context, Boolean.class);
                result.put("passed", passed != null && passed);
            } catch (Exception e) {
                log.warn("Invariant evaluation failed '{}': {}", invariant.getName(), e.getMessage());
                result.put("passed", false);
                result.put("error", e.getMessage());
            }

            results.add(result);
        }

        return results;
    }

    private Map<String, Object> buildEvidenceMap(List<EvidenceRecord> evidenceList) {
        Map<String, Object> map = new HashMap<>();
        for (EvidenceRecord record : evidenceList) {
            map.put(record.getEvidenceCode(), parseJsonSafe(record.getEvidenceData()));
        }
        return map;
    }

    private void appendDecisionEvent(DecisionRecord decision, Map<String, Object> evidenceSummary,
                                      List<Map<String, Object>> invariantResults,
                                      Map<String, Object> trace, Long tenantId, Long userId) {
        try {
            DecisionEvent event = new DecisionEvent(
                    decision.getSubjectType(), decision.getSubjectId(), decision.getStage(),
                    decision.getOutcome(), evidenceSummary, invariantResults, trace, tenantId, userId);

            String payload = objectMapper.writeValueAsString(event);
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("userId", userId != null ? userId : 0L);
            metadata.put("outcome", decision.getOutcome());
            metadata.put("stage", decision.getStage());
            metadata.put("eventId", event.getEventId());

            eventStore.append(tenantId, "DecisionEvent", decision.getSubjectType(),
                    decision.getSubjectId(), payload, metadata);
        } catch (Exception e) {
            log.warn("Failed to write decision event to event store: {}", e.getMessage());
        }
    }

    private String serializeJson(Object value) {
        if (value == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("JSON serialization failed: {}", e.getMessage());
            return "{}";
        }
    }

    private Object parseJsonSafe(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private List<RequiredEvidenceDTO> parseRequiredEvidence(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<RequiredEvidenceDTO>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<InvariantRuleDTO> parseInvariants(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<InvariantRuleDTO>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<DecisionOutcomeDTO> parseOutcomeOptions(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<DecisionOutcomeDTO>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
