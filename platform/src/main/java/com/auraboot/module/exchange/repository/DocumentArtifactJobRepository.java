package com.auraboot.module.exchange.repository;

import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/** Reads the optional platform-generic document artifact model without coupling to an industry plugin. */
@Repository
@RequiredArgsConstructor
public class DocumentArtifactJobRepository {

    private final JdbcTemplate jdbcTemplate;

    public List<Row> findRecentForOwner(Long tenantId, Long userId, int limit) {
        try {
            return jdbcTemplate.query("""
                    SELECT pid, ab_da_job_key, ab_da_definition_code, ab_da_definition_version,
                           ab_da_document_version, ab_da_business_version, ab_da_renderer_version,
                           ab_da_filename, ab_da_format, ab_da_download_url, ab_da_file_size,
                           ab_da_checksum_sha256, ab_da_status, created_at
                      FROM mt_ab_document_artifact
                     WHERE tenant_id = ? AND created_by = ? AND deleted_flag = FALSE
                     ORDER BY created_at DESC
                     LIMIT ?
                    """, (rs, rowNum) -> Row.builder()
                    .pid(rs.getString("pid"))
                    .jobKey(rs.getString("ab_da_job_key"))
                    .definitionCode(rs.getString("ab_da_definition_code"))
                    .definitionVersion(rs.getString("ab_da_definition_version"))
                    .documentVersion(integer(rs.getObject("ab_da_document_version")))
                    .businessVersion(rs.getString("ab_da_business_version"))
                    .rendererVersion(rs.getString("ab_da_renderer_version"))
                    .filename(rs.getString("ab_da_filename"))
                    .format(rs.getString("ab_da_format"))
                    .downloadUrl(rs.getString("ab_da_download_url"))
                    .fileSize(number(rs.getObject("ab_da_file_size")))
                    .checksumSha256(rs.getString("ab_da_checksum_sha256"))
                    .status(rs.getString("ab_da_status"))
                    .createdAt(instant(rs.getTimestamp("created_at")))
                    .build(), tenantId, userId, limit * 4);
        } catch (BadSqlGrammarException error) {
            if (isMissingArtifactTable(error)) {
                return List.of();
            }
            throw error;
        }
    }

    private static boolean isMissingArtifactTable(BadSqlGrammarException error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql
                    && ("42P01".equals(sql.getSQLState()) || "42S02".equals(sql.getSQLState()))) {
                return true;
            }
        }
        return error.getMessage() != null
                && error.getMessage().toLowerCase(java.util.Locale.ROOT)
                .contains("mt_ab_document_artifact");
    }

    private static Integer integer(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    @Data
    @Builder
    public static class Row {
        private String pid;
        private String jobKey;
        private String definitionCode;
        private String definitionVersion;
        private Integer documentVersion;
        private String businessVersion;
        private String rendererVersion;
        private String filename;
        private String format;
        private String downloadUrl;
        private Long fileSize;
        private String checksumSha256;
        private String status;
        private Instant createdAt;
    }
}
