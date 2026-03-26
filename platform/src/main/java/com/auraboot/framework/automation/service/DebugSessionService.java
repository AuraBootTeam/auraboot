package com.auraboot.framework.automation.service;

import com.auraboot.framework.automation.dto.DebugSessionCreateRequest;
import com.auraboot.framework.automation.dto.DebugSessionDTO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * Service for automation debug sessions.
 * Provides step-through debugging of automation workflows.
 *
 * @author AuraBoot Team
 * @since 2.3.0
 */
public interface DebugSessionService {

    /**
     * Create a new debug session for an automation.
     * Session starts in PAUSED state at action index 0.
     */
    DebugSessionDTO createSession(String automationId, DebugSessionCreateRequest request);

    /**
     * Get debug session by PID.
     */
    DebugSessionDTO getSession(String sessionId);

    /**
     * Execute the next action and pause.
     * Returns updated session state.
     */
    DebugSessionDTO step(String sessionId);

    /**
     * Continue execution until next breakpoint or completion.
     */
    DebugSessionDTO continueExecution(String sessionId);

    /**
     * Stop the debug session.
     */
    DebugSessionDTO stop(String sessionId);

    /**
     * Restart the debug session from the beginning.
     */
    DebugSessionDTO restart(String sessionId);

    /**
     * Get current execution context.
     */
    Map<String, Object> getContext(String sessionId);

    /**
     * Update breakpoints for a session.
     */
    DebugSessionDTO updateBreakpoints(String sessionId, List<Integer> breakpoints);

    /**
     * Subscribe to real-time debug events via SSE.
     */
    SseEmitter subscribeEvents(String sessionId);
}
