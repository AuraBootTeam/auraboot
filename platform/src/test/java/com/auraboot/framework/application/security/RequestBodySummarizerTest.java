package com.auraboot.framework.application.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class RequestBodySummarizerTest {

    private final RequestBodySummarizer summarizer = new RequestBodySummarizer(new ObjectMapper());

    @Test
    void summarize_returnsNull_forGetRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/admin/users");
        assertThat(summarizer.summarize(req)).isNull();
    }

    @Test
    void summarize_returnsNull_forDeleteRequest() {
        MockHttpServletRequest req = new MockHttpServletRequest("DELETE", "/api/admin/users/1");
        assertThat(summarizer.summarize(req)).isNull();
    }

    @Test
    void summarize_returnsNull_forNonCachingWrapper() {
        // Plain MockHttpServletRequest is NOT a ContentCachingRequestWrapper
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/admin/users");
        req.setContent("{\"x\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(summarizer.summarize(req)).isNull();
    }

    @Test
    void summarize_returnsTopLevelKeys_forPostRequest() throws Exception {
        MockHttpServletRequest mock = new MockHttpServletRequest("POST", "/api/admin/users");
        mock.setContent("{\"userId\":\"x\",\"password\":\"secret\",\"nested\":{\"inner\":1}}"
            .getBytes(StandardCharsets.UTF_8));
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(mock);
        wrapped.getInputStream().readAllBytes(); // populate cache

        String summary = summarizer.summarize(wrapped);

        assertThat(summary).contains("userId", "password", "nested");
        assertThat(summary).doesNotContain("secret");
        assertThat(summary).doesNotContain("inner");
    }

    @Test
    void summarize_returnsParseErrorMarker_forInvalidJson() throws Exception {
        MockHttpServletRequest mock = new MockHttpServletRequest("POST", "/api/admin/users");
        mock.setContent("not-json".getBytes(StandardCharsets.UTF_8));
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(mock);
        wrapped.getInputStream().readAllBytes();

        assertThat(summarizer.summarize(wrapped)).isEqualTo("{\"parse_error\":true}");
    }

    @Test
    void summarize_returnsParseErrorMarker_forJsonArray() throws Exception {
        // Root that's not an object
        MockHttpServletRequest mock = new MockHttpServletRequest("POST", "/api/admin/users");
        mock.setContent("[1,2,3]".getBytes(StandardCharsets.UTF_8));
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(mock);
        wrapped.getInputStream().readAllBytes();

        assertThat(summarizer.summarize(wrapped)).isEqualTo("{\"parse_error\":true}");
    }

    @Test
    void summarize_truncates_at2048Chars() throws Exception {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 500; i++) {
            sb.append("\"key").append(i).append("\":1");
            if (i < 499) sb.append(",");
        }
        sb.append("}");

        MockHttpServletRequest mock = new MockHttpServletRequest("POST", "/api/admin/users");
        mock.setContent(sb.toString().getBytes(StandardCharsets.UTF_8));
        ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(mock);
        wrapped.getInputStream().readAllBytes();

        String summary = summarizer.summarize(wrapped);
        assertThat(summary.length()).isLessThanOrEqualTo(2048);
    }
}
