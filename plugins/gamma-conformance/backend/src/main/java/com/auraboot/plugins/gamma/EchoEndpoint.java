package com.auraboot.plugins.gamma;

import com.auraboot.framework.plugin.extension.PluginHttpRequest;
import com.auraboot.framework.plugin.extension.PluginHttpResponse;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import com.auraboot.framework.plugin.extension.RestEndpointExtension;
import com.auraboot.framework.plugin.extension.RestRoute;
import org.pf4j.Extension;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * gamma-2 conformance: exercises the governed pipeline's JSON-schema pre-validation and
 * idempotency replay.
 *
 * <ul>
 *   <li>{@code POST /api/ext/probe/echo} with body {@code {"text":"..."}} returns the echoed body.</li>
 *   <li>The route declares {@code requestJsonSchema} requiring a non-blank {@code text} — a body
 *       failing the schema is rejected with HTTP 400 <em>before</em> this handler runs.</li>
 *   <li>The route is {@code idempotent}: a repeat call carrying the same {@code Idempotency-Key}
 *       header replays the first response (platform adds {@code X-Idempotent-Replay: true}) without
 *       re-running the handler.</li>
 * </ul>
 */
@Extension
public class EchoEndpoint implements RestEndpointExtension {

    private static final String SCHEMA = """
            {
              "type": "object",
              "required": ["text"],
              "properties": { "text": { "type": "string", "minLength": 1 } }
            }
            """;

    @Override
    public String namespace() {
        return "probe";
    }

    @Override
    public List<RestRoute> routes() {
        // method, pathPattern, permissionCode, authPolicy(null->AUTHENTICATED), idempotent, readOnlyTx, requestJsonSchema
        return List.of(new RestRoute("POST", "/echo", "probe.echo.write", null, true, false, SCHEMA));
    }

    @Override
    public void handle(PluginHttpRequest req, PluginHttpResponse res, PluginRequestContext ctx) throws Exception {
        // Body is already schema-validated to be a JSON object with a non-blank "text".
        String body = new String(req.body(), StandardCharsets.UTF_8);
        res.status(200).contentType("application/json").header("X-Gamma-Probe", "echo");
        res.out().write(("{\"echo\":" + body + "}").getBytes(StandardCharsets.UTF_8));
    }
}
