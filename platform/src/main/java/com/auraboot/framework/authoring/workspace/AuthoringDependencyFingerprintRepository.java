package com.auraboot.framework.authoring.workspace;

import org.springframework.dao.DataAccessResourceFailureException;
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

    private final JdbcTemplate jdbcTemplate;

    public AuthoringDependencyFingerprintRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public ModelFingerprint findCurrentModel(long tenantId, String modelCode, Duration timeout) {
        return jdbcTemplate.execute((ConnectionCallback<ModelFingerprint>) connection -> {
            Savepoint savepoint = connection.getAutoCommit()
                    ? null : connection.setSavepoint("authoring_impact_probe");
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT m.pid, m.version, m.row_version, m.updated_at,
                           COALESCE(string_agg(
                               concat_ws(':', f.pid, f.version, f.row_version, f.updated_at,
                                   b.pid, b.updated_at, b.required, b.visible, b.editable), ','
                               ORDER BY b.field_order, f.code), '') AS field_fingerprint
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
                    """)) {
                statement.setLong(1, tenantId);
                statement.setString(2, modelCode);
                statement.setQueryTimeout(
                        Math.max(1, (int) Math.ceil(timeout.toMillis() / 1000.0)));
                try (ResultSet resultSet = statement.executeQuery()) {
                    ModelFingerprint result = resultSet.next()
                            ? new ModelFingerprint(
                                resultSet.getString("pid"),
                                resultSet.getInt("version"),
                                resultSet.getInt("row_version"),
                                resultSet.getTimestamp("updated_at").toInstant(),
                                resultSet.getString("field_fingerprint"))
                            : null;
                    release(connection, savepoint);
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

    public record ModelFingerprint(
            String pid,
            int version,
            int rowVersion,
            Instant updatedAt,
            String fieldFingerprint) {
    }
}
