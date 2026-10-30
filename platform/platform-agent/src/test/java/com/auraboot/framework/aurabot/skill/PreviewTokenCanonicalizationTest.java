package com.auraboot.framework.aurabot.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PreviewTokenCanonicalizationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PreviewTokenStore store = new PreviewTokenStore(mock(StringRedisTemplate.class), mapper);

    @Test
    void objectOrderingDoesNotChangePreviewIdentityAcrossNestedObjectsAndArrays() throws Exception {
        var original = mapper.readTree("{\"displayName\":\"审批台账\",\"code\":\"approve_log\",\"fields\":[{\"type\":\"TEXT\",\"code\":\"name\"}]}");
        var reordered = mapper.readTree("{\"fields\":[{\"code\":\"name\",\"type\":\"TEXT\"}],\"code\":\"approve_log\",\"displayName\":\"审批台账\"}");
        assertThat(store.hashParams(original)).isEqualTo(store.hashParams(reordered));
    }

    @Test
    void changingValuesOrArrayOrderCannotReuseThePreviewIdentity() throws Exception {
        assertThat(store.hashParams(mapper.readTree("{\"items\":[1,2]}")))
                .isNotEqualTo(store.hashParams(mapper.readTree("{\"items\":[2,1]}")))
                .isNotEqualTo(store.hashParams(mapper.readTree("{\"items\":[1,3]}")));
    }
}
