package com.auraboot.framework.application.bootstrap.seeder;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Provisioning LLM provider apiKeys from environment variables.
 *
 * <p>The {@code readEnv} seam is overridden so the test controls which
 * {@code <PROVIDER>_API_KEY} env vars are "set" without touching the real
 * process environment.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudConfigSeeder — provision LLM apiKey from environment")
class CloudConfigSeederEnvProvisionTest {

    private static final String UPDATE_SQL =
            "UPDATE ab_cloud_config SET config = ?::jsonb, enabled = true WHERE pid = ?";
    private static final String SELECT_SQL =
            "SELECT config::text FROM ab_cloud_config WHERE pid = ?";
    private static final String DEEPSEEK_CONFIG =
            "{\"displayName\":\"DeepSeek\",\"apiFormat\":\"chat_completions\","
                    + "\"baseUrl\":\"https://api.deepseek.com\",\"defaultModel\":\"deepseek-chat\"}";

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private FieldEncryptionService fieldEncryptionService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, String> env = new HashMap<>();
    private CloudConfigSeeder seeder;

    @BeforeEach
    void setUp() {
        // Subclass overrides the env seam so tests control which keys are "set".
        seeder = new CloudConfigSeeder(jdbcTemplate, fieldEncryptionService, objectMapper) {
            @Override
            protected String readEnv(String name) {
                return env.get(name);
            }
        };
        // Passthrough encryption (no FIELD_ENCRYPTION_KEY in dev) — value unchanged.
        lenient().when(fieldEncryptionService.encrypt(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("env set → merges key into existing config + enables provider")
    void provisionsKeyWhenEnvSet() {
        env.put("DEEPSEEK_API_KEY", "sk-from-env-XYZ");
        when(jdbcTemplate.queryForList(eq(SELECT_SQL), eq(String.class), eq("seed_llm_deepseek")))
                .thenReturn(List.of(DEEPSEEK_CONFIG));

        seeder.provisionApiKeysFromEnv();

        ArgumentCaptor<String> configCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1))
                .update(eq(UPDATE_SQL), configCaptor.capture(), eq("seed_llm_deepseek"));
        assertTrue(configCaptor.getValue().contains("sk-from-env-XYZ"),
                "provisioned config must carry the env apiKey");
        assertTrue(configCaptor.getValue().contains("deepseek-chat"),
                "existing config fields must be preserved");
    }

    @Test
    @DisplayName("DASHSCOPE_API_KEY provisions the DashScope embedding provider, pinned to 1536 dimensions")
    void provisionsEmbeddingProviderFromEnv() {
        env.put("DASHSCOPE_API_KEY", "sk-dashscope-XYZ");
        // Neither the LLM nor the embedding row exists yet — both get created from their defaults.
        when(jdbcTemplate.queryForList(eq(SELECT_SQL), eq(String.class), anyString()))
                .thenReturn(List.of());

        seeder.provisionApiKeysFromEnv();

        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        verify(jdbcTemplate, times(2)).update(anyString(), args.capture(), args.capture(),
                args.capture(), args.capture(), args.capture(), args.capture(), args.capture());

        List<Object> all = args.getAllValues();
        String embeddingConfig = all.stream()
                .filter(a -> a instanceof String s && s.contains("text-embedding-v4"))
                .map(String.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "DASHSCOPE_API_KEY did not provision an embedding provider; captured: " + all));

        assertTrue(embeddingConfig.contains("sk-dashscope-XYZ"), "the env key must reach the config");
        // The load-bearing assertion. text-embedding-v4 answers with 1024 dimensions unless asked
        // otherwise, and ab_kb_chunk.embedding is vector(1536) — so a config that omits this (or
        // spells it "dimension", which EmbeddingService does not read) makes every chunk fail to
        // insert with "expected 1536 dimensions, not 1024".
        assertTrue(embeddingConfig.contains("\"dimensions\":1536"),
                "embedding config must pin dimensions=1536, got: " + embeddingConfig);
        assertTrue(all.contains("embedding"), "the row must be seeded with service_type=embedding");
    }

    @Test
    @DisplayName("env unset → no DB interaction at all (never clears a key)")
    void noOpWhenEnvUnset() {
        // env map empty → readEnv returns null for every provider
        seeder.provisionApiKeysFromEnv();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("env blank → treated as unset, no DB interaction")
    void noOpWhenEnvBlank() {
        env.put("DEEPSEEK_API_KEY", "   ");
        seeder.provisionApiKeysFromEnv();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("env set but provider row absent (deleted) → recreated from default config + key + enabled")
    void createsRowWhenAbsentButKnown() {
        env.put("DEEPSEEK_API_KEY", "sk-from-env-XYZ");
        when(jdbcTemplate.queryForList(eq(SELECT_SQL), eq(String.class), eq("seed_llm_deepseek")))
                .thenReturn(List.of()); // row was deleted (e.g. credential cleanup)

        seeder.provisionApiKeysFromEnv();

        // No UPDATE (nothing to update); instead an INSERT recreates the row from the
        // known default, carrying the env key + enabled=true + correct provider/priority.
        verify(jdbcTemplate, never()).update(eq(UPDATE_SQL), any(), any());
        ArgumentCaptor<String> configCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).update(anyString(),
                eq("seed_llm_deepseek"), eq("platform"), eq("llm"), eq("deepseek"),
                configCaptor.capture(), eq(true), eq(30));
        assertTrue(configCaptor.getValue().contains("sk-from-env-XYZ"),
                "recreated config must carry the env apiKey");
        assertTrue(configCaptor.getValue().contains("api.deepseek.com"),
                "recreated config must use the known default baseUrl");
    }

    @Test
    @DisplayName("unregistered env provider → skipped (no guessed config)")
    void skipsWhenEnvProviderIsNotRegistered() {
        // MINIMAX_API_KEY is not in LLM_PROVIDER_ENV_KEYS. Setting an arbitrary
        // env var must not make the seeder guess provider rows or config shape.
        env.put("MINIMAX_API_KEY", "sk-minimax");

        seeder.provisionApiKeysFromEnv();

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("multiple env vars set → each matching provider provisioned independently")
    void provisionsMultipleProviders() {
        env.put("DEEPSEEK_API_KEY", "sk-deepseek");
        env.put("OPENAI_API_KEY", "sk-openai");
        when(jdbcTemplate.queryForList(eq(SELECT_SQL), eq(String.class), eq("seed_llm_deepseek")))
                .thenReturn(List.of(DEEPSEEK_CONFIG));
        when(jdbcTemplate.queryForList(eq(SELECT_SQL), eq(String.class), eq("seed_llm_openai")))
                .thenReturn(List.of("{\"displayName\":\"OpenAI\",\"baseUrl\":\"https://api.openai.com\"}"));

        seeder.provisionApiKeysFromEnv();

        verify(jdbcTemplate, times(1)).update(eq(UPDATE_SQL), anyString(), eq("seed_llm_deepseek"));
        verify(jdbcTemplate, times(1)).update(eq(UPDATE_SQL), anyString(), eq("seed_llm_openai"));
    }

    @Test
    @DisplayName("seed() still skips defaults when already seeded, then provisions from env")
    void seedSkipsExistingThenProvisions() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(7);
        env.put("DEEPSEEK_API_KEY", "sk-from-env");
        when(jdbcTemplate.queryForList(eq(SELECT_SQL), eq(String.class), eq("seed_llm_deepseek")))
                .thenReturn(List.of(DEEPSEEK_CONFIG));

        seeder.seed();

        // Defaults were already seeded (count=7) so the else-branch INSERT is skipped,
        // but env provisioning still runs unconditionally afterwards.
        verify(jdbcTemplate, times(1))
                .update(eq(UPDATE_SQL), anyString(), eq("seed_llm_deepseek"));
        assertEquals("sk-from-env", env.get("DEEPSEEK_API_KEY"));
    }
}
