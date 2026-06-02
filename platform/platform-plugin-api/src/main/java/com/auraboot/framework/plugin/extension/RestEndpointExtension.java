package com.auraboot.framework.plugin.extension;

import org.pf4j.ExtensionPoint;

import java.util.List;

/**
 * Extension point: a plugin contributes custom HTTP routes served by the platform
 * dispatcher at {@code /api/ext/{namespace}/**}.
 *
 * <p>The platform applies authentication, tenant context, permission, transaction and
 * audit BEFORE delegating to {@link #handle}. Plugins never register Spring controllers,
 * and handlers depend only on the neutral {@link PluginHttpRequest}/{@link PluginHttpResponse}
 * abstraction (no jakarta.servlet) — mirroring the {@code CommandHandlerExtension} model.
 */
public interface RestEndpointExtension extends ExtensionPoint {

    /** Plugin namespace; MUST equal the plugin manifest namespace (e.g. "probe"). */
    String namespace();

    /** All routes this extension serves. Used for registry indexing and dispatch. */
    List<RestRoute> routes();

    /**
     * Handle a request the platform has already matched, authenticated, tenant-scoped and
     * permission-checked. Write the response via {@code res}.
     *
     * @throws Exception any failure; the dispatcher pipeline maps it to a 5xx (and rolls
     *                   back the transaction in gamma-2). Do NOT swallow with catch(Exception).
     */
    void handle(PluginHttpRequest req, PluginHttpResponse res, PluginRequestContext ctx) throws Exception;

    /** Higher priority wins when multiple extensions match the same route. */
    default int getPriority() {
        return 0;
    }
}
