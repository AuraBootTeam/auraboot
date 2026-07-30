package com.auraboot.framework.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Provider-neutral live LLM profile")
class LiveLlmSeederTest {

    @Test
    void resolvesOnlyTheExplicitProfileAndIndirectKeyVariable() {
        Map<String, String> environment = new HashMap<>();
        environment.put("AURA_LIVE_LLM_PROVIDER", "provider-under-test");
        environment.put("AURA_LIVE_LLM_MODEL", "model-under-test");
        environment.put("AURA_LIVE_LLM_API_KEY_ENV", "TEST_LIVE_KEY");
        environment.put("AURA_LIVE_LLM_BASE_URL", "https://llm.example.test/v1");
        environment.put("TEST_LIVE_KEY", "secret-value");
        environment.put("UNRELATED_PROVIDER_KEY", "must-not-be-selected");

        LiveLlmSeeder.LiveProvider profile = LiveLlmSeeder.resolve(environment);

        assertThat(profile).isNotNull();
        assertThat(profile.providerCode()).isEqualTo("provider-under-test");
        assertThat(profile.model()).isEqualTo("model-under-test");
        assertThat(profile.apiKey()).isEqualTo("secret-value");
        assertThat(profile.apiKeyEnvironmentVariable()).isEqualTo("TEST_LIVE_KEY");
    }

    @Test
    void neverInfersAProviderFromAnUnrelatedCredential() {
        LiveLlmSeeder.LiveProvider profile = LiveLlmSeeder.resolve(
                Map.of("UNRELATED_PROVIDER_KEY", "secret-value"));

        assertThat(profile).isNull();
    }

    @Test
    void rejectsInvalidOrUnsetIndirectKeyVariables() {
        assertThat(LiveLlmSeeder.resolve(Map.of(
                "AURA_LIVE_LLM_PROVIDER", "provider-under-test",
                "AURA_LIVE_LLM_MODEL", "model-under-test",
                "AURA_LIVE_LLM_API_KEY_ENV", "not a valid env name")))
                .isNull();
        assertThat(LiveLlmSeeder.resolve(Map.of(
                "AURA_LIVE_LLM_PROVIDER", "provider-under-test",
                "AURA_LIVE_LLM_MODEL", "model-under-test",
                "AURA_LIVE_LLM_API_KEY_ENV", "MISSING_KEY")))
                .isNull();
    }

    @Test
    void diagnosticStringNeverContainsTheCredential() {
        LiveLlmSeeder.LiveProvider profile = new LiveLlmSeeder.LiveProvider(
                "provider-under-test",
                "secret-value",
                "TEST_LIVE_KEY",
                "https://llm.example.test/v1",
                "model-under-test");

        assertThat(profile.toString())
                .contains("TEST_LIVE_KEY")
                .doesNotContain("secret-value");
    }
}
