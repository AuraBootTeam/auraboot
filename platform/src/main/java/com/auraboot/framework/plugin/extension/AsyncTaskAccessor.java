package com.auraboot.framework.plugin.extension;

import java.util.Map;

/**
 * Host-provided async task submission bridge for plugin command handlers.
 *
 * <p>Plugins receive this accessor through
 * {@link CommandHandlerExtension.CommandContext#asyncTaskAccessor()}. It lets a
 * <em>synchronous</em> command handler offload long-running follow-up work —
 * e.g. Gerber parsing or BOM import — onto the platform's background async-task
 * queue. The originating command then returns fast (so the UI can navigate
 * immediately), while the deferred work runs in the background and reports
 * progress through the async-task status surface (e.g. detail-page status
 * banners polling the async task state).</p>
 */
public interface AsyncTaskAccessor {

    /** Well-known key for AsyncTaskAccessor in the command settings map. */
    String SETTINGS_KEY = "__asyncTaskAccessor";

    /**
     * Submit a command to run as a background async task.
     *
     * <p>The command's handler is resolved by its code on the executing thread,
     * with the supplied tenant/user context preserved by the host.</p>
     *
     * @param commandCode the command to run asynchronously
     * @param modelCode   the model the command targets
     * @param recordId    the target record id (e.g. the just-created quote pid)
     * @param payload     the command payload
     * @return the submitted task code, or {@code null} when submission is unavailable
     */
    String submitCommandTask(String commandCode, String modelCode, String recordPid, Map<String, Object> payload);
}
