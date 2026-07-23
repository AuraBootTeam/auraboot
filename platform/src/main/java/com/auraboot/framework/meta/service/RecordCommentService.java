package com.auraboot.framework.meta.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Record Comment Service (GAP-123)
 * Manages comments for dynamic entity records.
 * Uses JdbcTemplate for direct SQL (bypasses DynamicDataMapper SELECT-only restriction).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordCommentService {

    private final JdbcTemplate jdbcTemplate;
    private final MetaModelService metaModelService;
    private final DynamicDataService dynamicDataService;

    private static final String TABLE = "ab_record_comment";

    /**
     * Enforce record-level visibility before exposing / mutating a record's comment thread.
     *
     * <p>Comments are keyed by {@code (modelCode, recordPid)}. When {@code modelCode} is a
     * registered model, delegate to {@link DynamicDataService#getById} which applies the
     * caller's row-ACL / field-mask and throws {@code Access denied} when the caller cannot
     * view the record — this closes the intra-tenant bypass where a user without data
     * permission on a record could still read or append its comments (SEC-20260723-04).
     * Comments attached to non-model targets (arbitrary {@code modelCode}) are not row-ACL
     * controlled and are left unchanged.
     */
    private void assertRecordVisible(String modelCode, String recordPid) {
        if (modelCode == null || recordPid == null
                || metaModelService.getModelDefinition(modelCode).isEmpty()) {
            return;
        }
        dynamicDataService.getById(modelCode, recordPid);
    }

    public List<Map<String, Object>> listComments(String modelCode, String recordPid) {
        assertRecordVisible(modelCode, recordPid);
        // JdbcTemplate bypasses the MyBatis tenant interceptor, so tenant_id must be scoped
        // explicitly here to keep comments isolated per tenant.
        Long tenantId = MetaContext.getCurrentTenantId();
        return jdbcTemplate.queryForList(
                "SELECT c.pid AS \"commentPid\", c.model_code, c.record_pid, c.content, c.mentions, "
                + "COALESCE(NULLIF(u.nick_name, ''), NULLIF(u.user_name, ''), u.email, 'User') AS \"actorName\", "
                + "c.created_at, c.updated_at, c.is_edited "
                + "FROM " + TABLE + " c "
                + "LEFT JOIN ab_user u ON u.id::text = c.created_by "
                + "WHERE c.tenant_id = ? AND c.model_code = ? AND c.record_pid = ? "
                + "AND (c.deleted_flag = FALSE OR c.deleted_flag IS NULL)"
                + " ORDER BY c.created_at DESC",
                tenantId, modelCode, recordPid);
    }

    public Map<String, Object> addComment(String modelCode, String recordPid, String content, String mentions) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Comment content cannot be empty");
        }
        assertRecordVisible(modelCode, recordPid);

        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        String commentPid = UniqueIdGenerator.generate();
        String actorName = resolveActorName(userId);

        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "INSERT INTO " + TABLE
                + " (pid, tenant_id, model_code, record_pid, content, mentions, created_by, created_at, updated_at, is_edited, deleted_flag) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), false, false) "
                + "RETURNING pid AS \"commentPid\", model_code, record_pid, content, mentions, created_at",
                commentPid, tenantId, modelCode, recordPid, content, mentions, userId);

        if (result.isEmpty()) throw new RuntimeException("Failed to insert comment");
        log.info("Comment added to {}/{} by user {}", modelCode, recordPid, userId);
        Map<String, Object> row = new LinkedHashMap<>(result.get(0));
        row.put("actorName", actorName);
        return row;
    }

    public Map<String, Object> editComment(String commentPid, String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("Comment content cannot be empty");
        }
        if (commentPid == null || commentPid.isBlank()) {
            throw new IllegalArgumentException("Invalid comment pid");
        }

        // Only the author may edit, scoped to their own tenant. JdbcTemplate bypasses the MyBatis
        // tenant interceptor, so without explicit tenant_id + created_by any authenticated user
        // could edit any comment by reference (IDOR). created_by is a varchar column, so bind
        // the user id as a String.
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();

        List<Map<String, Object>> result = jdbcTemplate.queryForList(
                "UPDATE " + TABLE
                + " SET content = ?, updated_at = NOW(), is_edited = true"
                + " WHERE pid = ? AND tenant_id = ? AND created_by = ?"
                + " AND (deleted_flag = FALSE OR deleted_flag IS NULL)"
                + " RETURNING pid AS \"commentPid\", content, updated_at, is_edited",
                content, commentPid, tenantId, String.valueOf(userId));

        if (result.isEmpty()) {
            throw new RuntimeException("Comment not found or not owned by current user: " + commentPid);
        }
        return result.get(0);
    }

    public void deleteComment(String commentPid) {
        if (commentPid == null || commentPid.isBlank()) {
            throw new IllegalArgumentException("Invalid comment pid");
        }
        // Author + tenant scoped (JdbcTemplate bypasses the tenant interceptor — see editComment).
        // created_by is a varchar column, so bind the user id as a String.
        Long tenantId = MetaContext.getCurrentTenantId();
        Long userId = MetaContext.getCurrentUserId();
        int affected = jdbcTemplate.update(
                "UPDATE " + TABLE + " SET deleted_flag = true"
                + " WHERE pid = ? AND tenant_id = ? AND created_by = ?",
                commentPid, tenantId, String.valueOf(userId));
        if (affected == 0) {
            throw new RuntimeException("Comment not found or not owned by current user: " + commentPid);
        }
        log.info("Comment {} deleted by user {}", commentPid, userId);
    }

    public List<Map<String, Object>> listActivity(String modelCode, String recordPid) {
        try {
            // Deny (return empty) when the caller cannot view the underlying record.
            assertRecordVisible(modelCode, recordPid);
            Long tenantId = MetaContext.getCurrentTenantId();
            return jdbcTemplate.queryForList(
                    "SELECT pid AS \"activityPid\", object_model, object_record, activity_type, subject, actor_name AS \"actorName\", occurred_at "
                    + "FROM ab_activity"
                    + " WHERE tenant_id = ? AND object_model = ? AND object_record = ?"
                    + " ORDER BY occurred_at DESC LIMIT 50",
                    tenantId, modelCode, recordPid);
        } catch (Exception e) {
            log.debug("Activity query failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String resolveActorName(Long userId) {
        if (userId == null) {
            return "User";
        }
        try {
            String actorName = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(NULLIF(nick_name, ''), NULLIF(user_name, ''), email, 'User') "
                    + "FROM ab_user WHERE id = ?",
                    String.class,
                    userId);
            return Optional.ofNullable(actorName).filter(name -> !name.isBlank()).orElse("User");
        } catch (Exception e) {
            return Optional.ofNullable(MetaContext.getCurrentUsername())
                    .filter(name -> !name.isBlank())
                    .orElse("User");
        }
    }
}
