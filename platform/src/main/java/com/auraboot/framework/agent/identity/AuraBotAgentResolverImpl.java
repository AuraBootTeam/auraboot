package com.auraboot.framework.agent.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class AuraBotAgentResolverImpl implements AuraBotAgentResolver {

    private static final String SELECT_ACTIVE_ID = """
            SELECT id FROM ab_agent_definition
            WHERE tenant_id = ?
              AND agent_code = ?
              AND status = 'active'
              AND (deleted_flag = FALSE OR deleted_flag IS NULL)
            LIMIT 1
            """;

    private static final String LAZY_SEED_AURABOT = """
            INSERT INTO ab_agent_definition (
                pid, tenant_id, agent_code, name, description,
                agent_type, model, system_prompt,
                max_tools, max_concurrent_runs, execution_timeout_seconds,
                status, visibility, deleted_flag,
                created_at, updated_at
            )
            VALUES (?, ?, 'aurabot', 'AuraBot',
                'Platform-native AI assistant with full access to all models, commands, queries, and platform tools.',
                'reactive', 'claude-sonnet-4-6',
                'You are AuraBot, the intelligent assistant embedded in this platform. You have full access to all data models, commands, queries, and platform tools. Help users accomplish their business tasks efficiently, accurately, and with clear explanations.',
                20, 3, 300,
                'active', 'tenant', FALSE,
                CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    public AuraBotAgentResolverImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Long> tryResolve(long tenantId, String agentCode) {
        List<Long> rows = jdbcTemplate.queryForList(SELECT_ACTIVE_ID, Long.class, tenantId, agentCode);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Long resolve(long tenantId, String agentCode) {
        Optional<Long> existing = tryResolve(tenantId, agentCode);
        if (existing.isPresent()) {
            return existing.get();
        }

        if (!DEFAULT_AGENT_CODE.equals(agentCode)) {
            throw new AgentNotResolvedException(tenantId, agentCode);
        }

        // Lazy seed for aurabot only. Use deterministic pid 'aurabot_' + tenantId
        // matching the OSS schema.sql:7197 bootstrap pattern; max length 8 + 19 = 27,
        // VARCHAR(26) constrains tenantId to 18 digits which is well within BIGINT range.
        // ON CONFLICT DO NOTHING handles concurrent inserts via uq_agent_def_tenant_code.
        String pid = "aurabot_" + tenantId;
        jdbcTemplate.update(LAZY_SEED_AURABOT, pid, tenantId);

        return tryResolve(tenantId, agentCode)
                .orElseThrow(() -> new AgentNotResolvedException(tenantId, agentCode));
    }
}
