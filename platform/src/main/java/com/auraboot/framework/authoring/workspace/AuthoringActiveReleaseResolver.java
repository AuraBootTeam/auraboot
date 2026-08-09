package com.auraboot.framework.authoring.workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Resolves the complete immutable snapshot behind one active release-channel pointer. */
@Component
public class AuthoringActiveReleaseResolver {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AuthoringRuntimeSnapshotSanitizer runtimeSanitizer;

    public AuthoringActiveReleaseResolver(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AuthoringRuntimeSnapshotSanitizer runtimeSanitizer) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.runtimeSanitizer = runtimeSanitizer;
    }

    public ActiveRelease findByResource(
            long tenantId,
            long envId,
            String resourceType,
            String resourcePid) {
        return jdbcTemplate.query("""
                        SELECT r.pid AS release_pid, c.row_version,
                               i.source_version, i.snapshot_checksum, i.snapshot::text
                        FROM ab_authoring_release_channel c
                        JOIN ab_authoring_release r
                          ON r.id = c.active_release_id
                         AND r.tenant_id = c.tenant_id AND r.env_id = c.env_id
                         AND r.status = 'ACTIVE'
                        JOIN ab_authoring_release_item i
                          ON i.release_id = r.id
                         AND i.tenant_id = c.tenant_id AND i.env_id = c.env_id
                         AND i.resource_type = c.resource_type
                         AND i.resource_pid = c.resource_pid
                        WHERE c.tenant_id = ? AND c.env_id = ?
                          AND c.resource_type = ? AND c.resource_pid = ?
                        """,
                resultSet -> resultSet.next()
                        ? new ActiveRelease(
                            resultSet.getString("release_pid"),
                            resultSet.getLong("row_version"),
                            resultSet.getLong("source_version"),
                            resultSet.getString("snapshot_checksum"),
                            runtimeSanitizer.sanitize(parse(resultSet.getString("snapshot"))))
                        : null,
                tenantId, envId, resourceType, resourcePid);
    }

    private JsonNode parse(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new DataRetrievalFailureException("Invalid authoring release snapshot", e);
        }
    }

    public record ActiveRelease(
            String releasePid,
            long channelVersion,
            long sourceVersion,
            String snapshotChecksum,
            JsonNode snapshot) {
    }
}
