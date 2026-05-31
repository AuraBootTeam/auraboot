package com.auraboot.framework.plugin.pf4j;

import com.auraboot.framework.plugin.extension.PluginHttpRequest;
import com.auraboot.framework.plugin.extension.PluginHttpResponse;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
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
        when(core.stream()).thenReturn(Stream.empty());
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
}
