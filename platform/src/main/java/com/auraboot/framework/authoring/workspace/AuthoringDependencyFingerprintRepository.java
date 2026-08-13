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
                       f.pid, f.version, f.row_version, EXTRACT(EPOCH FROM f.updated_at),
                       b.pid, EXTRACT(EPOCH FROM b.updated_at),
                       b.required, b.visible, b.editable,
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
                       i.pid, EXTRACT(EPOCH FROM i.updated_at),
                       i.value, i.label, i.parent_value,
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
                            b.pid, EXTRACT(EPOCH FROM b.updated_at),
                            b.rule_type, b.expression,
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

    private static final String NAMED_QUERY_SQL = """
            SELECT q.pid, q.current_version AS version,
                   q.current_version AS row_version, q.updated_at,
                   md5(concat_ws('|', q.status, q.current_version,
                       q.from_sql, q.base_where::text, q.default_order::text,
                       q.parameter_schema::text, q.result_schema::text,
                       q.policy::text, q.connector_pid, q.connector_endpoint_code,
                       COALESCE(fields.field_fingerprint, '[]'),
                       COALESCE(version_snapshot.version_fingerprint, '[]')))
                       AS component_fingerprint
            FROM ab_named_query q
            LEFT JOIN LATERAL (
                SELECT (jsonb_agg(jsonb_build_array(
                            f.field_code, EXTRACT(EPOCH FROM f.updated_at),
                            f.column_expr, f.data_type,
                            f.operators, f.dict_code, f.sortable, f.searchable,
                            f.ui_component, f.linked_field, f.required,
                            f.sort_order, f.ui_config)
                            ORDER BY f.sort_order, f.field_code))::text AS field_fingerprint
                FROM ab_named_query_field f
                WHERE f.tenant_id = q.tenant_id AND f.query_code = q.code
            ) fields ON TRUE
            LEFT JOIN LATERAL (
                SELECT (jsonb_build_array(
                            v.pid, v.version_no, v.status, v.published_at,
                            md5(concat_ws('|', v.from_sql, v.base_where::text,
                                v.default_order::text, v.fields_snapshot::text,
                                v.policy::text))))::text AS version_fingerprint
                FROM ab_named_query_version v
                WHERE v.tenant_id = q.tenant_id AND v.query_code = q.code
                  AND v.version_no = q.current_version
                ORDER BY v.id DESC
                LIMIT 1
            ) version_snapshot ON TRUE
            WHERE q.tenant_id = ? AND q.code = ?
              AND q.status IN ('draft', 'testing', 'published')
            """;

    private static final String PAGE_SQL = """
            WITH effective_page AS (
                SELECT p.*
                FROM ab_page_schema p
                WHERE p.tenant_id = ? AND p.page_key = ?
                  AND p.is_current = TRUE AND p.deleted_flag = FALSE
                  AND p.status = 'published'
                  AND (p.env_id = ? OR p.env_id IS NULL)
                ORDER BY CASE WHEN p.env_id = ? THEN 0 ELSE 1 END
                LIMIT 1
            )
            SELECT p.pid, p.version, p.row_version, p.updated_at,
                   md5(concat_ws('|', p.status, p.schema_version,
                       p.model_code, p.kind, p.profile, p.layout::text,
                       p.blocks::text, p.meta_info::text, p.plugin_pid,
                       channel.pid, channel.row_version,
                       active_release.pid, active_release.manifest_checksum))
                       AS component_fingerprint
            FROM effective_page p
            LEFT JOIN ab_authoring_release_channel channel
              ON channel.tenant_id = p.tenant_id AND channel.env_id = ?
             AND channel.resource_type = 'PAGE_SCHEMA'
             AND channel.resource_pid = p.pid
            LEFT JOIN ab_authoring_release active_release
              ON active_release.id = channel.active_release_id
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

    public ResourceFingerprint findCurrentNamedQuery(
            long tenantId, String queryCode, Duration timeout) {
        return findCurrentResource(
                "NAMED_QUERY", tenantId, queryCode, timeout, NAMED_QUERY_SQL);
    }

    public ResourceFingerprint findCurrentPage(
            long tenantId, long envId, String pageKey, Duration timeout) {
        return findCurrentResource(
                "PAGE", tenantId, pageKey, timeout, PAGE_SQL,
                statement -> {
                    statement.setLong(3, envId);
                    statement.setLong(4, envId);
                    statement.setLong(5, envId);
                });
    }

    private ResourceFingerprint findCurrentResource(
            String resourceType,
            long tenantId,
            String resourceCode,
            Duration timeout,
            String sql) {
        return findCurrentResource(
                resourceType, tenantId, resourceCode, timeout, sql, statement -> { });
    }

    private ResourceFingerprint findCurrentResource(
            String resourceType,
            long tenantId,
            String resourceCode,
            Duration timeout,
            String sql,
            StatementBinder extraBinder) {
        return jdbcTemplate.execute((ConnectionCallback<ResourceFingerprint>) connection -> {
            Savepoint savepoint = connection.getAutoCommit()
                    ? null : connection.setSavepoint("authoring_impact_probe");
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, tenantId);
                statement.setString(2, resourceCode);
                extraBinder.bind(statement);
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

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
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
