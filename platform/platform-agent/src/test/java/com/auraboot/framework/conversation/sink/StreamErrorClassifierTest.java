package com.auraboot.framework.conversation.sink;

import com.auraboot.framework.im.model.ImConstants;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StreamErrorClassifierTest {

    @Test
    void safetyRefusalKeyword() {
        assertThat(StreamErrorClassifier.classify("Anthropic safety policy refusal: prompt blocked", null))
                .isEqualTo(ImConstants.AI_ERR_SAFETY_REFUSAL);
        assertThat(StreamErrorClassifier.classify("Content policy violation", null))
                .isEqualTo(ImConstants.AI_ERR_SAFETY_REFUSAL);
    }

    @Test
    void rateLimited() {
        assertThat(StreamErrorClassifier.classify("HTTP 429 rate limit exceeded", null))
                .isEqualTo(ImConstants.AI_ERR_RATE_LIMITED);
        assertThat(StreamErrorClassifier.classify("Too many requests", null))
                .isEqualTo(ImConstants.AI_ERR_RATE_LIMITED);
    }

    @Test
    void upstreamTimeout() {
        assertThat(StreamErrorClassifier.classify("Upstream timed out after 60s", null))
                .isEqualTo(ImConstants.AI_ERR_UPSTREAM_TIMEOUT);
        assertThat(StreamErrorClassifier.classify("Read timeout", null))
                .isEqualTo(ImConstants.AI_ERR_UPSTREAM_TIMEOUT);
    }

    @Test
    void internalErrorFallback() {
        assertThat(StreamErrorClassifier.classify("NullPointerException at line 42", null))
                .isEqualTo(ImConstants.AI_ERR_INTERNAL);
        assertThat(StreamErrorClassifier.classify(null, null))
                .isEqualTo(ImConstants.AI_ERR_INTERNAL);
        assertThat(StreamErrorClassifier.classify("", null))
                .isEqualTo(ImConstants.AI_ERR_INTERNAL);
    }
}
