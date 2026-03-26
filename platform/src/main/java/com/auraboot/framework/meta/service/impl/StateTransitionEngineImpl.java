package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.meta.dto.StateTransitionDTO;
import com.auraboot.framework.meta.entity.StateGraphDefinition;
import com.auraboot.framework.meta.mapper.StateGraphDefinitionMapper;
import com.auraboot.framework.meta.service.StateTransitionEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * State Transition Engine implementation.
 * Validates transitions against published state graph definitions,
 * evaluates SpEL guard expressions.
 *
 * @author AuraBoot Team
 * @since 2.4.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StateTransitionEngineImpl implements StateTransitionEngine {

    private final StateGraphDefinitionMapper stateGraphMapper;
    private final ObjectMapper objectMapper;

    private final ExpressionParser spelParser = new SpelExpressionParser();

    @Override
    public void validateTransition(Long tenantId, String modelCode, String stateField,
                                   String currentState, String commandCode, Map<String, Object> payload) {
        // tenant_id is automatically added by TenantLineInnerInterceptor
        StateGraphDefinition graph = stateGraphMapper.findPublishedByModelCode(modelCode);
        if (graph == null) {
            // No state graph bound → silently pass
            return;
        }

        List<StateTransitionDTO> transitions = parseTransitions(graph.getTransitions());
        if (transitions.isEmpty()) {
            return;
        }

        // Find matching transition
        StateTransitionDTO matched = transitions.stream()
                .filter(t -> currentState.equals(t.getFrom()) && commandCode.equals(t.getTriggerCommand()))
                .findFirst()
                .orElse(null);

        if (matched == null) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "State transition denied: no transition from '" + currentState
                            + "' triggered by command '" + commandCode + "'");
        }

        // Evaluate guard expression
        if (StringUtils.hasText(matched.getGuard())) {
            boolean guardResult = evaluateGuard(matched.getGuard(), currentState, payload);
            if (!guardResult) {
                throw new ValidationException(ResponseCode.CommonValidationFailed,
                        "State guard failed: " + matched.getGuard());
            }
        }
    }

    @Override
    public List<StateTransitionDTO> getAllowedTransitions(Long tenantId, String modelCode, String currentState) {
        // tenant_id is automatically added by TenantLineInnerInterceptor
        StateGraphDefinition graph = stateGraphMapper.findPublishedByModelCode(modelCode);
        if (graph == null) {
            return Collections.emptyList();
        }

        List<StateTransitionDTO> transitions = parseTransitions(graph.getTransitions());
        return transitions.stream()
                .filter(t -> currentState.equals(t.getFrom()))
                .collect(Collectors.toList());
    }

    @Override
    public String resolveTargetState(Long tenantId, String modelCode, String currentState, String commandCode) {
        // tenant_id is automatically added by TenantLineInnerInterceptor
        StateGraphDefinition graph = stateGraphMapper.findPublishedByModelCode(modelCode);
        if (graph == null) {
            return null;
        }

        List<StateTransitionDTO> transitions = parseTransitions(graph.getTransitions());
        return transitions.stream()
                .filter(t -> currentState.equals(t.getFrom()) && commandCode.equals(t.getTriggerCommand()))
                .map(StateTransitionDTO::getTo)
                .findFirst()
                .orElse(null);
    }

    // ==================== Private Helpers ====================

    private List<StateTransitionDTO> parseTransitions(String transitionsJson) {
        if (!StringUtils.hasText(transitionsJson)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(transitionsJson, new TypeReference<List<StateTransitionDTO>>() {});
        } catch (Exception e) {
            log.error("Failed to parse state transitions JSON: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private boolean evaluateGuard(String guardExpression, String currentState, Map<String, Object> payload) {
        try {
            SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
            context.setVariable("currentState", currentState);
            context.setVariable("payload", payload);
            // Make payload fields directly accessible
            if (payload != null) {
                for (Map.Entry<String, Object> entry : payload.entrySet()) {
                    context.setVariable(entry.getKey(), entry.getValue());
                }
            }

            Boolean result = spelParser.parseExpression(guardExpression).getValue(context, Boolean.class);
            return result != null && result;
        } catch (Exception e) {
            log.warn("Guard expression evaluation failed '{}': {}", guardExpression, e.getMessage());
            return false;
        }
    }
}
