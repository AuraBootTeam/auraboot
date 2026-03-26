package com.auraboot.framework.automation.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.automation.dto.DebugEventDTO;
import com.auraboot.framework.automation.dto.DebugSessionCreateRequest;
import com.auraboot.framework.automation.dto.DebugSessionDTO;
import com.auraboot.framework.automation.entity.Automation;
import com.auraboot.framework.automation.entity.AutomationAction;
import com.auraboot.framework.automation.entity.AutomationLog.ActionResult;
import com.auraboot.framework.automation.entity.DebugSession;
import com.auraboot.framework.automation.executor.ActionExecutor;
import com.auraboot.framework.automation.mapper.AutomationMapper;
import com.auraboot.framework.automation.mapper.DebugSessionMapper;
import com.auraboot.framework.automation.service.DebugEventPublisher;
import com.auraboot.framework.automation.service.DebugSessionService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.ValidationException;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Debug session service implementation.
 * Uses state-machine approach: each step() call executes one action,
 * records the result, advances the pointer, and pauses.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
@Slf4j
@Service
public class DebugSessionServiceImpl implements DebugSessionService {

    private final DebugSessionMapper debugSessionMapper;
    private final AutomationMapper automationMapper;
    private final ActionExecutor actionExecutor;
    private final DebugEventPublisher eventPublisher;

    public DebugSessionServiceImpl(
            DebugSessionMapper debugSessionMapper,
            AutomationMapper automationMapper,
            @Qualifier("compositeActionExecutor") ActionExecutor actionExecutor,
            DebugEventPublisher eventPublisher) {
        this.debugSessionMapper = debugSessionMapper;
        this.automationMapper = automationMapper;
        this.actionExecutor = actionExecutor;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public DebugSessionDTO createSession(String automationId, DebugSessionCreateRequest request) {
        Automation automation = automationMapper.findByPid(automationId);
        if (automation == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation not found: " + automationId);
        }

        // Stop any existing active session
        DebugSession existing = debugSessionMapper.findActiveByAutomationId(automationId);
        if (existing != null) {
            existing.setStatus("stopped");
            existing.setUpdatedAt(Instant.now());
            debugSessionMapper.updateSession(existing);
            eventPublisher.closeSession(existing.getPid());
        }

        // Build initial context
        Map<String, Object> context = new HashMap<>();
        if (request.getTriggerPayload() != null) {
            context.putAll(request.getTriggerPayload());
        }
        if (request.getRecordId() != null) {
            context.put("recordId", request.getRecordId());
        }
        context.put("automationPid", automationId);
        context.put("debugMode", true);

        DebugSession session = new DebugSession();
        session.setPid(UniqueIdGenerator.generate());
        session.setTenantId(MetaContext.getCurrentTenantId());
        session.setAutomationId(automationId);
        session.setRecordId(request.getRecordId());
        session.setStatus("paused");
        session.setCurrentActionIndex(0);
        session.setBreakpoints(request.getBreakpoints() != null ? request.getBreakpoints() : List.of());
        session.setExecutionContext(context);
        session.setActionResults(new ArrayList<>());
        session.setTriggerPayload(request.getTriggerPayload() != null ? request.getTriggerPayload() : Map.of());
        session.setCreatedAt(Instant.now());
        session.setUpdatedAt(Instant.now());
        session.setCreatedBy(MetaContext.getCurrentUserPid());

        debugSessionMapper.insertSession(session);

        log.info("Debug session created: pid={}, automationId={}", session.getPid(), automationId);

        int totalActions = automation.getActions() != null ? automation.getActions().size() : 0;
        return toDTO(session, totalActions);
    }

    @Override
    public DebugSessionDTO getSession(String sessionId) {
        DebugSession session = findSession(sessionId);
        Automation automation = automationMapper.findByPid(session.getAutomationId());
        int totalActions = automation != null && automation.getActions() != null ? automation.getActions().size() : 0;
        return toDTO(session, totalActions);
    }

    @Override
    public DebugSessionDTO step(String sessionId) {
        DebugSession session = findSession(sessionId);
        if (!session.isActive()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Session is not active. Status: " + session.getStatus());
        }

        Automation automation = automationMapper.findByPid(session.getAutomationId());
        if (automation == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation not found");
        }

        List<AutomationAction> actions = getSortedActions(automation);
        int totalActions = actions.size();
        int currentIndex = session.getCurrentActionIndex();

        if (currentIndex >= totalActions) {
            // All actions completed
            session.setStatus(StatusConstants.COMPLETED);
            session.setUpdatedAt(Instant.now());
            debugSessionMapper.updateSession(session);

            publishEvent(session, "session_completed", null, null);
            return toDTO(session, totalActions);
        }

        // Execute current action
        AutomationAction action = actions.get(currentIndex);
        session.setStatus(StatusConstants.RUNNING);
        debugSessionMapper.updateSession(session);

        publishEvent(session, "action_started", action, null);

        ActionResult result = executeAction(action, session.getExecutionContext());

        // Record result
        List<ActionResult> results = session.getActionResults() != null
                ? new ArrayList<>(session.getActionResults())
                : new ArrayList<>();
        results.add(result);
        session.setActionResults(results);

        // Update context with action result
        Map<String, Object> ctx = new HashMap<>(session.getExecutionContext());
        ctx.put("action_" + action.getSequence() + "_result", result.getResult());
        session.setExecutionContext(ctx);

        // Advance pointer
        int nextIndex = currentIndex + 1;
        session.setCurrentActionIndex(nextIndex);

        if (StatusConstants.FAILED.equals(result.getStatus()) && !Boolean.TRUE.equals(action.getContinueOnError())) {
            session.setStatus(StatusConstants.FAILED);
            session.setErrorMessage(result.getErrorMessage());
            publishEvent(session, "action_failed", action, result);
        } else if (nextIndex >= totalActions) {
            session.setStatus(StatusConstants.COMPLETED);
            publishEvent(session, "action_completed", action, result);
            publishEvent(session, "session_completed", null, null);
        } else {
            session.setStatus("paused");
            publishEvent(session, "action_completed", action, result);
            publishEvent(session, "session_paused", null, null);
        }

        session.setUpdatedAt(Instant.now());
        debugSessionMapper.updateSession(session);

        return toDTO(session, totalActions);
    }

    @Override
    public DebugSessionDTO continueExecution(String sessionId) {
        DebugSession session = findSession(sessionId);
        if (!session.isActive()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Session is not active. Status: " + session.getStatus());
        }

        Automation automation = automationMapper.findByPid(session.getAutomationId());
        if (automation == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Automation not found");
        }

        List<AutomationAction> actions = getSortedActions(automation);
        int totalActions = actions.size();
        Set<Integer> breakpointSet = new HashSet<>(
                session.getBreakpoints() != null ? session.getBreakpoints() : List.of());

        while (session.getCurrentActionIndex() < totalActions) {
            int currentIndex = session.getCurrentActionIndex();

            // Check breakpoint (skip first check to allow continuing from breakpoint)
            if (currentIndex > session.getCurrentActionIndex() - 1 && breakpointSet.contains(currentIndex)
                    && session.getActionResults() != null && !session.getActionResults().isEmpty()) {
                // Hit a breakpoint, pause
                session.setStatus("paused");
                session.setUpdatedAt(Instant.now());
                debugSessionMapper.updateSession(session);
                publishEvent(session, "session_paused", null, null);
                return toDTO(session, totalActions);
            }

            AutomationAction action = actions.get(currentIndex);
            session.setStatus(StatusConstants.RUNNING);

            publishEvent(session, "action_started", action, null);

            ActionResult result = executeAction(action, session.getExecutionContext());

            // Record result
            List<ActionResult> results = session.getActionResults() != null
                    ? new ArrayList<>(session.getActionResults())
                    : new ArrayList<>();
            results.add(result);
            session.setActionResults(results);

            // Update context
            Map<String, Object> ctx = new HashMap<>(session.getExecutionContext());
            ctx.put("action_" + action.getSequence() + "_result", result.getResult());
            session.setExecutionContext(ctx);

            session.setCurrentActionIndex(currentIndex + 1);

            if (StatusConstants.FAILED.equals(result.getStatus()) && !Boolean.TRUE.equals(action.getContinueOnError())) {
                session.setStatus(StatusConstants.FAILED);
                session.setErrorMessage(result.getErrorMessage());
                session.setUpdatedAt(Instant.now());
                debugSessionMapper.updateSession(session);
                publishEvent(session, "action_failed", action, result);
                return toDTO(session, totalActions);
            }

            publishEvent(session, "action_completed", action, result);

            // Check if next action is a breakpoint
            int nextIndex = currentIndex + 1;
            if (nextIndex < totalActions && breakpointSet.contains(nextIndex)) {
                session.setStatus("paused");
                session.setUpdatedAt(Instant.now());
                debugSessionMapper.updateSession(session);
                publishEvent(session, "session_paused", null, null);
                return toDTO(session, totalActions);
            }
        }

        // All actions done
        session.setStatus(StatusConstants.COMPLETED);
        session.setUpdatedAt(Instant.now());
        debugSessionMapper.updateSession(session);
        publishEvent(session, "session_completed", null, null);

        return toDTO(session, totalActions);
    }

    @Override
    public DebugSessionDTO stop(String sessionId) {
        DebugSession session = findSession(sessionId);
        if (!session.isActive()) {
            throw new ValidationException(ResponseCode.CommonValidationFailed,
                    "Session is not active. Status: " + session.getStatus());
        }

        session.setStatus("stopped");
        session.setUpdatedAt(Instant.now());
        debugSessionMapper.updateSession(session);

        publishEvent(session, "session_stopped", null, null);
        eventPublisher.closeSession(sessionId);

        Automation automation = automationMapper.findByPid(session.getAutomationId());
        int totalActions = automation != null && automation.getActions() != null ? automation.getActions().size() : 0;
        return toDTO(session, totalActions);
    }

    @Override
    public DebugSessionDTO restart(String sessionId) {
        DebugSession session = findSession(sessionId);

        // Reset session state
        session.setStatus("paused");
        session.setCurrentActionIndex(0);
        session.setActionResults(new ArrayList<>());
        session.setErrorMessage(null);

        // Reset context to initial state
        Map<String, Object> context = new HashMap<>();
        if (session.getTriggerPayload() != null) {
            context.putAll(session.getTriggerPayload());
        }
        if (session.getRecordId() != null) {
            context.put("recordId", session.getRecordId());
        }
        context.put("automationPid", session.getAutomationId());
        context.put("debugMode", true);
        session.setExecutionContext(context);

        session.setUpdatedAt(Instant.now());
        debugSessionMapper.updateSession(session);

        publishEvent(session, "session_paused", null, null);

        Automation automation = automationMapper.findByPid(session.getAutomationId());
        int totalActions = automation != null && automation.getActions() != null ? automation.getActions().size() : 0;

        log.info("Debug session restarted: pid={}", sessionId);
        return toDTO(session, totalActions);
    }

    @Override
    public Map<String, Object> getContext(String sessionId) {
        DebugSession session = findSession(sessionId);
        return session.getExecutionContext() != null ? session.getExecutionContext() : Map.of();
    }

    @Override
    public DebugSessionDTO updateBreakpoints(String sessionId, List<Integer> breakpoints) {
        DebugSession session = findSession(sessionId);
        session.setBreakpoints(breakpoints != null ? breakpoints : List.of());
        session.setUpdatedAt(Instant.now());
        debugSessionMapper.updateSession(session);

        Automation automation = automationMapper.findByPid(session.getAutomationId());
        int totalActions = automation != null && automation.getActions() != null ? automation.getActions().size() : 0;
        return toDTO(session, totalActions);
    }

    @Override
    public SseEmitter subscribeEvents(String sessionId) {
        // Validate session exists
        findSession(sessionId);
        return eventPublisher.subscribe(sessionId);
    }

    // ==================== Private Helpers ====================

    private DebugSession findSession(String sessionId) {
        DebugSession session = debugSessionMapper.findByPid(sessionId);
        if (session == null) {
            throw new ValidationException(ResponseCode.NOT_FOUND, "Debug session not found: " + sessionId);
        }
        return session;
    }

    private List<AutomationAction> getSortedActions(Automation automation) {
        List<AutomationAction> actions = automation.getActions();
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<AutomationAction> sorted = new ArrayList<>(actions);
        sorted.sort(Comparator.comparingInt(a -> a.getSequence() != null ? a.getSequence() : 0));
        return sorted;
    }

    private ActionResult executeAction(AutomationAction action, Map<String, Object> context) {
        ActionResult result = new ActionResult();
        result.setSequence(action.getSequence());
        result.setActionType(action.getType());

        long startTime = System.currentTimeMillis();

        try {
            Object actionResult = actionExecutor.execute(action, context);
            result.setStatus(StatusConstants.SUCCESS);
            result.setResult(actionResult);
        } catch (Exception e) {
            result.setStatus(StatusConstants.FAILED);
            result.setErrorMessage(e.getMessage());
            log.warn("Debug action execution failed: type={}, sequence={}, error={}",
                    action.getType(), action.getSequence(), e.getMessage());
        }

        result.setDurationMs(System.currentTimeMillis() - startTime);
        return result;
    }

    private void publishEvent(DebugSession session, String eventType,
                              AutomationAction action, ActionResult result) {
        DebugEventDTO event = DebugEventDTO.builder()
                .eventType(eventType)
                .sessionId(session.getPid())
                .actionIndex(session.getCurrentActionIndex())
                .actionType(action != null ? action.getType() : null)
                .actionLabel(action != null ? action.getLabel() : null)
                .actionResult(result)
                .context(session.getExecutionContext())
                .errorMessage(result != null ? result.getErrorMessage() : session.getErrorMessage())
                .timestamp(Instant.now())
                .build();

        eventPublisher.publish(session.getPid(), event);
    }

    private DebugSessionDTO toDTO(DebugSession session, int totalActions) {
        return DebugSessionDTO.builder()
                .id(session.getId())
                .pid(session.getPid())
                .automationId(session.getAutomationId())
                .recordId(session.getRecordId())
                .status(session.getStatus())
                .currentActionIndex(session.getCurrentActionIndex())
                .totalActions(totalActions)
                .breakpoints(session.getBreakpoints())
                .executionContext(session.getExecutionContext())
                .actionResults(session.getActionResults())
                .triggerPayload(session.getTriggerPayload())
                .errorMessage(session.getErrorMessage())
                .createdAt(session.getCreatedAt())
                .updatedAt(session.getUpdatedAt())
                .build();
    }
}
