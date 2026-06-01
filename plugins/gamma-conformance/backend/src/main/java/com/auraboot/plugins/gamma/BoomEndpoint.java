package com.auraboot.plugins.gamma;

import com.auraboot.framework.plugin.extension.PluginHttpRequest;
import com.auraboot.framework.plugin.extension.PluginHttpResponse;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import com.auraboot.framework.plugin.extension.RestEndpointExtension;
import com.auraboot.framework.plugin.extension.RestRoute;
import org.pf4j.Extension;

import java.util.List;
import java.util.Map;

/**
 * gamma-2 conformance: proves the governed pipeline's transaction + no-swallow guarantees.
 *
 * <p>{@code POST /api/ext/probe/boom} first writes a {@code probe_note} row via the governed
 * {@code dataAccessor()} and then throws. Because the handler runs inside the pipeline's
 * transaction, the write must roll back (a subsequent {@code GET /api/ext/probe/notes} count is
 * unchanged), and because exceptions are never swallowed (red line #8) the request fails with
 * HTTP 500 rather than a silent success.
 */
@Extension
public class BoomEndpoint implements RestEndpointExtension {

    private static final String MODEL = "probe_note";

    @Override
    public String namespace() {
        return "probe";
    }

    @Override
    public List<RestRoute> routes() {
        return List.of(RestRoute.of("POST", "/boom", "probe.boom.write"));
    }

    @Override
    public void handle(PluginHttpRequest req, PluginHttpResponse res, PluginRequestContext ctx) throws Exception {
        // Write inside the request transaction, then fail. The pipeline must roll this back.
        ctx.dataAccessor().create(MODEL, Map.of("probe_note_text", "boom-should-rollback"));
        throw new IllegalStateException("boom: intentional failure to prove rollback + no-swallow");
    }
}
