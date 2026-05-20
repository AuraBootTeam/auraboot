package com.auraboot.framework.agent.identity;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * JDBC-backed resolver for {@code ab_agent_user_profile.pid}.
 */
@Service
@RequiredArgsConstructor
public class JdbcAgentUserProfileResolver implements AgentUserProfileResolver {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Optional<String> resolveProfileId(long tenantId, long userId) {
        try {
            String pid = jdbcTemplate.queryForObject("""
                    SELECT pid
                    FROM ab_agent_user_profile
                    WHERE tenant_id = ?
                      AND user_id = ?
                      AND (deleted_flag IS NULL OR deleted_flag = FALSE)
                    LIMIT 1
                    """, String.class, tenantId, userId);
            return pid == null || pid.isBlank() ? Optional.empty() : Optional.of(pid);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
