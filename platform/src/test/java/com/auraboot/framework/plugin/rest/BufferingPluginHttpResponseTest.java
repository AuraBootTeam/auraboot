package com.auraboot.framework.plugin.rest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class BufferingPluginHttpResponseTest {

    @Test
    void capturesStatusHeaderContentTypeAndBody_withoutTouchingServlet() throws Exception {
        BufferingPluginHttpResponse buf = new BufferingPluginHttpResponse();

        buf.status(201).contentType("application/json").header("X-Gamma-Probe", "echo");
        buf.out().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        assertThat(buf.status()).isEqualTo(201);
        assertThat(buf.contentType()).isEqualTo("application/json");
        assertThat(buf.headers()).containsEntry("X-Gamma-Probe", "echo");
        assertThat(new String(buf.body(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
    }

    @Test
    void defaultStatusIs200WhenHandlerNeverSetsIt() {
        BufferingPluginHttpResponse buf = new BufferingPluginHttpResponse();
        assertThat(buf.status()).isEqualTo(200);
    }

    @Test
    void flushToCopiesStatusHeaderContentTypeAndBodyIntoServletResponse() throws Exception {
        BufferingPluginHttpResponse buf = new BufferingPluginHttpResponse();
        buf.status(201).contentType("application/json").header("X-Gamma-Probe", "echo");
        buf.out().write("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse servlet = new MockHttpServletResponse();
        buf.flushTo(servlet);

        assertThat(servlet.getStatus()).isEqualTo(201);
        assertThat(servlet.getContentType()).isEqualTo("application/json");
        assertThat(servlet.getHeader("X-Gamma-Probe")).isEqualTo("echo");
        assertThat(servlet.getContentAsString()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void toOutcomeMapAndBackRoundTripsForIdempotentReplay() {
        BufferingPluginHttpResponse buf = new BufferingPluginHttpResponse();
        buf.status(201).contentType("application/json").header("X-Gamma-Probe", "echo");
        buf.bodyBytes("{\"ok\":true}".getBytes(StandardCharsets.UTF_8));

        var outcome = buf.toOutcomeMap();
        BufferingPluginHttpResponse replay = BufferingPluginHttpResponse.fromOutcomeMap(outcome);

        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.contentType()).isEqualTo("application/json");
        assertThat(replay.headers()).containsEntry("X-Gamma-Probe", "echo");
        assertThat(new String(replay.body(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
    }
}
