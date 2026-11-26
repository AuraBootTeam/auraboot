package com.auraboot.framework.plugin.rest;

import com.auraboot.framework.plugin.extension.AuthPolicy;
import com.auraboot.framework.plugin.extension.RestEndpointExtension;
import com.auraboot.framework.plugin.extension.RestPublication;
import com.auraboot.framework.plugin.extension.RestRoute;
import com.auraboot.framework.plugin.pf4j.RestEndpointRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RestPublicationCorsSourceTest {
    private static final String PATH = "/api/ext/probe/public/forms/F1/submit";
    private static final String ALLOWED = "https://publisher.example.test";
    private final RestEndpointRegistry registry = mock(RestEndpointRegistry.class);
    private final RestPublicationStore store = mock(RestPublicationStore.class);
    private final RestPublicationCorsSource source = new RestPublicationCorsSource(registry, store);

    @Test
    void permitsOnlyThePublishedOriginWithoutCredentialsForPreflightAndPost() throws Exception {
        register(AuthPolicy.PUBLIC);
        when(store.allowsOrigin("probe", "form", "F1", ALLOWED)).thenReturn(true);

        for (String method : new String[]{"OPTIONS", "POST"}) {
            var request = browserRequest(method, ALLOWED);
            var response = new MockHttpServletResponse();
            assertThat(new DefaultCorsProcessor().processRequest(source.getCorsConfiguration(request), request, response))
                    .isTrue();
            assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(ALLOWED);
            assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
        }

        var rejected = browserRequest("OPTIONS", "https://other.example.test");
        var response = new MockHttpServletResponse();
        assertThat(new DefaultCorsProcessor().processRequest(source.getCorsConfiguration(rejected), rejected, response))
                .isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void leavesNonPublicRoutesToTheExistingPolicy() {
        register(AuthPolicy.AUTHENTICATED);
        assertThat(source.getCorsConfiguration(browserRequest("OPTIONS", ALLOWED))).isNull();
        verifyNoInteractions(store);
    }

    private void register(AuthPolicy policy) {
        var extension = mock(RestEndpointExtension.class);
        var route = new RestRoute("POST", "/public/forms/{pid}/submit", null, policy, false, false, null);
        when(extension.publicationCors(route)).thenReturn(new RestPublication("form", "pid"));
        when(registry.match("probe", "POST", "/public/forms/F1/submit"))
                .thenReturn(Optional.of(new RestEndpointRegistry.Match(extension, route, Map.of("pid", "F1"))));
    }

    private MockHttpServletRequest browserRequest(String method, String origin) {
        var request = new MockHttpServletRequest(method, PATH);
        request.addHeader("Origin", origin);
        if ("OPTIONS".equals(method)) {
            request.addHeader("Access-Control-Request-Method", "POST");
            request.addHeader("Access-Control-Request-Headers", "content-type");
        }
        return request;
    }
}
