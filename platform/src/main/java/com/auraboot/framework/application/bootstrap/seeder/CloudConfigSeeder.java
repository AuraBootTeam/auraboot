package com.auraboot.framework.application.bootstrap.seeder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CloudConfigSeeder {
    private final JdbcTemplate jdbcTemplate;

    public void seed() {
        // Check if already seeded by looking for any seed_ prefixed pid
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ab_cloud_config WHERE pid LIKE 'seed_%'", Integer.class);
        if (existing != null && existing > 0) {
            log.info("CloudConfigSeeder: seeded 0 cloud configs (skipped {} existing)", existing);
            return;
        }

        String sql = """
                INSERT INTO ab_cloud_config (pid, config_level, service_type, provider_code, config, enabled, priority)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT DO NOTHING
                """;

        Object[][] rows = {
            // LLM Providers
            {
                "seed_llm_anthropic", "platform", "llm", "anthropic",
                "{\"displayName\":\"Anthropic (Claude)\",\"apiFormat\":\"messages\",\"baseUrl\":\"https://api.anthropic.com\",\"defaultModel\":\"claude-sonnet-4-6\",\"maxTokens\":4096,\"models\":[\"claude-opus\",\"claude-sonnet\",\"claude-haiku\"]}",
                false, 10
            },
            {
                "seed_llm_openai", "platform", "llm", "openai",
                "{\"displayName\":\"OpenAI\",\"apiFormat\":\"chat_completions\",\"baseUrl\":\"https://api.openai.com\",\"defaultModel\":\"gpt-4o\",\"maxTokens\":4096,\"models\":[\"gpt-4\",\"gpt-3.5\",\"o1-\",\"o3-\",\"o4-\"]}",
                false, 20
            },
            {
                "seed_llm_deepseek", "platform", "llm", "deepseek",
                "{\"displayName\":\"DeepSeek\",\"apiFormat\":\"chat_completions\",\"baseUrl\":\"https://api.deepseek.com\",\"defaultModel\":\"deepseek-chat\",\"maxTokens\":4096,\"models\":[\"deepseek\"]}",
                false, 30
            },
            {
                // TODO: Remove API key before open-source release
                "seed_llm_minimaxi", "platform", "llm", "minimaxi",
                "{\"apiKey\":\"sk-cp-XNk1pU7mUnMlnRoprZArXq1XhTNVgVgtBXm48BW6XjROAB4vsK2DyEgyCOS7ODpgJWvy9jx9dTQE7tR3q_1mg0ldaiBc-j5H1wwrFzJ3RcqYYLIpL1jW_cI\",\"displayName\":\"MiniMax (海螺AI)\",\"apiFormat\":\"chat_completions\",\"baseUrl\":\"https://api.minimax.chat/v1\",\"defaultModel\":\"MiniMax-M2.5\",\"maxTokens\":4096,\"models\":[\"MiniMax-M2.5\",\"MiniMax-Text-01\",\"abab6.5s-chat\"]}",
                true, 40
            },
            {
                "seed_llm_qianwen", "platform", "llm", "qianwen",
                "{\"displayName\":\"通义千问 (Qwen)\",\"apiFormat\":\"chat_completions\",\"baseUrl\":\"https://dashscope.aliyuncs.com/compatible-mode\",\"defaultModel\":\"qwen-plus\",\"maxTokens\":4096,\"models\":[\"qwen\"]}",
                false, 50
            },
            {
                "seed_llm_zhipu", "platform", "llm", "zhipu",
                "{\"displayName\":\"智谱 (Zhipu)\",\"apiFormat\":\"chat_completions\",\"baseUrl\":\"https://open.bigmodel.cn/api/paas\",\"defaultModel\":\"glm-4\",\"maxTokens\":4096,\"models\":[\"glm\"]}",
                false, 60
            },
            {
                "seed_llm_moonshot", "platform", "llm", "moonshot",
                "{\"displayName\":\"月之暗面 (Moonshot)\",\"apiFormat\":\"chat_completions\",\"baseUrl\":\"https://api.moonshot.cn\",\"defaultModel\":\"moonshot-v1-8k\",\"maxTokens\":4096,\"models\":[\"moonshot\"]}",
                false, 70
            },
            // Embedding Providers
            {
                "seed_emb_openai", "platform", "embedding", "openai",
                "{\"displayName\":\"OpenAI Embeddings\",\"baseUrl\":\"https://api.openai.com\",\"defaultModel\":\"text-embedding-3-small\",\"dimension\":1536,\"maxBatchSize\":20}",
                false, 10
            },
            {
                "seed_emb_zhipu", "platform", "embedding", "zhipu",
                "{\"displayName\":\"智谱 Embedding\",\"baseUrl\":\"https://open.bigmodel.cn/api/paas\",\"defaultModel\":\"embedding-3\",\"dimension\":2048,\"maxBatchSize\":20}",
                false, 20
            },
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

        int count = 0;
        for (Object[] row : rows) {
            count += jdbcTemplate.update(sql, row[0], row[1], row[2], row[3], row[4], row[5], row[6]);
        }
        log.info("CloudConfigSeeder: seeded {} cloud configs (skipped {} existing)", count, rows.length - count);
    }
}
