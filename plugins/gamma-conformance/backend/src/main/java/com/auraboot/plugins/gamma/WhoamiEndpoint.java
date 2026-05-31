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
 * gamma-1 conformance route: {@code GET /api/ext/probe/whoami} returns the tenantId + userId
 * the platform injected — proving authentication + tenant-context propagation + routing +
 * delegation all work through the {@code RestEndpointExtension} SPI.
 */
@Extension
public class WhoamiEndpoint implements RestEndpointExtension {

    @Override
    public String namespace() {
        return "probe";
    }

    @Override
    public List<RestRoute> routes() {
        return List.of(RestRoute.of("GET", "/whoami", "probe.whoami.read"));
    }

    @Override
    public void handle(PluginHttpRequest req, PluginHttpResponse res, PluginRequestContext ctx) throws Exception {
        String json = "{\"tenantId\":" + ctx.tenantId()
                + ",\"userId\":" + ctx.userId()
                + ",\"zone\":\"" + ctx.zoneId() + "\""
                + ",\"public\":" + ctx.isPublic() + "}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        res.status(200).contentType("application/json").header("X-Gamma-Probe", "whoami");
        res.out().write(body);
    }
}
