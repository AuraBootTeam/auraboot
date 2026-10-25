package com.auraboot.framework.agent.service;

import java.util.Map;

/**
 * Pluggable shadow-mode invoker for one substrate's tool_refs.
 *
 * Each substrate (named-query, DSL command, MCP, code, api) contributes
 * a Spring bean implementing this interface. {@link ShadowExecutor}
 * dispatches to the first invoker whose {@link #supports} returns true.
 *
 * Contract:
 *   - For FULL-support tool_refs, invoke the real path in read-only mode
 *     and return the raw result payload.
 *   - For SIMULATED-support tool_refs, run validation + before-snapshot
 *     but skip commit.
 *   - Invokers MUST NOT produce side effects.
 */
public interface ShadowToolInvoker {

    boolean supports(String toolRef);

    /**
     * @return the shadow result payload — serialised to JSON for hashing
     *         and storage. Null means the invoker chose to skip.
     */
    Map<String, Object> invokeShadow(Long tenantId, String toolRef, Map<String, Object> args);
}
