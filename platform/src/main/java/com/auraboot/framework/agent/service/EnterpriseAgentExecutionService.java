package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.spi.AgentExecutionService;
import com.auraboot.framework.application.tenant.MetaContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Enterprise implementation of AgentExecutionService.
 * Delegates to AgentRunService for full 20-round tool-loop orchestration.
 * Marked @Primary to override the DefaultAgentExecutionService in core.
 */
@Slf4j
@Service("enterpriseAgentExecutionService")
@Primary
@RequiredArgsConstructor
public class EnterpriseAgentExecutionService implements AgentExecutionService {

    private final AgentRunService agentRunService;

    @Override
    public AgentExecutionResult execute(String agentCode, String taskPid, Map<String, Object> input) {
        Long tenantId = MetaContext.getCurrentTenantId();
        log.info("Enterprise agent execution: agentCode={}, taskPid={}, tenantId={}", agentCode, taskPid, tenantId);
        agentRunService.executeTask(tenantId, taskPid, agentCode);
        return AgentExecutionResult.started(taskPid);
    }

    @Override
    public AgentExecutionResult resume(String runPid) {
        log.info("Enterprise agent resume: runPid={}", runPid);
        // Resume is not directly supported by AgentRunService.executeTaskWithResume
        // without a taskPid and agentCode. For now, log and return started status.
        // The full resume flow goes through AgentDispatchHandler.dispatchWithResume.
        return AgentExecutionResult.started(runPid);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
