package com.auraboot.framework.connector.saas.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaasHttpResponseTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void headerLookupIsCaseInsensitive() {
        SaasHttpResponse r = new SaasHttpResponse(200,
                Map.of("Content-Type", List.of("application/json")),
                new byte[0]);
        assertThat(r.header("content-type")).isEqualTo("application/json");
        assertThat(r.header("CONTENT-TYPE")).isEqualTo("application/json");
        assertThat(r.header("missing")).isNull();
    }

    @Test
    void retryAfterParses() {
        SaasHttpResponse r = new SaasHttpResponse(429,
                Map.of("Retry-After", List.of("12")), new byte[0]);
        assertThat(r.retryAfterSeconds()).contains(12);
    }

    @Test
    void retryAfterMissingOrInvalidEmpty() {
        SaasHttpResponse r = new SaasHttpResponse(429, Map.of(), new byte[0]);
        assertThat(r.retryAfterSeconds()).isEmpty();
        SaasHttpResponse bad = new SaasHttpResponse(429,
                Map.of("Retry-After", List.of("not-a-number")), new byte[0]);
        assertThat(bad.retryAfterSeconds()).isEmpty();
    }

    @Test
    void retryableIs429OrFiveXx() {
        assertThat(new SaasHttpResponse(429, Map.of(), new byte[0]).isRetryable()).isTrue();
        assertThat(new SaasHttpResponse(500, Map.of(), new byte[0]).isRetryable()).isTrue();
        assertThat(new SaasHttpResponse(503, Map.of(), new byte[0]).isRetryable()).isTrue();
        assertThat(new SaasHttpResponse(599, Map.of(), new byte[0]).isRetryable()).isTrue();
        assertThat(new SaasHttpResponse(200, Map.of(), new byte[0]).isRetryable()).isFalse();
        assertThat(new SaasHttpResponse(404, Map.of(), new byte[0]).isRetryable()).isFalse();
    }

    @Test
    void jsonParseSurfacesPreviewOnFailure() {
        SaasHttpResponse r = new SaasHttpResponse(200, Map.of(),
                "not valid json at all".getBytes());
        assertThatThrownBy(() -> r.json(json))
                .isInstanceOf(SaasHttpException.class)
                .hasMessageContaining("preview")
                .hasMessageContaining("not valid json");
    }

    @Test
    void emptyBodyJsonReturnsNullNode() {
        SaasHttpResponse r = new SaasHttpResponse(204, Map.of(), new byte[0]);
        assertThat(r.json(json).isNull()).isTrue();
    }
}
