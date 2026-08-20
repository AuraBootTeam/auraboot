package com.auraboot.framework.authoring.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Actor-scoped persistence for durable, typed AI patch proposals. */
@Repository
public class AuthoringAiPatchProposalRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AuthoringAiPatchProposalRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void create(CreateProposal command) {
        jdbcTemplate.update("""
                INSERT INTO ab_authoring_ai_patch_proposal (
                    pid, tenant_id, env_id, actor_user_id, source_session_id,
                    source_session_pid, change_set_id, change_set_pid, page_pid,
                    base_revision, registry_checksum, proposal_hash, item_count,
                    items, decisions, aggregate_risk, aggregate_route, publish_policy)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
                """,
                command.pid(), command.tenantId(), command.envId(), command.actorUserId(),
                command.sourceSessionId(), command.sourceSessionPid(), command.changeSetId(),
                command.changeSetPid(), command.pagePid(), command.baseRevision(),
                command.registryChecksum(), command.proposalHash(), command.itemCount(),
                json(command.items()), json(command.decisions()), command.aggregateRisk(),
                command.aggregateRoute(), command.publishPolicy());
    }

    public ProposalRow find(
            long tenantId,
            long envId,
            long actorUserId,
            String sourceSessionPid,
            String proposalPid,
            boolean lock) {
        String lockClause = lock ? " FOR UPDATE" : "";
        List<ProposalRow> rows = jdbcTemplate.query("""
                        SELECT id, pid, tenant_id, env_id, actor_user_id, source_session_id,
                               source_session_pid, change_set_id, change_set_pid, page_pid,
                               base_revision, registry_checksum, proposal_hash, item_count,
                               items::text, decisions::text, aggregate_risk, aggregate_route,
                               publish_policy, status, result_revision, applied_at, rejected_at,
                               row_version, created_at
                        FROM ab_authoring_ai_patch_proposal
                        WHERE tenant_id = ? AND env_id = ? AND actor_user_id = ?
                          AND source_session_pid = ? AND pid = ?
                        """ + lockClause,
                (resultSet, rowNum) -> map(resultSet),
                tenantId, envId, actorUserId, sourceSessionPid, proposalPid);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean markApplied(ProposalRow row, long resultRevision, Instant appliedAt) {
        return jdbcTemplate.update("""
                UPDATE ab_authoring_ai_patch_proposal
                SET status = 'APPLIED', result_revision = ?, applied_at = ?,
                    row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND actor_user_id = ?
                  AND status = 'PROPOSED' AND row_version = ?
                """,
                resultRevision, Timestamp.from(appliedAt), row.id(), row.tenantId(), row.envId(),
                row.actorUserId(), row.rowVersion()) == 1;
    }

    public boolean markRejected(ProposalRow row, String reason, Instant rejectedAt) {
        return jdbcTemplate.update("""
                UPDATE ab_authoring_ai_patch_proposal
                SET status = 'REJECTED', rejection_reason = ?, rejected_at = ?,
                    row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND env_id = ? AND actor_user_id = ?
                  AND status = 'PROPOSED' AND row_version = ?
                """,
                reason, Timestamp.from(rejectedAt), row.id(), row.tenantId(), row.envId(),
                row.actorUserId(), row.rowVersion()) == 1;
    }

    private ProposalRow map(ResultSet resultSet) throws SQLException {
        return new ProposalRow(
                resultSet.getLong("id"),
                resultSet.getString("pid"),
                resultSet.getLong("tenant_id"),
                resultSet.getLong("env_id"),
                resultSet.getLong("actor_user_id"),
                resultSet.getLong("source_session_id"),
                resultSet.getString("source_session_pid"),
                resultSet.getLong("change_set_id"),
                resultSet.getString("change_set_pid"),
                resultSet.getString("page_pid"),
                resultSet.getLong("base_revision"),
                resultSet.getString("registry_checksum"),
                resultSet.getString("proposal_hash"),
                resultSet.getInt("item_count"),
                parse(resultSet.getString("items")),
                parse(resultSet.getString("decisions")),
                resultSet.getString("aggregate_risk"),
                resultSet.getString("aggregate_route"),
                resultSet.getString("publish_policy"),
                resultSet.getString("status"),
                nullableLong(resultSet, "result_revision"),
                nullableInstant(resultSet.getTimestamp("applied_at")),
                nullableInstant(resultSet.getTimestamp("rejected_at")),
                resultSet.getLong("row_version"),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            // CATCH: explicit persisted-payload validation; corrupt rows must fail closed.
            throw new DataRetrievalFailureException("Invalid authoring AI proposal JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            // CATCH: explicit serialization boundary; invalid proposal payloads must not persist.
            throw new IllegalArgumentException("Invalid authoring AI proposal payload", exception);
        }
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Instant nullableInstant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record CreateProposal(
            String pid,
            long tenantId,
            long envId,
            long actorUserId,
            long sourceSessionId,
            String sourceSessionPid,
            long changeSetId,
            String changeSetPid,
            String pagePid,
            long baseRevision,
            String registryChecksum,
            String proposalHash,
            int itemCount,
            JsonNode items,
            JsonNode decisions,
            String aggregateRisk,
            String aggregateRoute,
            String publishPolicy) {
    }

    public record ProposalRow(
            long id,
            String pid,
            long tenantId,
            long envId,
            long actorUserId,
            long sourceSessionId,
            String sourceSessionPid,
            long changeSetId,
            String changeSetPid,
            String pagePid,
            long baseRevision,
            String registryChecksum,
            String proposalHash,
            int itemCount,
            JsonNode items,
            JsonNode decisions,
            String aggregateRisk,
            String aggregateRoute,
            String publishPolicy,
            String status,
            Long resultRevision,
            Instant appliedAt,
            Instant rejectedAt,
            long rowVersion,
            Instant createdAt) {
    }
}
