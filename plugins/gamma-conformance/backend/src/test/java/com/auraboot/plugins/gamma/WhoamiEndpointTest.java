package com.auraboot.plugins.gamma;

import com.auraboot.framework.plugin.extension.DataAccessor;
import com.auraboot.framework.plugin.extension.PluginHttpResponse;
import com.auraboot.framework.plugin.extension.PluginRequestContext;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class WhoamiEndpointTest {

    private static PluginRequestContext ctx(long tenant, long user) {
        return new PluginRequestContext() {
            @Override public Long tenantId() { return tenant; }
            @Override public Long userId() { return user; }
            @Override public ZoneId zoneId() { return ZoneId.of("Asia/Shanghai"); }
            @Override public String generateId() { return "01J0TESTID"; }
            @Override public boolean isPublic() { return false; }
            @Override public DataAccessor dataAccessor() { return null; }
        };
    }

    private static final class CapturingResponse implements PluginHttpResponse {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int status;
        @Override public PluginHttpResponse status(int code) { this.status = code; return this; }
        @Override public PluginHttpResponse header(String name, String value) { return this; }
        @Override public PluginHttpResponse contentType(String mediaType) { return this; }
        @Override public OutputStream out() { return body; }
    }

    @Test
    void handle_writesInjectedTenantAndUserAsJson() throws Exception {
        CapturingResponse res = new CapturingResponse();
        new WhoamiEndpoint().handle(null, res, ctx(7L, 3L));
        assertThat(res.status).isEqualTo(200);
        assertThat(res.body.toString())
                .contains("\"tenantId\":7")
                .contains("\"userId\":3")
                .contains("Asia/Shanghai");
    }

    @Test
    void declaresAuthenticatedWhoamiRoute() {
        WhoamiEndpoint endpoint = new WhoamiEndpoint();
        assertThat(endpoint.namespace()).isEqualTo("probe");
        assertThat(endpoint.routes()).hasSize(1);
        assertThat(endpoint.routes().get(0).permissionCode()).isEqualTo("probe.whoami.read");
        assertThat(endpoint.routes().get(0).method()).isEqualTo("GET");
    }
}
