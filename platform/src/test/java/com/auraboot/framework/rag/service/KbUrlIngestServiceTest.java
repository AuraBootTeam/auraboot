package com.auraboot.framework.rag.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * URL ingestion is an SSRF sink: it makes the backend fetch a URL that any user holding
 * AI_KNOWLEDGE_MANAGE typed in, and then puts the response where that user can read it back.
 *
 * <p>Every case here asserts the request is refused <b>before a socket is opened</b> — the ingest
 * collaborator is a mock, and each test verifies it was never called. A test that only checked the
 * thrown exception would still pass if the fetch had already happened.
 *
 * <p>The loopback cases are not hypothetical: this is why the golden stack has to opt 127.0.0.1
 * into an explicit allowlist before it can fetch its own fixture page.
 */
@DisplayName("KbUrlIngestService — SSRF")
class KbUrlIngestServiceTest {

    private final KbTextIngestService textIngest = Mockito.mock(KbTextIngestService.class);
    private final KbUrlIngestService service =
            new KbUrlIngestService(new DocumentParserService(), textIngest);

    private void assertRefused(String url, String expectedReason) {
        assertThatThrownBy(() -> service.ingestUrl(1L, "kb-1", url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(expectedReason);

        verify(textIngest, never()).ingestText(anyLong(), anyString(), anyString(), anyString(),
                any(), any());
    }

    @Test
    @DisplayName("cloud metadata endpoint is refused")
    void refusesCloudMetadata() {
        // The canonical SSRF payload: 169.254.169.254 hands out instance credentials on AWS/GCP/
        // Azure. Reaching it and indexing the response would put those credentials in a knowledge
        // base that the requesting user can then simply search.
        assertRefused("http://169.254.169.254/latest/meta-data/", "link-local");
    }

    @Test
    @DisplayName("loopback is refused")
    void refusesLoopback() {
        assertRefused("http://127.0.0.1/admin", "loopback");
    }

    @Test
    @DisplayName("localhost by name is refused")
    void refusesLocalhostByName() {
        assertRefused("http://localhost/admin", "loopback");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://10.0.0.1/internal",       // RFC 1918
            "http://192.168.1.1/router",      // RFC 1918
            "http://172.16.0.1/service",      // RFC 1918
    })
    @DisplayName("private network addresses are refused")
    void refusesPrivateNetworks(String url) {
        assertRefused(url, "private");
    }

    @Test
    @DisplayName("a blocked management port is refused even on a public host")
    void refusesBlockedPort() {
        assertRefused("http://example.com:5432/", "port not allowed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "gopher://example.com/",
            "ftp://example.com/x",
    })
    @DisplayName("non-HTTP schemes are refused")
    void refusesNonHttpSchemes(String url) {
        assertRefused(url, "scheme not allowed");
    }

    @Test
    @DisplayName("an empty URL is refused")
    void refusesEmptyUrl() {
        assertRefused("", "must not be empty");
    }

    @Test
    @DisplayName("a host that does not resolve is refused (nothing to fetch)")
    void refusesUnresolvableHost() {
        assertRefused("http://this-host-does-not-exist.invalid/page", "could not be resolved");
    }

    @Test
    @DisplayName("MAX_CONTENT_BYTES is a real bound, not a placeholder")
    void hasAContentSizeBound() {
        assertThat(KbUrlIngestService.MAX_CONTENT_BYTES).isBetween(1, 50 * 1024 * 1024);
    }
}
