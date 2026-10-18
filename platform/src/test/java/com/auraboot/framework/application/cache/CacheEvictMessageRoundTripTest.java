package com.auraboot.framework.application.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CacheEvictMessageRoundTripTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keyedMessageSurvivesJsonRoundTrip() throws Exception {
        CacheEvictMessage message = new CacheEvictMessage("dictData", "gender", false);

        CacheEvictMessage parsed = objectMapper.readValue(
            objectMapper.writeValueAsString(message), CacheEvictMessage.class);

        assertThat(parsed).isEqualTo(message);
    }

    @Test
    void regionWideMessageSurvivesJsonRoundTrip() throws Exception {
        CacheEvictMessage message = new CacheEvictMessage("user-permissions", null, true);

        CacheEvictMessage parsed = objectMapper.readValue(
            objectMapper.writeValueAsString(message), CacheEvictMessage.class);

        assertThat(parsed).isEqualTo(message);
        assertThat(parsed.all()).isTrue();
    }
}
