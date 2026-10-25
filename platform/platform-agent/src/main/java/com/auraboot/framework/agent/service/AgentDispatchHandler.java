package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Dispatches agent tasks for execution.
 */
@Slf4j
@Component("agentDispatchHandler")
@RequiredArgsConstructor
public class AgentDispatchHandler {

    private final AgentProperties agentProperties;
    private final AgentRunService agentRunService;
    private final AgentObservationService observationService;

    public void dispatch(Long tenantId, String taskPid, String agentCode) {
        if (!agentProperties.isEnabled()) {
            log.info("Agent runtime disabled, task {} not dispatched", taskPid);
            return;
        }
        if (agentCode == null || agentCode.isBlank()) {
            log.warn("Cannot dispatch task {}: no agent assigned", taskPid);
            return;
        }

        log.info("Dispatching task {} to agent {} in tenant {}", taskPid, agentCode, tenantId);
        observationService.publish(tenantId, "task_dispatched", agentCode, "agent_task", taskPid,
                Map.of("agent_code", agentCode));

        agentRunService.executeTask(tenantId, taskPid, agentCode);
    }

    public void dispatchWithResume(Long tenantId, String taskPid, String agentCode, String resumeFromRunPid) {
        if (!agentProperties.isEnabled()) {
            log.info("Agent runtime disabled, task {} not dispatched", taskPid);
            return;
        }
        log.info("Resuming task {} for agent {} from run {}", taskPid, agentCode, resumeFromRunPid);
        observationService.publish(tenantId, "task_dispatched", agentCode, "agent_task", taskPid,
                Map.of("agent_code", agentCode, "resumed_from", resumeFromRunPid));
        agentRunService.executeTaskWithResume(tenantId, taskPid, agentCode, resumeFromRunPid);
    }
}
