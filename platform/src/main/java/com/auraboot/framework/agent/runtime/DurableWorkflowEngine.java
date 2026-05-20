package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;

/**
 * Durable execution substrate for conversation-triggered agent runs.
 */
public interface DurableWorkflowEngine {

    boolean isAvailable();

    TurnOutcome startConversationRun(TurnContext ctx, ChatRequest legacyRequest, ResponseSink sink);

    TurnOutcome resumeConversationRun(TurnContext ctx, String taskPid, String runPid, ResponseSink sink);
}
