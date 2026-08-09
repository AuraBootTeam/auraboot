package com.auraboot.framework.authoring.workspace;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.time.Instant;

/** Reads metadata-only fingerprints for resources referenced by an authoring draft. */
@Repository
public class AuthoringDependencyFingerprintRepository {

    private static final String MODEL_SQL = """
            SELECT m.pid, m.version, m.row_version, m.updated_at,
                   md5(COALESCE((jsonb_agg(jsonb_build_array(
                       f.pid, f.version, f.row_version, f.updated_at,
                       b.pid, b.updated_at, b.required, b.visible, b.editable,
                       b.field_order, b.alias_code, b.dict_override_code,
                       b.searchable, b.deleted_flag)
                       ORDER BY b.field_order, f.code)
                       FILTER (WHERE b.id IS NOT NULL))::text, '[]'))
                       AS component_fingerprint
            FROM ab_meta_model m
            LEFT JOIN ab_meta_model_field_binding b
              ON b.model_id = m.id AND b.tenant_id = m.tenant_id
             AND b.deleted_flag = FALSE
            LEFT JOIN ab_meta_field f
              ON f.id = b.field_id AND f.tenant_id = b.tenant_id
             AND f.is_current = TRUE AND f.deleted_flag = FALSE
            WHERE m.tenant_id = ? AND m.code = ?
              AND m.is_current = TRUE AND m.deleted_flag = FALSE
            GROUP BY m.id, m.pid, m.version, m.row_version, m.updated_at
            """;

    private static final String DICTIONARY_SQL = """
            SELECT d.pid, d.version, d.version AS row_version, d.updated_at,
                   md5(COALESCE((jsonb_agg(jsonb_build_array(
                       i.pid, i.updated_at, i.value, i.label, i.parent_value,
                       i.sort_no, i.status)
                       ORDER BY i.sort_no, i.value)
                       FILTER (WHERE i.id IS NOT NULL))::text, '[]'))
                       AS component_fingerprint
            FROM ab_dict d
            LEFT JOIN ab_dict_item i
              ON i.dict_id = d.id AND i.tenant_id = d.tenant_id
            WHERE d.tenant_id = ? AND d.code = ?
              AND d.is_current = TRUE AND d.deleted_flag = FALSE
            GROUP BY d.id, d.pid, d.version, d.updated_at
            """;

    private static final String COMMAND_SQL = """
            SELECT c.pid, c.version, c.row_version, c.updated_at,
                   md5(concat_ws('|', c.status, c.model_code,
                       c.input_schema::text, c.target_models::text,
                       c.execution_config::text,
                       COALESCE(bindings.binding_fingerprint, '[]')))
                       AS component_fingerprint
            FROM ab_command_definition c
            LEFT JOIN LATERAL (
                SELECT (jsonb_agg(jsonb_build_array(
                            b.pid, b.updated_at, b.rule_type, b.expression,
                            b.target_model, b.target_field, b.source_field,
                            b.handler_class, b.event_type, b.config, b.sequence,
                            b.enabled, b.status)
                            ORDER BY b.sequence, b.pid))::text AS binding_fingerprint
                FROM ab_binding_rule b
                WHERE b.tenant_id = c.tenant_id AND b.command_id = c.id
                  AND b.deleted_flag = FALSE
            ) bindings ON TRUE
            WHERE c.tenant_id = ? AND c.code = ?
              AND c.is_current = TRUE AND c.deleted_flag = FALSE
            """;

    private final JdbcTemplate jdbcTemplate;

    public AuthoringDependencyFingerprintRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ResourceFingerprint findCurrentModel(
            long tenantId, String modelCode, Duration timeout) {
        return findCurrentResource("MODEL", tenantId, modelCode, timeout, MODEL_SQL);
    }

    public ResourceFingerprint findCurrentDictionary(
            long tenantId, String dictCode, Duration timeout) {
        return findCurrentResource("DICTIONARY", tenantId, dictCode, timeout, DICTIONARY_SQL);
    }

    public ResourceFingerprint findCurrentCommand(
            long tenantId, String commandCode, Duration timeout) {
        return findCurrentResource("COMMAND", tenantId, commandCode, timeout, COMMAND_SQL);
    }

    private ResourceFingerprint findCurrentResource(
            String resourceType,
            long tenantId,
            String resourceCode,
            Duration timeout,
            String sql) {
        return jdbcTemplate.execute((ConnectionCallback<ResourceFingerprint>) connection -> {
            Savepoint savepoint = connection.getAutoCommit()
                    ? null : connection.setSavepoint("authoring_impact_probe");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, tenantId);
                statement.setString(2, resourceCode);
                statement.setQueryTimeout(
                        Math.max(1, (int) Math.ceil(timeout.toMillis() / 1000.0)));
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResourceFingerprint result = resultSet.next()
                            ? new ResourceFingerprint(
                                resourceType,
                                resourceCode,
                                resultSet.getString("pid"),
                                resultSet.getInt("version"),
                                resultSet.getInt("row_version"),
                                resultSet.getTimestamp("updated_at").toInstant(),
                                resultSet.getString("component_fingerprint"))
                            : null;
                    boolean duplicate = result != null && resultSet.next();
                    release(connection, savepoint);
                    if (duplicate) {
                        throw new DataIntegrityViolationException(
                                "Authoring impact dependency is not uniquely current");
                    }
                    return result;
                }
            } catch (SQLException exception) {
                rollback(connection, savepoint, exception);
                if ("57014".equals(exception.getSQLState())) {
                    throw new QueryTimeoutException("Authoring impact dependency query timed out",
                            exception);
                }
                throw new DataAccessResourceFailureException(
                        "Authoring impact dependency query failed", exception);
            }
        });
    }

    private void rollback(
            Connection connection,
            Savepoint savepoint,
            SQLException original) throws SQLException {
        if (savepoint != null) {
            try {
                connection.rollback(savepoint);
                connection.releaseSavepoint(savepoint);
            } catch (SQLException rollbackFailure) {
                original.addSuppressed(rollbackFailure);
                throw original;
            }
        }
    }

    private void release(Connection connection, Savepoint savepoint) throws SQLException {
        if (savepoint != null) {
            connection.releaseSavepoint(savepoint);
        }
    }

    public record ResourceFingerprint(
            String resourceType,
            String resourceCode,
            String pid,
            int version,
            int rowVersion,
            Instant updatedAt,
            String componentFingerprint) {
    }
}
