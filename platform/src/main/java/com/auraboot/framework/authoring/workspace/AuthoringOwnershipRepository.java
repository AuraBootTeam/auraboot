package com.auraboot.framework.authoring.workspace;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import static org.springframework.http.HttpStatus.CONFLICT;

/** Persists one tenant-owned lineage row for each inherited authoring source. */
@Repository
public class AuthoringOwnershipRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuthoringOwnershipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TenantOverrideRow findOrCreate(CreateTenantOverride command) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO ab_authoring_tenant_override (
                    pid, tenant_id, env_id, source_resource_type, source_resource_pid,
                    source_ownership_scope, base_source_version, base_source_checksum,
                    status, created_by)
                VALUES (?, ?, ?, 'PAGE_SCHEMA', ?, ?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (tenant_id, env_id, source_resource_type, source_resource_pid)
                DO NOTHING
                """,
                command.overridePid(), command.tenantId(), command.envId(),
                command.sourceResourcePid(), command.sourceOwnershipScope(),
                command.baseSourceVersion(), command.baseSourceChecksum(), command.actorUserId());
        List<TenantOverrideRow> rows = jdbcTemplate.query("""
                        SELECT pid, tenant_id, env_id, source_resource_pid,
                               source_ownership_scope, base_source_version,
                               base_source_checksum, status, created_at, row_version
                        FROM ab_authoring_tenant_override
                        WHERE tenant_id = ? AND env_id = ?
                          AND source_resource_type = 'PAGE_SCHEMA'
                          AND source_resource_pid = ?
                        """,
                (resultSet, rowNumber) -> map(resultSet, inserted == 1),
                command.tenantId(), command.envId(), command.sourceResourcePid());
        if (rows.size() != 1) {
            throw new ResponseStatusException(CONFLICT, "authoring.ownership.override-conflict");
        }
        TenantOverrideRow row = rows.getFirst();
        if (!row.sourceOwnershipScope().equals(command.sourceOwnershipScope())) {
            throw new ResponseStatusException(CONFLICT, "authoring.ownership.source-scope-changed");
        }
        return row;
    }

    private TenantOverrideRow map(ResultSet resultSet, boolean created) throws SQLException {
        return new TenantOverrideRow(
                resultSet.getString("pid"),
                resultSet.getLong("tenant_id"),
                resultSet.getLong("env_id"),
                resultSet.getString("source_resource_pid"),
                resultSet.getString("source_ownership_scope"),
                resultSet.getLong("base_source_version"),
                resultSet.getString("base_source_checksum"),
                resultSet.getString("status"),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getLong("row_version"),
                created);
    }

    public record CreateTenantOverride(
            String overridePid,
            long tenantId,
            long envId,
            long actorUserId,
            String sourceResourcePid,
            String sourceOwnershipScope,
            long baseSourceVersion,
            String baseSourceChecksum) {
    }

    public record TenantOverrideRow(
            String pid,
            long tenantId,
            long envId,
            String sourceResourcePid,
            String sourceOwnershipScope,
            long baseSourceVersion,
            String baseSourceChecksum,
            String status,
            Instant createdAt,
            long rowVersion,
            boolean created) {
    }
}
