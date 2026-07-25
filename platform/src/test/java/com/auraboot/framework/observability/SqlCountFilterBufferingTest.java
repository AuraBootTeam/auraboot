package com.auraboot.framework.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the property that matters about this filter: it must not hold the response
 * body in memory. The previous implementation wrapped every response in a
 * {@link ContentCachingResponseWrapper}, which made an arbitrarily large file download
 * an arbitrarily large heap allocation and could swallow a streaming response outright.
 */
class SqlCountFilterBufferingTest {

    private SqlCountFilter filter() {
        return new SqlCountFilter(new SimpleMeterRegistry(), 50, 100, true);
    }

    @Test
    void doesNotWrapTheResponseInABufferingWrapper() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/things");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<HttpServletResponse> seen = new AtomicReference<>();

        filter().doFilter(request, response, (req, res) -> seen.set((HttpServletResponse) res));

        assertNotNull(seen.get());
        assertFalse(seen.get() instanceof ContentCachingResponseWrapper,
                "buffering the body is what made large downloads a heap risk — the filter "
                        + "must never hand a ContentCachingResponseWrapper down the chain");
    }

    /**
     * The regression that catches re-introduced buffering: bytes written by the handler
     * must be visible on the real response immediately, not only after the filter copies
     * a buffer out on the way back.
     */
    @Test
    void bodyReachesTheRealResponseWhileTheChainIsStillRunning() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/things");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> visibleMidChain = new AtomicReference<>();

        filter().doFilter(request, response, (req, res) -> {
            ((HttpServletResponse) res).getOutputStream().write("streamed".getBytes());
            ((HttpServletResponse) res).getOutputStream().flush();
            visibleMidChain.set(response.getContentAsString());
        });

        assertEquals("streamed", visibleMidChain.get(),
                "the body must pass straight through; if this is empty the filter is "
                        + "buffering again and a streaming response would be swallowed");
    }

    @Test
    void stampsTheSqlCountHeaderWhenTheBodyIsAcquired() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/things");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, (req, res) -> {
            SqlCountHolder.increment();
            SqlCountHolder.increment();
            SqlCountHolder.increment();
            ((HttpServletResponse) res).getOutputStream().write("{}".getBytes());
        });

        assertEquals("3", response.getHeader("X-SQL-Count"),
                "the header has to reflect the SQL the handler actually ran");
    }

    @Test
    void stampsTheHeaderOnABodylessResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/things/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, (req, res) -> {
            SqlCountHolder.increment();
            ((HttpServletResponse) res).setStatus(204);
        });

        assertEquals("1", response.getHeader("X-SQL-Count"),
                "a 204 never asks for the output stream, so it must be stamped on the way out");
    }

    @Test
    void writerBasedResponsesAreStampedToo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/things");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, (req, res) -> {
            SqlCountHolder.increment();
            ((HttpServletResponse) res).getWriter().write("hello");
        });

        assertEquals("1", response.getHeader("X-SQL-Count"));
    }

    @Test
    void headerIsOmittedWhenDisabled() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/things");
        MockHttpServletResponse response = new MockHttpServletResponse();
        SqlCountFilter disabled = new SqlCountFilter(new SimpleMeterRegistry(), 50, 100, false);

        disabled.doFilter(request, response, (req, res) -> {
            SqlCountHolder.increment();
            ((HttpServletResponse) res).getOutputStream().write("{}".getBytes());
        });

        assertTrue(response.getHeader("X-SQL-Count") == null);
    }

    /**
     * An SSE response used to require the filter to guess correctly up front; with no
     * buffering there is nothing to guess.
     *
     * <p>Asserted <em>during</em> the stream, not after it. Checking the body once the
     * filter has returned proves nothing: a buffering implementation copies the buffer
     * out on the way back, so the bytes are there by then either way — that version of
     * this test stayed green under the buffering mutant. What SSE actually needs is for
     * each event to be visible as it is flushed.
     */
    @Test
    void serverSentEventsAreVisibleAsTheyAreFlushed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/stream");
        request.addHeader("Accept", "text/event-stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> afterFirstEvent = new AtomicReference<>();

        filter().doFilter(request, response, (req, res) -> {
            HttpServletResponse r = (HttpServletResponse) res;
            r.setContentType("text/event-stream");
            r.getOutputStream().write("data: one\n\n".getBytes());
            r.getOutputStream().flush();
            afterFirstEvent.set(response.getContentAsString());
            r.getOutputStream().write("data: two\n\n".getBytes());
            r.getOutputStream().flush();
        });

        assertEquals("data: one\n\n", afterFirstEvent.get(),
                "the first event must have reached the client before the second was written; "
                        + "buffering an SSE response produced an empty 200 with the events "
                        + "stuck in a buffer nobody flushed");
        assertEquals("data: one\n\ndata: two\n\n", response.getContentAsString());
    }
}
