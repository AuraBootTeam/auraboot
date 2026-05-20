package com.auraboot.framework.agent.runtime;

import java.util.Map;

/**
 * Durable execution ledger for approved pending-tool side effects.
 */
public interface PendingToolExecutionLedger {

    PendingToolExecutionClaim claim(PendingToolSnapshot pendingTool);

    void complete(PendingToolSnapshot pendingTool, String executionKey, Map<String, Object> result);

    void fail(PendingToolSnapshot pendingTool,
              String executionKey,
              Map<String, Object> result,
              String errorMessage);
}
