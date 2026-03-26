package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.security.SqlSafetyUtils;
import com.auraboot.framework.meta.dto.InvariantEvaluationResultDTO;
import com.auraboot.framework.meta.entity.DecisionAlarm;
import com.auraboot.framework.meta.entity.InvariantDefinition;
import com.auraboot.framework.meta.entity.InvariantEvaluationLog;
import com.auraboot.framework.meta.mapper.DecisionAlarmMapper;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.meta.mapper.InvariantDefinitionMapper;
import com.auraboot.framework.meta.mapper.InvariantEvaluationLogMapper;
import com.auraboot.framework.meta.service.InvariantEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Invariant Engine implementation.
 * Evaluates SpEL-based invariant expressions and records results.
 *
 * @author AuraBoot Team
 * @since 2.5.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvariantEngineImpl implements InvariantEngine {

    private static final int ALWAYS_BATCH_SIZE = 100;

    private final InvariantDefinitionMapper invariantMapper;
    private final InvariantEvaluationLogMapper evaluationLogMapper;
    private final DecisionAlarmMapper alarmMapper;
    private final DynamicDataMapper dynamicDataMapper;
    private final ObjectMapper objectMapper;

    private final ExpressionParser spelParser = new SpelExpressionParser();

    @Override
    public List<InvariantEvaluationResultDTO> evaluatePreInvariants(
            Long tenantId, String commandCode, String modelCode,
            Map<String, Object> payload, String recordId, String currentState) {

        List<InvariantDefinition> invariants = loadInvariants(tenantId, "pre", commandCode, modelCode, currentState);
        if (invariants.isEmpty()) {
            return List.of();
        }

        EvaluationContext context = buildSpelContext(payload, recordId, currentState, null);
        List<InvariantEvaluationResultDTO> results = new ArrayList<>();

        for (InvariantDefinition inv : invariants) {
            InvariantEvaluationResultDTO result = evaluateSingle(inv, context, tenantId, commandCode, recordId);
            results.add(result);

            if (!result.isPassed() && "error".equals(inv.getSeverity())) {
                log.warn("PRE invariant violated (ERROR): code={}, expression={}",
                        inv.getCode(), inv.getExpression());
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "Invariant violated: " + inv.getCode() + " - " + result.getErrorMessage());
            }

            if (!result.isPassed() && "warn".equals(inv.getSeverity())) {
                log.info("PRE invariant violated (WARN): code={}", inv.getCode());
            }
        }

        return results;
    }

    @Override
    public List<InvariantEvaluationResultDTO> evaluatePostInvariants(
            Long tenantId, String commandCode, String modelCode,
            Map<String, Object> payload, String recordId, String currentState) {

        List<InvariantDefinition> invariants = loadInvariants(tenantId, "post", commandCode, modelCode, currentState);
        if (invariants.isEmpty()) {
            return List.of();
        }

        EvaluationContext context = buildSpelContext(payload, recordId, currentState, null);
        List<InvariantEvaluationResultDTO> results = new ArrayList<>();

        for (InvariantDefinition inv : invariants) {
            InvariantEvaluationResultDTO result = evaluateSingle(inv, context, tenantId, commandCode, recordId);
            results.add(result);

            if (!result.isPassed()) {
                log.warn("POST invariant violated: code={}, severity={}", inv.getCode(), inv.getSeverity());
                createAlarmForViolation(tenantId, inv, recordId);
            }
        }

        return results;
    }

    @Override
    public void evaluateAlwaysInvariants(Long tenantId, String modelCode) {
        List<InvariantDefinition> invariants = invariantMapper.findAlwaysByModelCode(tenantId, modelCode);
        if (invariants.isEmpty()) {
            return;
        }

        // Scan recent records from the target model
        List<Map<String, Object>> records = loadRecentRecords(tenantId, modelCode);
        if (records.isEmpty()) {
            return;
        }

        log.debug("Evaluating {} ALWAYS invariants against {} records for model {}",
                invariants.size(), records.size(), modelCode);

        for (Map<String, Object> record : records) {
            String recordId = record.get("id") != null ? record.get("id").toString() : null;
            String state = record.get("status") != null ? record.get("status").toString() : null;

            EvaluationContext context = buildSpelContext(record, recordId, state, null);

            for (InvariantDefinition inv : invariants) {
                InvariantEvaluationResultDTO result = evaluateSingle(inv, context, tenantId, null, recordId);

                if (!result.isPassed()) {
                    log.info("ALWAYS invariant violated: code={}, model={}, recordId={}",
                            inv.getCode(), modelCode, recordId);
                    createAlarmForViolation(tenantId, inv, recordId != null ? recordId : "batch");
                }
            }
        }
    }

    // ==================== Private Helpers ====================

    private List<InvariantDefinition> loadInvariants(Long tenantId, String type,
                                                      String commandCode, String modelCode,
                                                      String currentState) {
        List<InvariantDefinition> all = new ArrayList<>();

        // Load MODEL-scoped invariants
        if (StringUtils.hasText(modelCode)) {
            all.addAll(invariantMapper.findPublishedByScope(tenantId, type, "model", modelCode));
        }

        // Load COMMAND-scoped invariants
        if (StringUtils.hasText(commandCode)) {
            all.addAll(invariantMapper.findPublishedByScope(tenantId, type, "command", commandCode));
        }

        // Load STATE-scoped invariants
        if (StringUtils.hasText(currentState)) {
            all.addAll(invariantMapper.findPublishedByScope(tenantId, type, "state", currentState));
        }

        return all;
    }

    private InvariantEvaluationResultDTO evaluateSingle(InvariantDefinition inv,
                                                         EvaluationContext context,
                                                         Long tenantId,
                                                         String commandCode,
                                                         String recordId) {
        long start = System.currentTimeMillis();
        boolean passed = false;
        String errorMessage = null;

        try {
            Boolean result = spelParser.parseExpression(inv.getExpression()).getValue(context, Boolean.class);
            passed = result != null && result;
            if (!passed) {
                errorMessage = "Expression evaluated to false: " + inv.getExpression();
            }
        } catch (Exception e) {
            errorMessage = "Expression evaluation error: " + e.getMessage();
            log.debug("SpEL evaluation failed for invariant {}: {}", inv.getCode(), e.getMessage());
        }

        long executionTimeMs = System.currentTimeMillis() - start;

        // Record evaluation log
        recordEvaluationLog(tenantId, inv, commandCode, recordId, passed, errorMessage, executionTimeMs, context);

        return InvariantEvaluationResultDTO.builder()
                .invariantCode(inv.getCode())
                .passed(passed)
                .errorMessage(errorMessage)
                .executionTimeMs(executionTimeMs)
                .build();
    }

    private EvaluationContext buildSpelContext(Map<String, Object> payload,
                                                String recordId,
                                                String currentState,
                                                Map<String, Object> result) {
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        context.setVariable("payload", payload);

        // Make payload fields directly accessible
        if (payload != null) {
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                context.setVariable(entry.getKey(), entry.getValue());
            }
        }

        context.setVariable("recordId", recordId);
        context.setVariable("state", currentState);

        if (result != null) {
            context.setVariable("result", result);
        }

        return context;
    }

    private void recordEvaluationLog(Long tenantId, InvariantDefinition inv,
                                      String commandCode, String recordId,
                                      boolean passed, String errorMessage,
                                      long executionTimeMs, EvaluationContext context) {
        try {
            InvariantEvaluationLog logEntry = new InvariantEvaluationLog();
            logEntry.setTenantId(tenantId);
            logEntry.setInvariantCode(inv.getCode());
            logEntry.setInvariantType(inv.getInvariantType());
            logEntry.setScopeType(inv.getScopeType());
            logEntry.setScopeRef(inv.getScopeRef());
            logEntry.setModelCode(inv.getModelCode());
            logEntry.setRecordId(recordId);
            logEntry.setCommandCode(commandCode);
            logEntry.setEvaluationResult(passed);
            logEntry.setSeverity(inv.getSeverity());
            logEntry.setExpression(inv.getExpression());
            logEntry.setErrorMessage(errorMessage);
            logEntry.setExecutionTimeMs(executionTimeMs);
            logEntry.setCreatedAt(Instant.now());

            evaluationLogMapper.insertLog(logEntry);
        } catch (Exception e) {
            log.warn("Failed to record invariant evaluation log: {}", e.getMessage());
        }
    }

    private void createAlarmForViolation(Long tenantId, InvariantDefinition inv, String recordId) {
        try {
            int existing = alarmMapper.countOpenAlarm(tenantId, "invariant_violation",
                    inv.getModelCode(), recordId, inv.getCode());
            if (existing > 0) {
                return;
            }

            DecisionAlarm alarm = new DecisionAlarm();
            alarm.setTenantId(tenantId);
            alarm.setAlarmType("invariant_violation");
            alarm.setSubjectType(inv.getModelCode());
            alarm.setSubjectId(recordId);
            alarm.setStage(inv.getCode());
            alarm.setSeverity(inv.getSeverity());
            alarm.setMessage("Invariant violated: " + inv.getCode() + " [" + inv.getExpression() + "]");
            alarm.setStatus(StatusConstants.OPEN);
            alarm.setCreatedAt(Instant.now());

            alarmMapper.insertAlarm(alarm);
            log.info("Invariant alarm created: code={}, model={}, recordId={}",
                    inv.getCode(), inv.getModelCode(), recordId);
        } catch (Exception e) {
            log.warn("Failed to create invariant alarm: {}", e.getMessage());
        }
    }

    private List<Map<String, Object>> loadRecentRecords(Long tenantId, String modelCode) {
        try {
            SqlSafetyUtils.validateIdentifier(modelCode, "invariant modelCode as table");
            String sql = "SELECT * FROM " + modelCode
                    + " WHERE tenant_id = #{params.tenantId}"
                    + " ORDER BY updated_at DESC NULLS LAST"
                    + " LIMIT " + ALWAYS_BATCH_SIZE;
            Map<String, Object> params = Map.of("tenantId", tenantId);
            List<Map<String, Object>> result = dynamicDataMapper.selectByQuery(sql, params);
            return result != null ? result : List.of();
        } catch (Exception e) {
            log.debug("Failed to load records for ALWAYS invariant check, model={}: {}",
                    modelCode, e.getMessage());
            return List.of();
        }
    }
}
