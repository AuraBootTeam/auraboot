package com.auraboot.framework.agent.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.util.Map;

/**
 * Published when a tool execution completes (command or query).
 * Consumed by ActionRecorder in platform-enterprise-ai to create ab_agent_action records.
 * Decouples the core module from the enterprise-ai module.
 */
@Getter
public class AgentActionEvent extends ApplicationEvent {

    public enum ActionKind { COMMAND, QUERY }

    private final ActionKind kind;
    private final Long tenantId;
    private final String runId;           // "aurabot_chat" for chat context
    private final String commandCode;     // command code or query code
    private final String modelCode;       // target model (nullable for queries)
    private final Map<String, Object> input;
    private final Map<String, Object> beforeData;
    private final Map<String, Object> afterData;
    private final String recordPid;
    private final int resultCount;        // for queries
    private final String error;
    private final String riskLevel;

    private AgentActionEvent(Object source, ActionKind kind, Long tenantId, String runId,
                              String commandCode, String modelCode, Map<String, Object> input,
                              Map<String, Object> beforeData, Map<String, Object> afterData,
                              String recordPid, int resultCount, String error, String riskLevel) {
        super(source);
        this.kind = kind;
        this.tenantId = tenantId;
        this.runId = runId;
        this.commandCode = commandCode;
        this.modelCode = modelCode;
        this.input = input;
        this.beforeData = beforeData;
        this.afterData = afterData;
        this.recordPid = recordPid;
        this.resultCount = resultCount;
        this.error = error;
        this.riskLevel = riskLevel;
    }

    public static AgentActionEvent command(Object source, Long tenantId, String runId,
                                            String commandCode, String modelCode,
                                            Map<String, Object> input,
                                            Map<String, Object> beforeData, Map<String, Object> afterData,
                                            String recordPid, String error) {
        return new AgentActionEvent(source, ActionKind.COMMAND, tenantId, runId,
                commandCode, modelCode, input, beforeData, afterData, recordPid, 1, error, null);
    }

    public static AgentActionEvent query(Object source, Long tenantId, String runId,
                                          String queryCode, Map<String, Object> input,
                                          int resultCount, String error) {
        return new AgentActionEvent(source, ActionKind.QUERY, tenantId, runId,
                queryCode, null, input, null, null, null, resultCount, error, "L0");
    }
}
