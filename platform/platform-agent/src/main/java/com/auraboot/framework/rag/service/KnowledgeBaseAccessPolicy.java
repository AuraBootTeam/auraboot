package com.auraboot.framework.rag.service;

import com.auraboot.framework.agent.identity.ExecutionPrincipal;
import com.auraboot.framework.agent.identity.ExecutionPrincipalContext;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Resource gate for knowledge-base reads.
 *
 * <p>Tenant ownership and active lifecycle are mandatory. Tenant-visible KBs
 * are readable by authenticated actors in the tenant; restricted/private KBs
 * require ownership or an explicit user/member/role/digital-employee grant.
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseAccessPolicy {

    private final JdbcTemplate jdbc;

    public List<String> resolveReadable(
            Long tenantId,
            List<String> requestedKnowledgeBaseIds) {
        return resolve(
                tenantId,
                requestedKnowledgeBaseIds,
                Access.READ,
                true,
                true,
                true);
    }

    /**
     * Returns every knowledge base whose metadata the current actor may see.
     *
     * <p>Unlike retrieval auto-selection, this includes empty and disabled
     * knowledge bases so the management UI can still explain and repair them.
     * Unauthorized rows are filtered instead of turning the whole list into a
     * forbidden response.
     */
    public List<String> listReadableMetadata(Long tenantId) {
        return resolve(
                tenantId,
                List.of(),
                Access.READ,
                false,
                false,
                false);
    }

    public void requireReadable(Long tenantId, String knowledgeBaseId) {
        require(tenantId, knowledgeBaseId, Access.READ, false);
    }

    public void requireActiveReadable(Long tenantId, String knowledgeBaseId) {
        require(tenantId, knowledgeBaseId, Access.READ, true);
    }

    public void requireManage(Long tenantId, String knowledgeBaseId) {
        require(tenantId, knowledgeBaseId, Access.MANAGE, false);
    }

    public void requireActiveManage(Long tenantId, String knowledgeBaseId) {
        require(tenantId, knowledgeBaseId, Access.MANAGE, true);
    }

    private void require(
            Long tenantId,
            String knowledgeBaseId,
            Access access,
            boolean activeOnly) {
        if (knowledgeBaseId == null || knowledgeBaseId.isBlank()) {
            throw forbidden();
        }
        resolve(
                tenantId,
                List.of(knowledgeBaseId),
                access,
                activeOnly,
                false,
                true);
    }

    private List<String> resolve(
            Long tenantId,
            List<String> requestedKnowledgeBaseIds,
            Access access,
            boolean activeOnly,
            boolean requireChunksWhenUnspecified,
            boolean failClosed) {
        if (tenantId == null || tenantId <= 0L) {
            throw new BusinessException(ResponseCode.FORBIDDEN, "Tenant context is required");
        }
        List<String> requested = normalize(requestedKnowledgeBaseIds);
        String requestedClause = "";
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        if (!requested.isEmpty()) {
            requestedClause = " AND pid IN ("
                    + String.join(",", java.util.Collections.nCopies(requested.size(), "?"))
                    + ")";
            params.addAll(requested);
        } else if (requireChunksWhenUnspecified) {
            requestedClause = " AND chunk_count > 0";
        }
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT pid, COALESCE(visibility, 'tenant') AS visibility, created_by "
                        + "FROM ab_knowledge_base "
                        + "WHERE tenant_id = ? "
                        + (activeOnly ? "AND status = 'active' " : "")
                        + "AND (deleted_flag IS NULL OR deleted_flag = FALSE)"
                        + requestedClause,
                params.toArray());

        if (failClosed && !requested.isEmpty() && candidates.size() != requested.size()) {
            throw forbidden();
        }

        ActorSubjects subjects = actorSubjects();
        if (subjects.identities().isEmpty()) {
            throw forbidden();
        }
        List<String> restrictedPids = candidates.stream()
                .filter(row -> !"tenant".equals(String.valueOf(row.get("visibility"))))
                .map(row -> String.valueOf(row.get("pid")))
                .toList();
        Set<String> granted = loadGranted(tenantId, restrictedPids, subjects, access);

        List<String> accessible = new ArrayList<>();
        for (Map<String, Object> row : candidates) {
            String pid = String.valueOf(row.get("pid"));
            String visibility = String.valueOf(row.get("visibility"));
            Long createdBy = row.get("created_by") instanceof Number number
                    ? number.longValue()
                    : null;
            if ("tenant".equals(visibility)
                    || Objects.equals(createdBy, subjects.userId())
                    || granted.contains(pid)) {
                accessible.add(pid);
            }
        }
        if (failClosed && !requested.isEmpty() && accessible.size() != requested.size()) {
            throw forbidden();
        }
        return List.copyOf(accessible);
    }

    private Set<String> loadGranted(
            Long tenantId,
            List<String> kbPids,
            ActorSubjects subjects,
            Access access) {
        if (kbPids.isEmpty() || subjects.identities().isEmpty()) {
            return Set.of();
        }
        String kbPlaceholders =
                String.join(",", java.util.Collections.nCopies(kbPids.size(), "?"));
        String subjectPredicate = subjects.identities().stream()
                .map(ignored -> "(subject_type = ? AND subject_id = ?)")
                .collect(java.util.stream.Collectors.joining(" OR "));
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.addAll(kbPids);
        subjects.identities().forEach(identity -> {
            params.add(identity.type());
            params.add(identity.id());
        });
        List<String> rows = jdbc.queryForList(
                "SELECT DISTINCT kb_pid FROM ab_kb_access_grant "
                        + "WHERE tenant_id = ? AND kb_pid IN (" + kbPlaceholders + ") "
                        + (access == Access.MANAGE
                                ? "AND permission = 'manage' "
                                : "AND permission IN ('read', 'manage') ")
                        + "AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP) "
                        + "AND (" + subjectPredicate + ")",
                String.class,
                params.toArray());
        return Set.copyOf(rows);
    }

    private ActorSubjects actorSubjects() {
        ExecutionPrincipal principal =
                ExecutionPrincipalContext.current().orElse(null);
        Long userId = null;
        Long memberId = null;
        if (principal != null) {
            userId = principal.actorUserId();
            memberId = principal.actorMemberId();
        } else if (MetaContext.exists()) {
            userId = MetaContext.getCurrentUserId();
            memberId = MetaContext.getCurrentMemberId();
        }
        Set<Long> roles = principal != null
                ? principal.roleIds()
                : MetaContext.exists() ? MetaContext.getCurrentRoleIds() : Set.of();
        List<SubjectIdentity> identities = new ArrayList<>();
        if (userId != null && userId > 0L) {
            identities.add(new SubjectIdentity("user", String.valueOf(userId)));
        }
        if (memberId != null && memberId > 0L) {
            identities.add(new SubjectIdentity("member", String.valueOf(memberId)));
        }
        roles.stream().sorted().forEach(role ->
                identities.add(new SubjectIdentity("role", String.valueOf(role))));
        if (principal != null && principal.actorEmployeeId() != null
                && principal.actorEmployeeId() > 0L) {
            identities.add(new SubjectIdentity(
                    "digital_employee",
                    String.valueOf(principal.actorEmployeeId())));
        }
        return new ActorSubjects(userId, List.copyOf(identities));
    }

    private List<String> normalize(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String id : ids) {
            if (id != null && !id.isBlank()) {
                normalized.add(id.trim());
            }
        }
        return List.copyOf(normalized);
    }

    private BusinessException forbidden() {
        return new BusinessException(
                ResponseCode.FORBIDDEN,
                "One or more knowledge bases are not active or accessible");
    }

    private enum Access {
        READ,
        MANAGE
    }

    private record SubjectIdentity(String type, String id) {
    }

    private record ActorSubjects(
            Long userId,
            List<SubjectIdentity> identities) {
    }
}
