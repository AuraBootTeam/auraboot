package com.auraboot.framework.agent.spi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
public class DefaultAgentExecutionService implements AgentExecutionService {

    private static final String UPGRADE_MESSAGE =
        "Agent execution requires Professional license. " +
        "Community edition includes AuraBot Copilot for basic AI conversations.";

    @Override
    public AgentExecutionResult execute(String agentPid, String taskPid, Map<String, Object> input) {
        log.info("Agent execution requested but enterprise module not loaded. agentPid={}", agentPid);
        return AgentExecutionResult.unavailable(UPGRADE_MESSAGE);
    }

    @Override
    public AgentExecutionResult resume(String runPid) {
        log.info("Agent resume requested but enterprise module not loaded. runPid={}", runPid);
        return AgentExecutionResult.unavailable(UPGRADE_MESSAGE);
    }
}
