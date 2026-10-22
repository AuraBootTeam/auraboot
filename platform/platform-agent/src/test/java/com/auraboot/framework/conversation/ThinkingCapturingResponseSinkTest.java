package com.auraboot.framework.conversation;

import com.auraboot.framework.agent.dto.ResultContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ThinkingCapturingResponseSink}.
 *
 * <p>Locks the D.1 capture contract (design v3.3 §3.4):
 * <ul>
 *   <li>Empty turn (no {@code onThinking} call) → {@code capturedContent} /
 *       {@code capturedSignature} both null (no empty-string poison).</li>
 *   <li>Single block → exact prose returned, signature mirrors block.</li>
 *   <li>Multiple blocks → joined by {@code "\n\n"}; signature uses LAST
 *       non-null value (Anthropic verification token is per-block, only the
 *       trailing one matters for replay).</li>
 *   <li>Null / empty content + signature inputs are filtered out before they
 *       reach the captured buffers.</li>
 *   <li>All other ResponseSink methods are pure pass-throughs — verified by
 *       delegating to a Mockito mock and asserting the same args propagate.</li>
 *   <li>{@code isClientConnected} reflects the delegate.</li>
 * </ul>
 */
@DisplayName("ThinkingCapturingResponseSink — D.1 Extended-Thinking capture")
class ThinkingCapturingResponseSinkTest {

    @Test
    @DisplayName("empty turn returns null content and signature")
    void empty_returnsNulls() {
        ResponseSink delegate = mock(ResponseSink.class);
        ThinkingCapturingResponseSink sink = new ThinkingCapturingResponseSink(delegate);

        assertThat(sink.capturedContent()).isNull();
        assertThat(sink.capturedSignature()).isNull();
        assertThat(sink.delegate()).isSameAs(delegate);
    }

    @Test
    @DisplayName("single thinking block — captures content + signature, forwards to delegate")
    void singleBlock_captured() {
        ResponseSink delegate = mock(ResponseSink.class);
        ThinkingCapturingResponseSink sink = new ThinkingCapturingResponseSink(delegate);

        sink.onThinking("Reasoning step 1", 42, "sig-A");

        assertThat(sink.capturedContent()).isEqualTo("Reasoning step 1");
        assertThat(sink.capturedSignature()).isEqualTo("sig-A");
        verify(delegate).onThinking("Reasoning step 1", 42, "sig-A");
    }

    @Test
    @DisplayName("multiple blocks — content joined by \\n\\n, last non-null signature wins")
    void multipleBlocks_joined() {
        ResponseSink delegate = mock(ResponseSink.class);
        ThinkingCapturingResponseSink sink = new ThinkingCapturingResponseSink(delegate);

        sink.onThinking("Step 1", 10, "sig-1");
        sink.onThinking("Step 2", 20, "sig-2");
        sink.onThinking("Step 3", 30, null);

        assertThat(sink.capturedContent()).isEqualTo("Step 1\n\nStep 2\n\nStep 3");
        assertThat(sink.capturedSignature()).isEqualTo("sig-2");
    }

    @Test
    @DisplayName("null and empty content/signature ignored but still forwarded")
    void nullEmptyValues_filtered() {
        ResponseSink delegate = mock(ResponseSink.class);
        ThinkingCapturingResponseSink sink = new ThinkingCapturingResponseSink(delegate);

        sink.onThinking(null, -1, null);
        sink.onThinking("", -1, "");
        // Captures still empty
        assertThat(sink.capturedContent()).isNull();
        assertThat(sink.capturedSignature()).isNull();

        // Add real value to confirm later capture still works
        sink.onThinking("real", 1, "real-sig");
        assertThat(sink.capturedContent()).isEqualTo("real");
        assertThat(sink.capturedSignature()).isEqualTo("real-sig");

        verify(delegate).onThinking(null, -1, null);
        verify(delegate).onThinking("", -1, "");
        verify(delegate).onThinking("real", 1, "real-sig");
    }

    @Test
    @DisplayName("all other events pass through to delegate unchanged")
    void otherEvents_passThrough() {
        ResponseSink delegate = mock(ResponseSink.class);
        ThinkingCapturingResponseSink sink = new ThinkingCapturingResponseSink(delegate);

        sink.onTextChunk("hello");
        sink.onToolStart("tid", "tname", Map.of("k", "v"));
        sink.onToolResult("tid", Map.of("r", 1), true);
        sink.onConfirmRequired("tid", "tname", "desc", Map.of("a", 1), "pending-turn");
        sink.onError("oops", "trace-1");
        sink.onDone("final", "trace-2");

        ResultContract contract = mock(ResultContract.class);
        sink.onResultContract(contract);

        verify(delegate).onTextChunk("hello");
        verify(delegate).onToolStart(eq("tid"), eq("tname"), anyMap());
        verify(delegate).onToolResult(eq("tid"), anyMap(), eq(true));
        verify(delegate).onConfirmRequired("tid", "tname", "desc", Map.of("a", 1), "pending-turn");
        verify(delegate).onError("oops", "trace-1");
        verify(delegate).onDone("final", "trace-2");
        verify(delegate).onResultContract(contract);
    }

    @Test
    @DisplayName("isClientConnected reflects the delegate's view")
    void isClientConnected_delegated() {
        ResponseSink delegate = mock(ResponseSink.class);
        when(delegate.isClientConnected()).thenReturn(false);
        ThinkingCapturingResponseSink sink = new ThinkingCapturingResponseSink(delegate);
        assertThat(sink.isClientConnected()).isFalse();
    }

    @Test
    @DisplayName("blank-only contents stay null after join")
    void joinedContents_blankOnly() {
        ResponseSink delegate = mock(ResponseSink.class);
        ThinkingCapturingResponseSink sink = new ThinkingCapturingResponseSink(delegate);

        // Force the contents list to receive only whitespace via reflection-free path:
        // we cannot bypass the empty-string filter, so simulate via direct empty check.
        sink.onThinking("", -1, null); // filtered
        assertThat(sink.capturedContent()).isNull();
    }
}
