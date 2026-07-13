package com.auraboot.framework.application.bootstrap.seeder;

import com.auraboot.framework.common.crypto.FieldEncryptionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class CloudConfigSeeder {
    private final JdbcTemplate jdbcTemplate;
    private final FieldEncryptionService fieldEncryptionService;
    private final ObjectMapper objectMapper;

    private static final String INSERT_SQL = """
            INSERT INTO ab_cloud_config (pid, config_level, service_type, provider_code, config, enabled, priority)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT DO NOTHING
            """;

    /**
     * Sensitive JSON field names that must be encrypted at rest before INSERT.
     * Mirrors {@code CloudConfigServiceImpl.SENSITIVE_FIELDS}. When configs
     * are saved through {@code CloudConfigService.saveConfig}, the auto-encrypt
     * path runs. This seeder uses raw JDBC for bootstrap, so we replicate the
     * encryption explicitly here — without it, hardcoded apiKey values would
     * be stored in plaintext (which the previous version of this seeder did,
     * a real CVE-grade leak).
     */
    private static final Set<String> SENSITIVE_CONFIG_FIELDS = Set.of(
            "apiKey",
            "secretId", "secretKey", "appSecret", "clientSecret",
            "privateKey", "password", "accessKey", "accessToken", "refreshToken"
    );

    /** Default (keyless, disabled) config for an env-provisionable LLM provider. */
    private record ProviderSeed(String providerCode, int priority, String config) {}

    /**
     * Default config for the OpenAI-compatible / messages-format LLM providers whose
     * apiKey is supplied via {@code <PROVIDER>_API_KEY} env vars (see
     * {@link #LLM_PROVIDER_ENV_KEYS}). Single source of truth shared by
     * {@link #seedDefaults()} (seeds them keyless + disabled on a fresh DB) and
     * {@link #provisionLlmApiKeysFromEnv()} (recreates a row from this default when
     * the operator sets the env var but the row is absent — e.g. it was deleted
     * during a credential cleanup). All entries are keyless: keys only ever come
     * from the environment, never from source.
     */
    private static final Map<String, ProviderSeed> LLM_PROVIDER_SEEDS = buildLlmProviderSeeds();

    private static Map<String, ProviderSeed> buildLlmProviderSeeds() {
        Map<String, ProviderSeed> m = new LinkedHashMap<>();
        m.put("seed_llm_anthropic", new ProviderSeed("anthropic", 10,
                "{\"displayName\":\"Anthropic (Claude)\",\"apiFormat\":\"messages\",\"baseUrl\":\"https://api.anthropic.com\",\"defaultModel\":\"claude-sonnet-4-6\",\"maxTokens\":4096,\"models\":[\"claude-opus\",\"claude-sonnet\",\"claude-haiku\"]}"));
        m.put("seed_llm_openai", new ProviderSeed("openai", 20,
                "{\"displayName\":\"OpenAI\",\"apiFormat\":\"chat_completions\",\"baseUrl\":\"https://api.openai.com\",\"defaultModel\":\"gpt-4o\",\"maxTokens\":4096,\"models\":[\"gpt-4\",\"gpt-3.5\",\"o1-\",\"o3-\",\"o4-\"]}"));
        m.put("seed_llm_deepseek", new ProviderSeed("deepseek", 30,
                "{\"displayName\":\"DeepSeek\",\"apiFormat\":\"chat_completions\",\"baseUrl\":\"https://api.deepseek.com\",\"defaultModel\":\"deepseek-chat\",\"maxTokens\":4096,\"models\":[\"deepseek\"]}"));
        m.put("seed_llm_qianwen", new ProviderSeed("qianwen", 50,
                "{\"displayName\":\"通义千问 (Qwen)\",\"apiFormat\":\"chat_completions\",\"baseUrl\":\"https://dashscope.aliyuncs.com/compatible-mode\",\"defaultModel\":\"qwen-plus\",\"maxTokens\":4096,\"models\":[\"qwen\"]}"));
        m.put("seed_llm_zhipu", new ProviderSeed("zhipu", 60,
                "{\"displayName\":\"智谱 (Zhipu)\",\"apiFormat\":\"chat_completions\",\"baseUrl\":\"https://open.bigmodel.cn/api/paas\",\"defaultModel\":\"glm-4\",\"maxTokens\":4096,\"models\":[\"glm\"]}"));
        m.put("seed_llm_moonshot", new ProviderSeed("moonshot", 70,
                "{\"displayName\":\"月之暗面 (Moonshot)\",\"apiFormat\":\"chat_completions\",\"baseUrl\":\"https://api.moonshot.cn\",\"defaultModel\":\"moonshot-v1-8k\",\"maxTokens\":4096,\"models\":[\"moonshot\"]}"));
        return m;
    }

    /** seed_ pid → environment variable holding that LLM provider's apiKey. */
    private static final Map<String, String> LLM_PROVIDER_ENV_KEYS = Map.of(
            "seed_llm_deepseek", "DEEPSEEK_API_KEY",
            "seed_llm_openai", "OPENAI_API_KEY",
            "seed_llm_anthropic", "ANTHROPIC_API_KEY",
            "seed_llm_qianwen", "DASHSCOPE_API_KEY",
            "seed_llm_zhipu", "ZHIPU_API_KEY",
            "seed_llm_moonshot", "MOONSHOT_API_KEY");

    /**
     * Embedding providers, seeded keyless + disabled and provisioned from the environment exactly
     * like the LLM providers above.
     *
     * <p>⚠️ The dimension field is {@code dimensions} (plural) — that is the key
     * {@code EmbeddingService.resolveConfig} reads. Spelling it {@code dimension} means the value is
     * silently ignored, the request goes out without a dimension, and the provider answers with its
     * own default. That is not cosmetic: {@code text-embedding-v4} defaults to <b>1024</b> dims,
     * while {@code ab_kb_chunk.embedding} is {@code vector(1536)} — every chunk would fail to
     * insert with "expected 1536 dimensions, not 1024", and the whole knowledge base would embed
     * into nothing.
     */
    private static final Map<String, ProviderSeed> EMBEDDING_PROVIDER_SEEDS = Map.of(
            "seed_emb_openai", new ProviderSeed("openai", 10,
                    "{\"displayName\":\"OpenAI Embeddings\",\"baseUrl\":\"https://api.openai.com\","
                            + "\"defaultModel\":\"text-embedding-3-small\",\"dimensions\":1536,\"maxBatchSize\":20}"),
            // DashScope speaks the OpenAI-compatible /v1/embeddings dialect, so EmbeddingService
            // needs no provider-specific code. Batch limit measured against the live API: 10 passes,
            // 25 is rejected with InvalidParameter.
            "seed_emb_qianwen", new ProviderSeed("qianwen", 15,
                    "{\"displayName\":\"通义千问 Embedding (DashScope)\","
                            + "\"baseUrl\":\"https://dashscope.aliyuncs.com/compatible-mode\","
                            + "\"defaultModel\":\"text-embedding-v4\",\"dimensions\":1536,\"maxBatchSize\":10}"),
            // Seeded but NOT offered in the knowledge-base dialog, and that is deliberate:
            // embedding-3 answers with 2048 dimensions while ab_kb_chunk.embedding is vector(1536),
            // so a knowledge base created on it could not embed a single chunk. The config is left
            // exactly as it was — "dimension" (a key EmbeddingService does not read) and a 2048-dim
            // model — because correcting it means guessing at Zhipu's dimensions parameter with no
            // key to verify against, and a plausible guess that is wrong is worse than a documented
            // gap. To bring it back: pin dimensions=1536, verify against the live API, then restore
            // the option in knowledge.tsx.
            "seed_emb_zhipu", new ProviderSeed("zhipu", 20,
                    "{\"displayName\":\"智谱 Embedding\",\"baseUrl\":\"https://open.bigmodel.cn/api/paas\","
                            + "\"defaultModel\":\"embedding-3\",\"dimension\":2048,\"maxBatchSize\":20}"));

    /** seed_ pid → environment variable holding that embedding provider's apiKey. */
    private static final Map<String, String> EMBEDDING_PROVIDER_ENV_KEYS = Map.of(
            "seed_emb_openai", "OPENAI_API_KEY",
            "seed_emb_qianwen", "DASHSCOPE_API_KEY",
            "seed_emb_zhipu", "ZHIPU_API_KEY");

    public void seed() {
        // Check if already seeded by looking for any seed_ prefixed pid
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ab_cloud_config WHERE pid LIKE 'seed_%'", Integer.class);
        if (existing != null && existing > 0) {
            log.info("CloudConfigSeeder: seeded 0 cloud configs (skipped {} existing)", existing);
        } else {
            seedDefaults();
        }
        // Always (idempotent): provision/override provider apiKeys from environment
        // variables (<PROVIDER>_API_KEY). Keeps secrets in the environment — never
        // hardcoded in source, never pasted into chat — and re-applies on every boot.
        provisionApiKeysFromEnv();
    }

    private void seedDefaults() {
        int count = 0;

        // Env-provisionable providers — keyless + disabled until a key is supplied.
        count += seedProviders(LLM_PROVIDER_SEEDS, "llm");
        count += seedProviders(EMBEDDING_PROVIDER_SEEDS, "embedding");

        Object[][] rows = {
            // Prompt Templates
            {
                "seed_tpl_aurabot_system", "platform", "prompt_template", "aurabot_system",
                "{\"template\":\"You are AuraBot, an AI assistant for the AuraBoot platform ({{tenantName}}).\\nYou help users understand their business data, suggest actions, and execute operations.\\nAlways respond in the user's language (Chinese by default). Be concise and helpful.\\n\\n{{#if context}}## Current Context\\n{{context}}{{/if}}\\n\\n{{#if tools}}## Available Actions\\nYou can suggest or execute these actions:\\n{{#each tools}}- {{code}}: {{description}}\\n{{/each}}{{/if}}\",\"description\":\"AuraBot main system prompt\",\"variables\":[\"tenantName\",\"context\",\"tools\"]}",
                false, 10
            },
            {
                "seed_tpl_aurabot_context", "platform", "prompt_template", "aurabot_context",
                "{\"template\":\"Page type: {{pageType}} | Model: {{modelCode}}\\n{{#if breadcrumb}}Path: {{breadcrumb}}{{/if}}\\n{{#if recordData}}Record fields:\\n{{#each recordData}}- {{@key}}: {{this}}\\n{{/each}}{{/if}}\",\"description\":\"Context injection section\",\"variables\":[\"pageType\",\"modelCode\",\"breadcrumb\",\"recordData\"]}",
                false, 10
            },
            {
                "seed_tpl_aurabot_tool_hint", "platform", "prompt_template", "aurabot_tool_hint",
                "{\"template\":\"You have access to tools that can query data and execute commands on the current record.\\n\\nRules:\\n1. For data queries, use the available query tools directly.\\n2. For write operations (commands), describe what you will do before calling the tool.\\n3. Always include the recordPid when operating on a specific record.\\n4. Present query results in a readable format (table or summary in Chinese).\\n5. If a tool fails, explain the error to the user in Chinese.\\n6. Always respond in Chinese unless the user writes in another language.\",\"description\":\"Tool calling instructions for native LLM tool_use\",\"variables\":[]}",
                true, 10
            },
        };

        for (Object[] row : rows) {
            String configJson = (String) row[4];
            String encryptedConfigJson = encryptSensitiveFields(configJson);
            count += jdbcTemplate.update(INSERT_SQL, row[0], row[1], row[2], row[3], encryptedConfigJson, row[5], row[6]);
        }
        int total = LLM_PROVIDER_SEEDS.size() + EMBEDDING_PROVIDER_SEEDS.size() + rows.length;
        log.info("CloudConfigSeeder: seeded {} cloud configs (skipped {} existing)", count, total - count);
    }

    private int seedProviders(Map<String, ProviderSeed> seeds, String serviceType) {
        int count = 0;
        for (Map.Entry<String, ProviderSeed> e : seeds.entrySet()) {
            ProviderSeed seed = e.getValue();
            count += jdbcTemplate.update(INSERT_SQL, e.getKey(), "platform", serviceType,
                    seed.providerCode(), encryptSensitiveFields(seed.config()), false, seed.priority());
        }
        return count;
    }

    /**
     * Provisions LLM provider apiKeys from environment variables — the 12-factor way
     * to supply secrets in dev/CI/prod without hardcoding them in source or pasting
     * them into a chat. For each provider whose {@code <PROVIDER>_API_KEY} env var is
     * set, merges the key into the provider's config (encrypted) and enables it.
     *
     * <p>If the row is absent (e.g. it was deleted during a credential cleanup) and
     * the provider has a known default ({@link #LLM_PROVIDER_SEEDS}), the row is
     * recreated from that default so setting the env var alone is sufficient to make
     * the provider work. Idempotent: runs on every boot, only writes when the env var
     * is set, never clears a key when the env var is absent, and never logs the key.
     */
    void provisionApiKeysFromEnv() {
        provisionFromEnv(LLM_PROVIDER_ENV_KEYS, LLM_PROVIDER_SEEDS, "llm");
        provisionFromEnv(EMBEDDING_PROVIDER_ENV_KEYS, EMBEDDING_PROVIDER_SEEDS, "embedding");
    }

    private void provisionFromEnv(Map<String, String> envKeys, Map<String, ProviderSeed> seeds,
                                    String serviceType) {
        for (Map.Entry<String, String> entry : envKeys.entrySet()) {
            String pid = entry.getKey();
            String envVar = entry.getValue();
            String apiKey = readEnv(envVar);
            if (apiKey == null || apiKey.isBlank()) {
                continue;
            }
            List<String> existing = jdbcTemplate.queryForList(
                    "SELECT config::text FROM ab_cloud_config WHERE pid = ?", String.class, pid);
            ProviderSeed seed = seeds.get(pid);
            if (existing.isEmpty() && seed == null) {
                // Absent row with no known default (e.g. an inline-only provider) —
                // skip rather than guess a config shape.
                continue;
            }
            try {
                String baseConfig = existing.isEmpty() ? seed.config() : existing.get(0);
                ObjectNode config = (ObjectNode) objectMapper.readTree(baseConfig);
                config.put("apiKey", apiKey);
                String encrypted = encryptSensitiveFields(objectMapper.writeValueAsString(config));
                if (existing.isEmpty()) {
                    jdbcTemplate.update(INSERT_SQL, pid, "platform", serviceType,
                            seed.providerCode(), encrypted, true, seed.priority());
                    log.info("CloudConfigSeeder: created + provisioned {} from env {} (enabled)",
                            pid, envVar);
                } else {
                    jdbcTemplate.update(
                            "UPDATE ab_cloud_config SET config = ?::jsonb, enabled = true WHERE pid = ?",
                            encrypted, pid);
                    log.info("CloudConfigSeeder: provisioned {} apiKey from env {} (enabled)",
                            pid, envVar);
                }
            } catch (Exception e) {
                log.warn("CloudConfigSeeder: failed to provision {} apiKey from env {}: {}",
                        pid, envVar, e.getMessage());
            }
        }
    }

    /** Seam over {@link System#getenv(String)} so the env source can be overridden in tests. */
    protected String readEnv(String name) {
        return System.getenv(name);
    }

    /**
     * Encrypts {@link #SENSITIVE_CONFIG_FIELDS} string values inside the seed
     * config JSON before INSERT. Returns the original JSON unchanged when:
     * <ul>
     *   <li>No encryption key is configured ({@link FieldEncryptionService}
     *       in passthrough mode) — same effective behavior as before this fix
     *       for dev environments lacking {@code FIELD_ENCRYPTION_KEY}.</li>
     *   <li>The JSON has no sensitive fields (most seed rows).</li>
     *   <li>Parsing fails (malformed JSON in the seed row itself — that's a
     *       code bug, logged at warn).</li>
     * </ul>
     *
     * <p>When the key IS configured, this method produces {@code ENC:<base64>}
     * ciphertext for the apiKey/secret values so plaintext never reaches disk.
     * Reads via {@code CloudConfigService.getEffectiveConfig} auto-decrypt.
     */
    String encryptSensitiveFields(String configJson) {
        if (configJson == null || configJson.isBlank()) return configJson;
        try {
            JsonNode node = objectMapper.readTree(configJson);
            if (!(node instanceof ObjectNode obj)) return configJson;
            boolean changed = false;
            for (String field : SENSITIVE_CONFIG_FIELDS) {
                if (obj.has(field) && obj.get(field).isTextual()) {
                    String plain = obj.get(field).asText();
                    if (!plain.isBlank()) {
                        String encrypted = fieldEncryptionService.encrypt(plain);
                        if (!encrypted.equals(plain)) {
                            obj.put(field, encrypted);
                            changed = true;
                        }
                    }
                }
            }
            return changed ? objectMapper.writeValueAsString(obj) : configJson;
        } catch (Exception e) {
            // Seed JSON is committed source — a parse failure indicates a
            // code bug, not runtime data corruption. Log + fall through to
            // raw insert so the seed still completes (and the bug is visible
            // in tests rather than silently broken at boot).
            log.warn("CloudConfigSeeder: failed to pre-encrypt config JSON; "
                    + "inserting verbatim. error={}", e.getMessage());
            return configJson;
        }
    }
}
