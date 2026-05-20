package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;

/**
 * Shared boundary for resuming suspended chat-tool continuations.
 */
public interface PendingContinuationService {

    TurnOutcome resumeApprovedChatTool(TurnContext ctx,
                                       PendingToolSnapshot pending,
                                       ResponseSink sink);
}
