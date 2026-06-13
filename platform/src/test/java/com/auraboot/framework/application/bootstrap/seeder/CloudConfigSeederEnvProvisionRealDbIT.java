package com.auraboot.framework.application.bootstrap.seeder;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-DB proof for env-based LLM apiKey provisioning. Runs only when
 * {@code ENVKEY_IT_DB} is set (a JDBC URL to an isolated Postgres DB that
 * already has {@code ab_cloud_config} + the {@code seed_llm_deepseek} row),
 * so it never runs in the default test/CI sweep (which has no DB).
 *
 * <p>Exercises the actual {@link CloudConfigSeeder#provisionLlmApiKeysFromEnv()}
 * against a live Postgres connection — proving the {@code SET config = ?::jsonb}
 * UPDATE applies on the real jsonb column and the row flips to {@code enabled=true}
 * carrying the supplied key. Uses a dummy key (never a real secret) and leaves
 * the row provisioned for inspection.
 *
 * <p>Invoke with:
 * <pre>
 *   ENVKEY_IT_DB="jdbc:postgresql://localhost:5432/aura_boot_envkeyverify" \
 *   ENVKEY_IT_USER=ghj \
 *   ./gradlew :test --tests '*CloudConfigSeederEnvProvisionRealDbIT'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "ENVKEY_IT_DB", matches = ".+")
@DisplayName("CloudConfigSeeder — env apiKey provisioning against real Postgres")
class CloudConfigSeederEnvProvisionRealDbIT {

    private static final String DUMMY_KEY = "sk-envprovision-realdb-verify-DUMMY";

    @Test
    @DisplayName("provision recreates a deleted seed_llm_deepseek row from env key on a real jsonb column")
    void provisionsAgainstRealDb() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(System.getenv("ENVKEY_IT_DB"));
        ds.setUsername(envOr("ENVKEY_IT_USER", "ghj"));
        ds.setPassword(envOr("ENVKEY_IT_PASSWORD", ""));
        ds.setDriverClassName("org.postgresql.Driver");
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // Reproduce the live auraqr scenario: the deepseek row was deleted during a
        // credential cleanup, so provisioning must RECREATE it from the env key alone.
        jdbc.update("DELETE FROM ab_cloud_config WHERE pid='seed_llm_deepseek'");
        Integer before = jdbc.queryForObject(
                "SELECT count(*) FROM ab_cloud_config WHERE pid='seed_llm_deepseek'", Integer.class);
        assertEquals(0, before, "precondition: deepseek row absent");

        // Seeder with env seam returning a dummy DEEPSEEK_API_KEY (others unset).
        CloudConfigSeeder seeder = new CloudConfigSeeder(
                jdbc, new FieldEncryptionService(), new ObjectMapper()) {
            @Override
            protected String readEnv(String name) {
                return "DEEPSEEK_API_KEY".equals(name) ? DUMMY_KEY : null;
            }
        };

        seeder.provisionLlmApiKeysFromEnv();

        // AFTER: row recreated, enabled, apiKey injected, default provider fields present.
        Boolean enabledAfter = jdbc.queryForObject(
                "SELECT enabled FROM ab_cloud_config WHERE pid='seed_llm_deepseek'", Boolean.class);
        String providerAfter = jdbc.queryForObject(
                "SELECT provider_code FROM ab_cloud_config WHERE pid='seed_llm_deepseek'", String.class);
        String apiKeyAfter = jdbc.queryForObject(
                "SELECT config::jsonb ->> 'apiKey' FROM ab_cloud_config WHERE pid='seed_llm_deepseek'",
                String.class);
        String baseUrlAfter = jdbc.queryForObject(
                "SELECT config::jsonb ->> 'baseUrl' FROM ab_cloud_config WHERE pid='seed_llm_deepseek'",
                String.class);

        assertTrue(Boolean.TRUE.equals(enabledAfter), "provider enabled after provision");
        assertEquals("deepseek", providerAfter, "provider_code set from default");
        assertEquals(DUMMY_KEY, apiKeyAfter, "env apiKey injected into config jsonb");
        assertEquals("https://api.deepseek.com", baseUrlAfter, "default baseUrl present");
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
