package com.auraboot.framework.branding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthAppearanceValidatorTest {
    @Test
    void acceptsAndRejectsSharedCrossLanguageCorpus() throws Exception {
        try (InputStream input = getClass().getResourceAsStream(
                "/branding/auth-appearance.corpus.json")) {
            assertThat(input).isNotNull();
            JsonNode corpus = new ObjectMapper().readTree(input);
            assertThat(corpus.size()).isGreaterThan(25);
            for (JsonNode fixture : corpus) {
                if (fixture.get("valid").asBoolean()) {
                    assertThatCode(() -> AuthAppearanceValidator.validate(fixture.get("value")))
                            .as(fixture.get("name").asText()).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> AuthAppearanceValidator.validate(fixture.get("value")))
                            .as(fixture.get("name").asText()).isInstanceOf(IllegalArgumentException.class);
                }
            }
        }
    }
}
