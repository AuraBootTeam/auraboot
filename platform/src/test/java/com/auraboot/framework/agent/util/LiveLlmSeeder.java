package com.auraboot.framework.agent.util;

import com.auraboot.framework.cloudconfig.dto.CloudConfigSaveRequest;
import com.auraboot.framework.cloudconfig.service.CloudConfigService;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared seeding for {@code @Tag("agent-eval-live")} integration tests.
 *
 * <p><strong>Why this exists.</strong> Every live eval IT used to carry its own
 * {@code seedDeepSeek()} with the provider code, baseUrl and — critically — the
 * <em>model name</em> hard-coded inline. That duplication (31 files pinned
 * {@code deepseek-chat}) silently rotted the entire live eval layer the day
 * DeepSeek retired that model name: every live capability eval failed with
 * {@code 400 invalid_request_error}, and nothing noticed because these tests are
 * excluded from the default task unless {@code -PincludeLiveEvals} is passed.
 *
 * <p><strong>Contract.</strong> Provider identity comes from the environment, never
 * from source. A test asks for "a live provider" and gets whichever one the
 * operator has credentials for, preferring qwen (owner has a standing plan):
 *
 * <ol>
 *   <li>{@code DASHSCOPE_API_KEY} → {@code qianwen} (qwen-plus, OpenAI-compatible)</li>
 *   <li>{@code DEEPSEEK_API_KEY} → {@code deepseek}</li>
 * </ol>
 *
 * <p>Model names are overridable per-run ({@code AURA_LIVE_EVAL_MODEL}) so a
 * provider-side rename is a one-line env change, not a 31-file sweep. Keys are
 * read from the environment and never logged — see the workspace rule on
 * reporting secrets as SET/UNSET only.
 */
public final class LiveLlmSeeder {

    /** Provider code understood by {@code LlmProviderFactory} for qwen/DashScope. */
    public static final String QIANWEN = "qianwen";
    /** Provider code for DeepSeek. */
    public static final String DEEPSEEK = "deepseek";

    private static final String QIANWEN_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode";
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";

    /** Current model names. Kept here (one place) rather than inline in every IT. */
    private static final String QIANWEN_DEFAULT_MODEL = "qwen-plus";
    private static final String DEEPSEEK_DEFAULT_MODEL = "deepseek-v4-flash";

    private LiveLlmSeeder() {}

    /**
     * The live provider this environment can actually reach, or {@code null} when no
     * live credential is present (callers turn that into an {@code assumeTrue} skip).
     */
    public static LiveProvider resolve() {
        String dashscope = System.getenv("DASHSCOPE_API_KEY");
        if (dashscope != null && !dashscope.isBlank()) {
            return new LiveProvider(QIANWEN, dashscope, QIANWEN_BASE_URL, model(QIANWEN_DEFAULT_MODEL),
                    "通义千问 (Qwen) — live eval");
        }
        String deepseek = System.getenv("DEEPSEEK_API_KEY");
        if (deepseek != null && !deepseek.isBlank()) {
            return new LiveProvider(DEEPSEEK, deepseek, DEEPSEEK_BASE_URL, model(DEEPSEEK_DEFAULT_MODEL),
                    "DeepSeek — live eval");
        }
        return null;
    }

    /** Per-run model override, so a provider-side rename needs no code change. */
    private static String model(String fallback) {
        String override = System.getenv("AURA_LIVE_EVAL_MODEL");
        return override != null && !override.isBlank() ? override : fallback;
    }

    /** Human-readable reason for the {@code assumeTrue} skip message. */
    public static String skipReason() {
        return "no live LLM credential (DASHSCOPE_API_KEY or DEEPSEEK_API_KEY) — skipping live eval";
    }

    /**
     * Seed the resolved provider as a tenant-scoped LLM config so it sorts ahead of any
     * platform-level provider. Idempotent: clears prior seeds for this provider first.
     */
    public static void seed(LiveProvider provider, Long tenantId,
                           CloudConfigService cloudConfigService, JdbcTemplate jdbcTemplate) {
        clear(provider, tenantId, jdbcTemplate);

        String configJson = "{"
                + "\"apiKey\":\"" + provider.apiKey() + "\","
                + "\"baseUrl\":\"" + provider.baseUrl() + "\","
                + "\"defaultModel\":\"" + provider.model() + "\","
                + "\"apiFormat\":\"chat_completions\","
                + "\"models\":[\"" + provider.model() + "\"],"
                + "\"displayName\":\"" + provider.displayName() + "\""
                + "}";

        CloudConfigSaveRequest req = new CloudConfigSaveRequest();
        req.setConfigLevel("tenant");
        req.setServiceType("llm");
        req.setProviderCode(provider.providerCode());
        req.setConfig(configJson);
        req.setEnabled(true);
        req.setPriority(0);
        cloudConfigService.saveConfig(req);
    }

    /** Remove this provider's seeded rows for the tenant. */
    public static void clear(LiveProvider provider, Long tenantId, JdbcTemplate jdbcTemplate) {
        jdbcTemplate.update(
                "DELETE FROM ab_cloud_config WHERE service_type='llm' AND provider_code=? AND tenant_id=?",
                provider.providerCode(), tenantId);
    }

    /**
     * A reachable live provider. {@code apiKey} is a live credential — never log,
     * print, or embed it in an assertion message.
     */
    public record LiveProvider(String providerCode, String apiKey, String baseUrl,
                               String model, String displayName) {
    }
}
