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
 * gamma-3 conformance: a non-JSON / binary response. {@code GET /api/ext/probe/report.csv}
 * streams CSV bytes (Content-Type {@code text/csv}) via {@link PluginHttpResponse#out()},
 * proving the SPI is not JSON-only.
 *
 * <p>Note: the governed pipeline buffers the response in memory and flushes it after commit
 * (so a rollback never leaks a partial body). That keeps the transaction/audit guarantees for
 * binary payloads; true large-file chunked streaming (bypassing the buffer) is intentionally
 * out of scope because it cannot coexist with rollback-safety.
 */
@Extension
public class ReportEndpoint implements RestEndpointExtension {

    @Override
    public String namespace() {
        return "probe";
    }

    @Override
    public List<RestRoute> routes() {
        return List.of(RestRoute.of("GET", "/report.csv", "probe.report.read"));
    }

    @Override
    public void handle(PluginHttpRequest req, PluginHttpResponse res, PluginRequestContext ctx) throws Exception {
        String csv = "id,text\n1,alpha\n2,beta\n";
        res.status(200).contentType("text/csv").header("X-Gamma-Probe", "report");
        res.out().write(csv.getBytes(StandardCharsets.UTF_8));
    }
}
