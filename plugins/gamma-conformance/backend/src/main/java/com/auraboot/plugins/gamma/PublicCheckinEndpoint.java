package com.auraboot.plugins.gamma;

import com.auraboot.framework.plugin.extension.AuthPolicy;
import com.auraboot.framework.plugin.extension.PluginHttpRequest;
import com.auraboot.framework.plugin.extension.PluginHttpResponse;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import com.auraboot.framework.plugin.extension.RestEndpointExtension;
import com.auraboot.framework.plugin.extension.RestRoute;
import org.pf4j.Extension;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * gamma-3 conformance: a PUBLIC (unauthenticated) endpoint. {@code POST /api/ext/probe/public/checkin}
 * requires no JWT and no permission — the security WhiteList exposes the {@code /public/} subpath and
 * the dispatcher binds a default-tenant public context (declare-and-serve, DDR D6).
 *
 * <p>Proves: no-auth reachability + public-context binding ({@code ctx.isPublic()==true},
 * default tenant) + mandatory audit + per-IP rate limiting (all enforced by the platform, not here).
 */
@Extension
public class PublicCheckinEndpoint implements RestEndpointExtension {

    @Override
    public String namespace() {
        return "probe";
    }

    @Override
    public List<RestRoute> routes() {
        // No permissionCode: PUBLIC routes are unauthenticated. Must live under /public/ (fail-closed).
        return List.of(new RestRoute("POST", "/public/checkin", null, AuthPolicy.PUBLIC, false, false, null));
    }

    @Override
    public void handle(PluginHttpRequest req, PluginHttpResponse res, PluginRequestContext ctx) throws Exception {
        String json = "{\"public\":" + ctx.isPublic()
                + ",\"tenantId\":" + ctx.tenantId()
                + ",\"userId\":" + ctx.userId() + "}";
        res.status(200).contentType("application/json").header("X-Gamma-Probe", "public-checkin");
        res.out().write(json.getBytes(StandardCharsets.UTF_8));
    }
}
