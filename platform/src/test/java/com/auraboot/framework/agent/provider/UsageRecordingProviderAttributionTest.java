package com.auraboot.framework.agent.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.trace.GenAiUsageRecorder;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.auraboot.framework.application.tenant.MetaContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The usage ledger has a provider column and nothing wrote it, so every row said
 * only which model was asked for. A deployment with more than one vendor could
 * not answer "how much is going to whom", and a live run could not prove after
 * the fact which vendor actually served it — which matters, because with two
 * keys present the resolution order decides silently.
 */
@DisplayName("UsageRecordingLlmProvider — vendor attribution")
class UsageRecordingProviderAttributionTest {

    @Test
    @DisplayName("the serving vendor is recorded alongside the model")
    void recordsProviderCodeOfTheDelegate() throws Exception {
        LlmProvider delegate = mock(LlmProvider.class);
        GenAiUsageRecorder recorder = mock(GenAiUsageRecorder.class);
        Tracer tracer = mock(Tracer.class);

        // Production shape, and the reason the first version of this fix was wrong:
        // Qwen is served by the OpenAI-compatible adapter, whose getProviderCode()
        // answers "openai". Attributing by the delegate therefore names the wrong
        // vendor on every Qwen/Zhipu/Moonshot call. A mock that answers "qianwen"
        // here would let this test pass for a reason that does not exist in
        // production — verified against a real stack, where the ledger said openai.
        when(delegate.getProviderCode()).thenReturn("openai");
        LlmChatResponse response = new LlmChatResponse();
        response.setInputTokens(11);
        response.setOutputTokens(7);
        when(delegate.chat(any(), anyString(), anyString())).thenReturn(response);

        LlmChatRequest request = LlmChatRequest.builder().model("qwen-plus").build();
        MetaContext.setSystemTenantContext(7L);
        try {
            new UsageRecordingLlmProvider(delegate, recorder, tracer, "qianwen")
                    .chat(request, "k", "https://example.invalid");
        } finally {
            MetaContext.clear();
        }

        ArgumentCaptor<String> provider = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> model = ArgumentCaptor.forClass(String.class);
        verify(recorder).record(any(), any(), provider.capture(), model.capture(),
                eq(11), eq(7), any(), any(), any());

        assertThat(provider.getValue())
                .as("the configured vendor must win over the adapter family that serves it")
                .isEqualTo("qianwen");
        assertThat(model.getValue()).isEqualTo("qwen-plus");
    }
}
