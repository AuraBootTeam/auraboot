package com.auraboot.framework.plugin.extension;

import java.util.Map;

/**
 * Host-provided in-process command dispatch bridge for plugin command handlers.
 *
 * <p>Plugins receive this accessor through the command settings map
 * ({@code __commandDispatcher}). It lets a handler chain a <em>follow-up</em>
 * command through the host's full command pipeline — schema validation,
 * invariants, domain events, and the {@code ab_command_audit_log} audit row —
 * instead of instantiating another handler class and calling {@code execute}
 * directly, which silently skips every one of those boundaries (Q03-04).</p>
 *
 * <p>The nested execution joins the caller's transaction and runs under the
 * already-authorized outer command, so no additional endpoint permission is
 * involved. Dispatch failures throw, exactly like an outer command failure.
 * Returns {@code null} when the connected host does not yet provide the
 * bridge — callers should fall back to their legacy chain path.</p>
 */
public interface CommandDispatcherAccessor {

    /** Well-known key for CommandDispatcherAccessor in the command settings map. */
    String SETTINGS_KEY = "__commandDispatcher";

    /**
     * Execute a nested command through the host command pipeline.
     *
     * @param commandCode the command definition code
     *                    (e.g. {@code "qo_quote_common:compute_process_fee"})
     * @param modelCode   the model the command targets
     * @param recordPid   the target record pid
     * @param payload     the command payload (may be null)
     * @return the command's result data, or {@code null} when dispatch is
     *         unavailable on this host
     */
    Object execute(String commandCode, String modelCode, String recordPid, Map<String, Object> payload);
}
