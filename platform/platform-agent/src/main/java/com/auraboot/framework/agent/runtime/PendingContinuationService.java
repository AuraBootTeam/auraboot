package com.auraboot.framework.agent.runtime;

import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;

/**
 * Shared boundary for resuming suspended chat-tool continuations.
 */
public interface PendingContinuationService {

    /** Persist truthful terminal tool results before acknowledging a denial or cancellation. */
    void completeRejectedChatTool(PendingToolSnapshot pending, String reason);

    TurnOutcome resumeApprovedChatTool(TurnContext ctx,
                                       PendingToolSnapshot pending,
                                       ResponseSink sink);
}
