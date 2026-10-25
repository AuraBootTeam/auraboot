package com.auraboot.framework.rag.service;

import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Management plane for explicit restricted/private KB grants.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseGrantService {

    private static final Set<String> SUBJECT_TYPES =
            Set.of("user", "member", "role", "digital_employee");
    private static final Set<String> PERMISSIONS = Set.of("read", "manage");

    private final KnowledgeBaseService knowledgeBaseService;
    private final JdbcTemplate jdbc;

    public record GrantRequest(
            String subjectType,
            String subjectId,
            String permission,
            Instant expiresAt) {
    }

    public List<Map<String, Object>> list(Long tenantId, String kbPid) {
        knowledgeBaseService.requireActiveKnowledgeBase(tenantId, kbPid);
        return jdbc.queryForList(
                "SELECT pid, subject_type, subject_id, permission, expires_at, "
                        + "created_at, created_by "
                        + "FROM ab_kb_access_grant "
                        + "WHERE tenant_id = ? AND kb_pid = ? "
                        + "ORDER BY subject_type, subject_id, permission",
                tenantId, kbPid);
    }

    @Transactional
    public String save(
            Long tenantId,
            Long userId,
            String kbPid,
            GrantRequest request) {
        knowledgeBaseService.requireActiveKnowledgeBase(tenantId, kbPid);
        String type = normalize(request.subjectType());
        String subjectId = request.subjectId() == null ? "" : request.subjectId().trim();
        String permission = normalize(request.permission());
        if (!SUBJECT_TYPES.contains(type)
                || subjectId.isBlank()
                || !PERMISSIONS.contains(permission)) {
            throw new BusinessException("Invalid knowledge-base grant");
        }
        String pid = UniqueIdGenerator.generate();
        jdbc.update(
                "INSERT INTO ab_kb_access_grant ("
                        + "pid, tenant_id, kb_pid, subject_type, subject_id, permission, "
                        + "expires_at, created_by"
                        + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (tenant_id, kb_pid, subject_type, subject_id, permission) "
                        + "DO UPDATE SET expires_at = EXCLUDED.expires_at",
                pid, tenantId, kbPid, type, subjectId, permission,
                request.expiresAt(), userId);
        return jdbc.queryForObject(
                "SELECT pid FROM ab_kb_access_grant "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND subject_type = ? "
                        + "AND subject_id = ? AND permission = ?",
                String.class, tenantId, kbPid, type, subjectId, permission);
    }

    public boolean delete(Long tenantId, String kbPid, String grantPid) {
        knowledgeBaseService.requireActiveKnowledgeBase(tenantId, kbPid);
        return jdbc.update(
                "DELETE FROM ab_kb_access_grant "
                        + "WHERE tenant_id = ? AND kb_pid = ? AND pid = ?",
                tenantId, kbPid, grantPid) == 1;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
