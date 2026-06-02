package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.PluginHttpRequest;
import com.auraboot.framework.plugin.extension.PluginHttpResponse;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import com.auraboot.framework.plugin.extension.AuthPolicy;
import com.auraboot.framework.plugin.extension.RestEndpointExtension;
import com.auraboot.framework.plugin.extension.RestRoute;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RestEndpointRegistryTest {

    /** Minimal stub extension serving GET /whoami under namespace "probe". */
    static final class ProbeExt implements RestEndpointExtension {
        @Override public String namespace() { return "probe"; }
        @Override public List<RestRoute> routes() {
            return List.of(RestRoute.of("GET", "/whoami", "probe.probe.read"));
        }
        @Override public void handle(PluginHttpRequest req, PluginHttpResponse res, PluginRequestContext ctx) {
            // no-op for registry tests
        }
    }

    @SuppressWarnings("unchecked")
    private RestEndpointRegistry registryWith(RestEndpointExtension... exts) {
        AuraPluginManager pm = mock(AuraPluginManager.class);
        when(pm.getExtensionsOfType(RestEndpointExtension.class)).thenReturn(List.of(exts));
        ObjectProvider<RestEndpointExtension> core = mock(ObjectProvider.class);
        // getAll() recomputes each call now (hot-reload safe) -> hand back a fresh stream each time.
        when(core.stream()).thenAnswer(inv -> Stream.empty());
        return new RestEndpointRegistry(pm, core);
    }

    @Test
    void match_hitsDeclaredRoute() {
        RestEndpointRegistry reg = registryWith(new ProbeExt());
        var match = reg.match("probe", "GET", "/whoami");
        assertThat(match).isPresent();
        assertThat(match.get().route().permissionCode()).isEqualTo("probe.probe.read");
        assertThat(match.get().pathVars()).isEmpty();
    }

    @Test
    void match_missesUnknownPath() {
        RestEndpointRegistry reg = registryWith(new ProbeExt());
        assertThat(reg.match("probe", "GET", "/nope")).isEmpty();
    }

    @Test
    void match_missesWrongNamespace() {
        RestEndpointRegistry reg = registryWith(new ProbeExt());
        assertThat(reg.match("other", "GET", "/whoami")).isEmpty();
    }

    /** gamma-2 fail-closed: AUTHENTICATED route declaring a blank permissionCode is a
     *  misconfiguration. The registry must refuse to match it (effectively 404 + logged)
     *  rather than letting it reach the dispatcher's runtime permission check. */
    static final class MisconfiguredExt implements RestEndpointExtension {
        @Override public String namespace() { return "probe"; }
        @Override public List<RestRoute> routes() {
            return List.of(new RestRoute("GET", "/leaky", "  ", AuthPolicy.AUTHENTICATED, false, false, null));
        }
        @Override public void handle(PluginHttpRequest req, PluginHttpResponse res, PluginRequestContext ctx) { }
    }

    @Test
    void match_failsClosedOnAuthenticatedRouteWithBlankPermission() {
        RestEndpointRegistry reg = registryWith(new MisconfiguredExt());
        assertThat(reg.match("probe", "GET", "/leaky")).isEmpty();
    }

    /** A PUBLIC route legitimately has no permissionCode and (gamma-3 convention) sits under the
     *  /public/ subpath that the security WhiteList exposes — it must match. */
    static final class PublicExt implements RestEndpointExtension {
        @Override public String namespace() { return "probe"; }
        @Override public List<RestRoute> routes() {
            return List.of(new RestRoute("GET", "/public/open", null, AuthPolicy.PUBLIC, false, false, null));
        }
        @Override public void handle(PluginHttpRequest req, PluginHttpResponse res, PluginRequestContext ctx) { }
    }

    @Test
    void match_allowsPublicRouteUnderPublicSubpath() {
        RestEndpointRegistry reg = registryWith(new PublicExt());
        assertThat(reg.match("probe", "GET", "/public/open")).isPresent();
    }

    /** gamma-3 fail-closed: a PUBLIC route NOT under /public/ would never be whitelisted by the
     *  security layer (so it would confusingly 401) and risks exposing a non-public path. The
     *  registry refuses to match it. */
    static final class PublicOutsideSubpathExt implements RestEndpointExtension {
        @Override public String namespace() { return "probe"; }
        @Override public List<RestRoute> routes() {
            return List.of(new RestRoute("GET", "/open", null, AuthPolicy.PUBLIC, false, false, null));
        }
        @Override public void handle(PluginHttpRequest req, PluginHttpResponse res, PluginRequestContext ctx) { }
    }

    @Test
    void match_failsClosedOnPublicRouteOutsidePublicSubpath() {
        RestEndpointRegistry reg = registryWith(new PublicOutsideSubpathExt());
        assertThat(reg.match("probe", "GET", "/open")).isEmpty();
    }
}
