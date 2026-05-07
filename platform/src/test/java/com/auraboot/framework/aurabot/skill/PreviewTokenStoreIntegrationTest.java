package com.auraboot.framework.aurabot.skill;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Redis IT for {@link PreviewTokenStore} (Plan Step 5 / SPI §6 §11).
 *
 * <p>Verifies the three branches the validator (B4) maps to HTTP 422
 * {@code PREVIEW_TOKEN_INVALID}: token-missing (consumed twice), skill
 * mismatch, params hash mismatch.
 */
@ActiveProfiles({"integration-test", "skills-c2-test"})
@DisplayName("PreviewTokenStore — Redis one-shot handoff (real Redis on :26389)")
class PreviewTokenStoreIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PreviewTokenStore store;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanupPreviewKeys() {
        Set<String> keys = redisTemplate.keys(PreviewTokenStore.KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private String freshSkill() {
        return "it-aurabot-preview-" + UniqueIdGenerator.generate().toLowerCase();
    }

    private ObjectNode params(String text, int n) {
        ObjectNode p = objectMapper.createObjectNode();
        p.put("text", text);
        p.put("n", n);
        return p;
    }

    private ObjectNode samplePayload() {
        ObjectNode p = objectMapper.createObjectNode();
        p.put("summary", "it-aurabot-preview-summary");
        p.put("itemCount", 3);
        return p;
    }

    @Test
    @DisplayName("save → consume — one-shot success, second consume returns empty")
    void save_consume_oneShotSuccess() {
        long tenant = 9001L;
        String skill = freshSkill();
        ObjectNode p = params("hello", 1);
        ObjectNode payload = samplePayload();

        String token = store.save(tenant, skill, p, payload);
        assertThat(token).isNotBlank();

        Optional<PreviewTokenStore.PreviewPayload> first = store.consume(token, skill, p);
        assertThat(first).isPresent();
        PreviewTokenStore.PreviewPayload pp = first.get();
        assertThat(pp.tenantId()).isEqualTo(tenant);
        assertThat(pp.skillName()).isEqualTo(skill);
        JsonNode roundTripped = pp.payload();
        assertThat(roundTripped.path("summary").asText()).isEqualTo("it-aurabot-preview-summary");
        assertThat(roundTripped.path("itemCount").asInt()).isEqualTo(3);

        // Second consume must miss — token already deleted.
        Optional<PreviewTokenStore.PreviewPayload> second = store.consume(token, skill, p);
        assertThat(second).as("token is one-shot").isEmpty();
        assertThat(redisTemplate.opsForValue().get(PreviewTokenStore.buildKey(token))).isNull();
    }

    @Test
    @DisplayName("consume — params mismatch returns empty (and burns the token)")
    void consume_paramsMismatch_returnsEmpty() {
        long tenant = 9001L;
        String skill = freshSkill();
        ObjectNode original = params("hello", 1);
        ObjectNode tampered = params("hello", 2); // different value → different hash

        String token = store.save(tenant, skill, original, samplePayload());

        Optional<PreviewTokenStore.PreviewPayload> result = store.consume(token, skill, tampered);
        assertThat(result).as("paramsHash mismatch must produce empty").isEmpty();

        // One-shot semantics: even a mismatched consume burns the token.
        assertThat(redisTemplate.opsForValue().get(PreviewTokenStore.buildKey(token)))
                .as("mismatched consume still deletes the token")
                .isNull();

        // Sanity: re-saving with the matching params on a fresh token still
        // succeeds — we haven't poisoned the store.
        String token2 = store.save(tenant, skill, original, samplePayload());
        assertThat(store.consume(token2, skill, original)).isPresent();
    }

    @Test
    @DisplayName("consume — skill mismatch returns empty")
    void consume_skillMismatch_returnsEmpty() {
        long tenant = 9001L;
        String savedSkill = freshSkill();
        String otherSkill = freshSkill();
        ObjectNode p = params("hello", 1);

        String token = store.save(tenant, savedSkill, p, samplePayload());

        Optional<PreviewTokenStore.PreviewPayload> result = store.consume(token, otherSkill, p);
        assertThat(result).as("skill mismatch must produce empty").isEmpty();

        // Token already deleted by the mismatched consume.
        assertThat(redisTemplate.opsForValue().get(PreviewTokenStore.buildKey(token))).isNull();
    }
}
