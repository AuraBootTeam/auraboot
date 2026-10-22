package com.auraboot.framework.agent.util;

import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Provider-neutral live LLM profile used by all business capability tests.
 *
 * <p>Business tests do not select a vendor, infer one from whatever key happens
 * to exist, or carry provider model names. The release runner supplies one
 * explicit profile:
 *
 * <ul>
 *   <li>{@code AURA_LIVE_LLM_PROVIDER}</li>
 *   <li>{@code AURA_LIVE_LLM_MODEL}</li>
 *   <li>{@code AURA_LIVE_LLM_API_KEY_ENV}</li>
 *   <li>{@code AURA_LIVE_LLM_BASE_URL} (optional when the provider catalog has one)</li>
 * </ul>
 *
 * <p>The API-key variable is dereferenced exactly once and its value is never
 * logged or placed in a test name/assertion. Provider-specific defaults belong
 * to the provider catalog or an explicit validation profile, not this class.
 */
public final class LiveLlmSeeder {

    static final String PROVIDER_ENV = "AURA_LIVE_LLM_PROVIDER";
    static final String MODEL_ENV = "AURA_LIVE_LLM_MODEL";
    static final String API_KEY_ENV_ENV = "AURA_LIVE_LLM_API_KEY_ENV";
    static final String BASE_URL_ENV = "AURA_LIVE_LLM_BASE_URL";

    private static final Pattern ENV_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private LiveLlmSeeder() {
    }

    /**
     * Resolve the explicit live profile, or {@code null} when it is incomplete.
     * The capability runner treats that as a hard failure; individual opt-in
     * tests may turn it into an assumption skip.
     */
    public static LiveProvider resolve() {
        return resolve(System.getenv());
    }

    static LiveProvider resolve(Map<String, String> environment) {
        if (environment == null) {
            return null;
        }
        String providerCode = normalized(environment.get(PROVIDER_ENV));
        String model = normalized(environment.get(MODEL_ENV));
        String apiKeyEnv = normalized(environment.get(API_KEY_ENV_ENV));
        if (providerCode == null || model == null || apiKeyEnv == null
                || !ENV_NAME.matcher(apiKeyEnv).matches()) {
            return null;
        }
        String apiKey = normalized(environment.get(apiKeyEnv));
        if (apiKey == null) {
            return null;
        }
        return new LiveProvider(
                providerCode,
                apiKey,
                apiKeyEnv,
                normalized(environment.get(BASE_URL_ENV)),
                model);
    }

    /** Human-readable skip reason containing variable names, never values. */
    public static String skipReason() {
        String apiKeyEnv = normalized(System.getenv(API_KEY_ENV_ENV));
        if (normalized(System.getenv(PROVIDER_ENV)) == null
                || normalized(System.getenv(MODEL_ENV)) == null
                || apiKeyEnv == null) {
            return "incomplete provider-neutral live profile: set "
                    + PROVIDER_ENV + ", " + MODEL_ENV + " and " + API_KEY_ENV_ENV;
        }
        if (!ENV_NAME.matcher(apiKeyEnv).matches()
                || normalized(System.getenv(apiKeyEnv)) == null) {
            return "configured live API-key environment variable is unset or invalid: "
                    + apiKeyEnv;
        }
        return "live LLM profile is unavailable";
    }

    /**
     * Seed the selected profile as a tenant-scoped configuration. When the
     * optional base URL is absent, reuse the matching provider-catalog URL;
     * never guess a vendor endpoint in business-test code.
     */
    public static LiveProvider seed(
            LiveProvider provider,
            Long tenantId,
            CloudConfigService cloudConfigService,
            JdbcTemplate jdbcTemplate) {
        if (provider == null || tenantId == null) {
            throw new IllegalArgumentException("live provider and tenantId are required");
        }
        String baseUrl = provider.baseUrl() != null
                ? provider.baseUrl()
                : catalogBaseUrl(provider.providerCode(), tenantId, jdbcTemplate);
        if (baseUrl == null) {
            throw new IllegalStateException(
                    "No base URL supplied and provider catalog has none for "
                            + provider.providerCode());
        }

        clear(provider, tenantId, jdbcTemplate);

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("apiKey", provider.apiKey());
        config.put("baseUrl", baseUrl);
        config.put("defaultModel", provider.model());
        config.put("apiFormat", "chat_completions");
        config.put("models", List.of(provider.model()));
        config.put("displayName", "Live LLM validation profile");

        CloudConfigSaveRequest request = new CloudConfigSaveRequest();
        request.setConfigLevel("tenant");
        request.setServiceType("llm");
        request.setProviderCode(provider.providerCode());
        request.setConfig(toJson(config));
        request.setEnabled(true);
        request.setPriority(0);
        cloudConfigService.saveConfig(request);
        return new LiveProvider(
                provider.providerCode(),
                provider.apiKey(),
                provider.apiKeyEnvironmentVariable(),
                baseUrl,
                provider.model());
    }

    /** Remove this profile's seeded rows for the tenant. */
    public static void clear(
            LiveProvider provider,
            Long tenantId,
            JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "DELETE FROM ab_cloud_config "
                        + "WHERE service_type='llm' AND provider_code=? AND tenant_id=?",
                provider.providerCode(),
                tenantId);
    }

    private static String catalogBaseUrl(
            String providerCode,
            Long tenantId,
            JdbcTemplate jdbcTemplate) {
        List<String> urls = jdbcTemplate.query(
                """
                SELECT config ->> 'baseUrl'
                FROM ab_cloud_config
                WHERE service_type = 'llm'
                  AND provider_code = ?
                  AND (tenant_id = ? OR tenant_id IS NULL)
                ORDER BY CASE WHEN tenant_id = ? THEN 0 ELSE 1 END, priority
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getString(1),
                providerCode,
                tenantId,
                tenantId);
        return urls.isEmpty() ? null : normalized(urls.get(0));
    }

    private static String toJson(Map<String, Object> config) {
        try {
            return OBJECT_MAPPER.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("Could not encode live LLM profile", e);
        }
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * In-memory credential is deliberately excluded from {@link #toString()}.
     */
    public record LiveProvider(
            String providerCode,
            String apiKey,
            String apiKeyEnvironmentVariable,
            String baseUrl,
            String model) {

        @Override
        public String toString() {
            return "LiveProvider[providerCode=" + providerCode
                    + ", apiKeyEnvironmentVariable=" + apiKeyEnvironmentVariable
                    + ", baseUrl=" + baseUrl
                    + ", model=" + model
                    + "]";
        }
    }
}
