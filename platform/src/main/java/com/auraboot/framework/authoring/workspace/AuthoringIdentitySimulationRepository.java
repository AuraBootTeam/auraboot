package com.auraboot.framework.authoring.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Tenant/environment/actor-scoped persistence for audited identity simulation sessions. */
@Repository
public class AuthoringIdentitySimulationRepository {

    private static final RowMapper<SimulationRow> ROW_MAPPER = (resultSet, rowNum) ->
            new SimulationRow(
                    resultSet.getLong("id"),
                    resultSet.getString("pid"),
                    resultSet.getLong("tenant_id"),
                    resultSet.getLong("env_id"),
                    resultSet.getLong("actor_user_id"),
                    resultSet.getString("source_session_pid"),
                    resultSet.getString("change_set_pid"),
                    resultSet.getString("page_pid"),
                    resultSet.getString("target_role_pid"),
                    resultSet.getString("target_role_code"),
                    resultSet.getString("target_role_name"),
                    resultSet.getString("reason"),
                    resultSet.getString("status"),
                    resultSet.getTimestamp("started_at").toInstant(),
                    resultSet.getTimestamp("expires_at").toInstant(),
                    nullableInstant(resultSet.getTimestamp("ended_at")),
                    nullableInstant(resultSet.getTimestamp("last_accessed_at")),
                    nullableInstant(resultSet.getTimestamp("acknowledged_at")),
                    resultSet.getLong("row_version"));

    private final JdbcTemplate jdbcTemplate;

    public AuthoringIdentitySimulationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void create(CreateSimulation command) {
        jdbcTemplate.update("""
                INSERT INTO ab_authoring_identity_simulation (
                    pid, tenant_id, env_id, actor_user_id, source_session_pid,
                    change_set_pid, page_pid, target_role_pid, target_role_code, target_role_name,
                    reason, status, started_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                command.pid(), command.tenantId(), command.envId(), command.actorUserId(),
                command.sourceSessionPid(), command.changeSetPid(), command.pagePid(),
                command.targetRolePid(),
                command.targetRoleCode(), command.targetRoleName(), command.reason(),
                Timestamp.from(command.startedAt()),
                Timestamp.from(command.expiresAt()));
    }

    public SimulationRow find(
            long tenantId,
            long envId,
            long actorUserId,
            String simulationPid,
            boolean lock) {
        String lockClause = lock ? " FOR UPDATE" : "";
        List<SimulationRow> rows = jdbcTemplate.query("""
                        SELECT id, pid, tenant_id, env_id, actor_user_id, source_session_pid,
                               change_set_pid, page_pid, target_role_pid,
                               target_role_code, target_role_name,
                               reason, status, started_at, expires_at, ended_at,
                               last_accessed_at, acknowledged_at, row_version
                        FROM ab_authoring_identity_simulation
                        WHERE tenant_id = ? AND env_id = ? AND actor_user_id = ? AND pid = ?
                        """ + lockClause,
                ROW_MAPPER,
                tenantId, envId, actorUserId, simulationPid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public SimulationRow findActiveForSession(
            long tenantId,
            long envId,
            long actorUserId,
            String sourceSessionPid,
            boolean lock) {
        String lockClause = lock ? " FOR UPDATE" : "";
        List<SimulationRow> rows = jdbcTemplate.query("""
                        SELECT id, pid, tenant_id, env_id, actor_user_id, source_session_pid,
                               change_set_pid, page_pid, target_role_pid,
                               target_role_code, target_role_name,
                               reason, status, started_at, expires_at, ended_at,
                               last_accessed_at, acknowledged_at, row_version
                        FROM ab_authoring_identity_simulation
                        WHERE tenant_id = ? AND env_id = ? AND actor_user_id = ?
                          AND source_session_pid = ? AND status = 'ACTIVE'
                        ORDER BY created_at DESC
                        LIMIT 1
                        """ + lockClause,
                ROW_MAPPER,
                tenantId, envId, actorUserId, sourceSessionPid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public SimulationRow findRecoverableForSession(
            long tenantId,
            long envId,
            long actorUserId,
            String sourceSessionPid,
            boolean lock) {
        String lockClause = lock ? " FOR UPDATE" : "";
        List<SimulationRow> rows = jdbcTemplate.query("""
                        SELECT id, pid, tenant_id, env_id, actor_user_id, source_session_pid,
                               change_set_pid, page_pid, target_role_pid,
                               target_role_code, target_role_name,
                               reason, status, started_at, expires_at, ended_at,
                               last_accessed_at, acknowledged_at, row_version
                        FROM ab_authoring_identity_simulation
                        WHERE tenant_id = ? AND env_id = ? AND actor_user_id = ?
                          AND source_session_pid = ?
                          AND (status = 'ACTIVE' OR acknowledged_at IS NULL)
                        ORDER BY CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END,
                                 created_at DESC
                        LIMIT 1
                        """ + lockClause,
                ROW_MAPPER,
                tenantId, envId, actorUserId, sourceSessionPid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean markAccessed(SimulationRow row, Instant accessedAt) {
        return jdbcTemplate.update("""
                UPDATE ab_authoring_identity_simulation
                SET last_accessed_at = ?, row_version = row_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND actor_user_id = ?
                  AND status = 'ACTIVE' AND row_version = ?
                """,
                Timestamp.from(accessedAt), row.id(), row.tenantId(), row.envId(),
                row.actorUserId(), row.rowVersion()) == 1;
    }

    public boolean end(SimulationRow row, String terminalStatus, Instant endedAt) {
        return jdbcTemplate.update("""
                UPDATE ab_authoring_identity_simulation
                SET status = ?, ended_at = ?, row_version = row_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND actor_user_id = ?
                  AND status = 'ACTIVE' AND row_version = ?
                """,
                terminalStatus, Timestamp.from(endedAt), row.id(), row.tenantId(), row.envId(),
                row.actorUserId(), row.rowVersion()) == 1;
    }

    public boolean acknowledge(SimulationRow row, Instant acknowledgedAt) {
        return jdbcTemplate.update("""
                UPDATE ab_authoring_identity_simulation
                SET acknowledged_at = ?, row_version = row_version + 1,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND actor_user_id = ?
                  AND status IN ('ENDED', 'EXPIRED')
                  AND acknowledged_at IS NULL AND row_version = ?
                """,
                Timestamp.from(acknowledgedAt), row.id(), row.tenantId(), row.envId(),
                row.actorUserId(), row.rowVersion()) == 1;
    }

    private static Instant nullableInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record CreateSimulation(
            String pid,
            long tenantId,
            long envId,
            long actorUserId,
            String sourceSessionPid,
            String changeSetPid,
            String pagePid,
            String targetRolePid,
            String targetRoleCode,
            String targetRoleName,
            String reason,
            Instant startedAt,
            Instant expiresAt) {
    }

    public record SimulationRow(
            long id,
            String pid,
            long tenantId,
            long envId,
            long actorUserId,
            String sourceSessionPid,
            String changeSetPid,
            String pagePid,
            String targetRolePid,
            String targetRoleCode,
            String targetRoleName,
            String reason,
            String status,
            Instant startedAt,
            Instant expiresAt,
            Instant endedAt,
            Instant lastAccessedAt,
            Instant acknowledgedAt,
            long rowVersion) {
    }
}
