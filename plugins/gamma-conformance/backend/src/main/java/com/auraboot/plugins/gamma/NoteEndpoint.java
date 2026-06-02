package com.auraboot.plugins.gamma;

import com.auraboot.framework.plugin.extension.PluginHttpRequest;
import com.auraboot.framework.plugin.extension.PluginHttpResponse;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import com.auraboot.framework.plugin.extension.RestEndpointExtension;
import com.auraboot.framework.plugin.extension.RestRoute;
import org.pf4j.Extension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * gamma-1 conformance: governed CRUD from a plugin REST endpoint via the injected
 * {@link PluginRequestContext#dataAccessor()}.
 *
 * <ul>
 *   <li>{@code POST /api/ext/probe/notes?text=...} creates a {@code probe_note} record.</li>
 *   <li>{@code GET  /api/ext/probe/notes} lists this tenant's notes (count).</li>
 * </ul>
 *
 * Proves the plugin REST handler reaches the platform's tenant-scoped, model-driven data layer
 * (create persists, query reads it back) — no host internals touched.
 */
@Extension
public class NoteEndpoint implements RestEndpointExtension {

    private static final String MODEL = "probe_note";

    @Override
    public String namespace() {
        return "probe";
    }

    @Override
    public List<RestRoute> routes() {
        return List.of(
                RestRoute.of("POST", "/notes", "probe.note.write"),
                RestRoute.of("GET", "/notes", "probe.note.read"));
    }

    @Override
    public void handle(PluginHttpRequest req, PluginHttpResponse res, PluginRequestContext ctx) throws Exception {
        if ("POST".equalsIgnoreCase(req.method())) {
            List<String> text = req.query().get("text");
            String value = (text != null && !text.isEmpty()) ? text.get(0) : "";
            ctx.dataAccessor().create(MODEL, Map.of("probe_note_text", value));
            writeJson(res, 201, "{\"created\":true,\"text\":\"" + value + "\"}");
            return;
        }
        List<Map<String, Object>> rows = ctx.dataAccessor().query(MODEL, Map.of());
        writeJson(res, 200, "{\"count\":" + rows.size() + ",\"tenantId\":" + ctx.tenantId() + "}");
    }

    private void writeJson(PluginHttpResponse res, int status, String json) throws Exception {
        res.status(status).contentType("application/json").header("X-Gamma-Probe", "notes");
        res.out().write(json.getBytes(StandardCharsets.UTF_8));
    }
}
