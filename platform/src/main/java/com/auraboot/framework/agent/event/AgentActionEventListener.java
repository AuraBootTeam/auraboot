package com.auraboot.framework.agent.event;

import com.auraboot.framework.agent.service.ActionRecorder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Listens for AgentActionEvent from ChatToolExecutor (core module)
 * and delegates to ActionRecorder (enterprise-ai module).
 *
 * This bridges the module boundary: core publishes events, enterprise-ai records Actions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentActionEventListener {

    private final ActionRecorder actionRecorder;

    @EventListener
    public void onAgentAction(AgentActionEvent event) {
        try {
            if (event.getKind() == AgentActionEvent.ActionKind.COMMAND) {
                actionRecorder.recordAction(
                        event.getTenantId(),
                        event.getRunId(),
                        event.getCommandCode(),
                        null,  // no AgentToolDefinition in chat context
                        event.getInput(),
                        null,  // no CommandExecuteResult (already completed)
                        event.getBeforeData(),
                        event.getAfterData(),
                        event.getError());
            } else if (event.getKind() == AgentActionEvent.ActionKind.QUERY) {
                actionRecorder.recordReadAction(
                        event.getTenantId(),
                        event.getRunId(),
                        event.getCommandCode(),  // query code
                        null,
                        event.getInput(),
                        event.getResultCount(),
                        event.getError());
            }
        } catch (Exception e) {
            log.error("Failed to record action from event: {}", e.getMessage(), e);
        }
    }
}
