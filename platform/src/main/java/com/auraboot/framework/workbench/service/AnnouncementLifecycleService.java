package com.auraboot.framework.workbench.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.CasVersionRequiredException;
import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import lombok.RequiredArgsConstructor;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Transactional writer for drafts, withdrawal and revision. Publication uses the frozen release service.
 */
@Service
@RequiredArgsConstructor
public class AnnouncementLifecycleService {
    private static final Set<String> EDITABLE =
            Set.of("title", "content", "announcement_priority", "pinned", "expires_at", "policy");
    public static final String PIN = "announcement:set_pinned";
    private static final String CREATE = "announcement:create_announcement";
    private static final String UPDATE = "announcement:update_announcement";
    private static final String REVISE = "announcement:republish";
    private static final String WITHDRAW = "announcement:archive";
    private static final String DELETE = "announcement:delete_announcement";
    private final AnnouncementLifecycleMapper mapper;
    private final com.auraboot.framework.workbench.mapper.AnnouncementReminderMapper reminderPlans;
    private final UserPermissionService permissions;
    private final IdentityDirectoryAccessor identities;
    private final ObjectMapper objectMapper;
    private final AnnouncementPolicyService policies;

    @Transactional(readOnly = true)
    public long authorizeTarget(String command, String pid) {
        Long actor = requireActor(command);
        Announcement record = mapper.readTarget(MetaContext.getCurrentTenantId(), pid);
        authorizeRecord(record, actor);
        return record.getRowVersion();
    }

    private Long requireActor(String command) {
        Long tenant = MetaContext.getCurrentTenantId();
        Long actor = MetaContext.getCurrentUserId();
        if (tenant == null
                || actor == null
                || identities.findActiveTenantUser(tenant, MetaContext.getCurrentUserPid())
                        == null) {
            throw new AccessDeniedException("Active tenant membership required");
        }
        if (!mapper.isEnabledActor(actor, MetaContext.getCurrentUserPid())) {
            throw new AccessDeniedException("Active user identity required");
        }
        String permission =
                DELETE.equals(command) ? "delete" : WITHDRAW.equals(command) ? "withdraw" : "edit";
        if (!permissions.hasPermission(actor, "announcement.announcement." + permission)) {
            throw new AccessDeniedException("Announcement action is not permitted");
        }
        return actor;
    }

    @Transactional
    public Map<String, Object> execute(
            String command,
            String target,
            Map<String, Object> input,
            String operationId,
            Integer expectedVersion) {
        Long tenant = MetaContext.getCurrentTenantId();
        Long actor = requireActor(command);
        Map<String, Object> payload = input == null ? Map.of() : new LinkedHashMap<>(input);
        if (operationId == null || operationId.isBlank() || operationId.length() > 100)
            throw BusinessException.i18n("announcement.error.operation_required");
        for (String field : payload.keySet())
            if (!EDITABLE.contains(field) && !AnnouncementPolicyService.FORM_FIELDS.containsKey(field))
                throw BusinessException.i18n("announcement.error.protected_input");
        if (!Set.of(CREATE, UPDATE, DELETE, REVISE, WITHDRAW, PIN).contains(command))
            throw BusinessException.i18n("announcement.error.publication_unavailable");
        if (PIN.equals(command) && (!payload.keySet().equals(Set.of("pinned")) || !(payload.get("pinned") instanceof Boolean)))
            throw BusinessException.i18n("announcement.error.protected_input");
        Announcement record = null;
        if (!CREATE.equals(command)) {
            if (target == null || target.isBlank())
                throw BusinessException.i18n("announcement.error.not_found");
            record = mapper.lock(tenant, target);
            authorizeRecord(record, actor);
        }
        Map<String, Object> intent = new TreeMap<>();
        intent.put("target", target);
        intent.put("expectedVersion", expectedVersion);
        intent.put("payload", payload);
        String hash = hash(serialize(intent));
        if (mapper.claim(tenant, actor, command, operationId, hash) == 0) {
            Map<String, Object> prior = mapper.operation(tenant, actor, command, operationId);
            if (prior == null || !hash.equals(prior.get("request_hash")))
                throw new ConflictException(
                        "IDEMPOTENCY_CONFLICT", "Operation content changed", Map.of());
            if (prior.get("result") == null)
                throw new ConflictException("Operation has no committed result");
            return deserialize(prior.get("result").toString());
        }
        String policyJson = policies.fromForm(payload, target);
        String pid;
        Integer before = null;
        int after;
        if (CREATE.equals(command)) {
            pid = UniqueIdGenerator.generate();
            String title = text(payload, "title", "");
            String content = text(payload, "content", null);
            String importance = importance(payload, "normal");
            requireTitleLength(title);
            if (mapper.insertDraft(
                            tenant,
                            actor,
                            pid,
                            title,
                            content,
                            importance,
                            bool(payload, false),
                            expiry(payload, null))
                    != 1)
                throw new IllegalStateException("Announcement draft insert did not persist");
            after = 1;
        } else {
            before = record.getRowVersion();
            requireVersion(record, expectedVersion);
            if (Boolean.TRUE.equals(record.getDeletedFlag()))
                throw BusinessException.i18n("announcement.error.not_found");
            pid = record.getPid();
            if (PIN.equals(command)) {
                if (!"published".equals(record.getStatus()) || record.getActiveVersionPid() == null)
                    throw BusinessException.i18n("announcement.error.not_editable");
                if (mapper.setPinned(tenant, actor, pid, expectedVersion, (Boolean) payload.get("pinned")) != 1) throw conflict();
            } else if (WITHDRAW.equals(command)) {
                if (!payload.isEmpty()
                        || record.getActiveVersionPid() == null
                        || !"published".equals(record.getStatus()))
                    throw BusinessException.i18n("announcement.error.not_editable");
                if (mapper.withdraw(tenant, actor, pid, expectedVersion) != 1
                        || mapper.withdrawVersion(tenant, record.getActiveVersionPid()) != 1)
                    throw conflict();
                mapper.closeRecipientTasks(tenant,record.getActiveVersionPid());
                mapper.cancelRecipientDeliveries(tenant,record.getActiveVersionPid());
                reminderPlans.cancelPlan(tenant,record.getActiveVersionPid());
            } else if (REVISE.equals(command)) {
                if (!payload.isEmpty()
                        || !Set.of("withdrawn", "expired", "missed", "rejected", "approval_withdrawn", "approval_failed").contains(record.getStatus())
                        || record.getCurrentVersionPid() == null)
                    throw BusinessException.i18n("announcement.error.not_editable");
                Map<String, Object> snapshot =
                        deserialize(mapper.versionSnapshot(tenant, record.getCurrentVersionPid()));
                if (mapper.revise(
                                tenant,
                                actor,
                                pid,
                                expectedVersion,
                                (String) snapshot.get("title"),
                                (String) snapshot.get("content"))
                        != 1) throw conflict();
            } else if (!"draft".equals(record.getStatus())) {
                throw BusinessException.i18n("announcement.error.not_editable");
            } else if (DELETE.equals(command)) {
                if (!payload.isEmpty())
                    throw BusinessException.i18n("announcement.error.protected_input");
                if (mapper.versionCount(tenant, pid) > 0 || record.getCurrentVersionPid() != null)
                    throw BusinessException.i18n("announcement.error.history_protected");
                if (mapper.deleteDraft(tenant, actor, pid, expectedVersion) != 1) throw conflict();
            } else {
                String title = text(payload, "title", record.getTitle());
                requireTitleLength(title);
                if (mapper.saveDraft(
                                tenant,
                                actor,
                                pid,
                                expectedVersion,
                                title,
                                text(payload, "content", record.getContent()),
                                importance(payload, record.getAnnouncementPriority()),
                                bool(payload, Boolean.TRUE.equals(record.getPinned())),
                                expiry(payload, record.getExpiresAt()))
                        != 1) throw conflict();
            }
            after = before + 1;
        }
        if (policyJson != null) {
            if (mapper.saveSettings(tenant, pid, policyJson) != 1)
                throw new IllegalStateException("Announcement policy was not persisted");
            policies.linkDraftAttachments(pid,policyJson);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", pid);
        result.put("recordPid", pid);
        result.put("row_version", after);
        result.put("operationId", operationId);
        if (PIN.equals(command)) result.put("pinned", payload.get("pinned"));
        result.put(
                "status",
                DELETE.equals(command)
                        ? "deleted"
                        : WITHDRAW.equals(command) ? "withdrawn" : PIN.equals(command) ? record.getStatus() : "draft");
        if (mapper.audit(
                                UniqueIdGenerator.generate(),
                                tenant,
                                actor,
                                pid,
                                record == null ? null : record.getCurrentVersionPid(),
                                command,
                                operationId,
                                before,
                                after)
                        != 1
                || mapper.finish(tenant, actor, command, operationId, pid, serialize(result)) != 1)
            throw new IllegalStateException("Announcement operation evidence did not persist");
        return result;
    }

    private void authorizeRecord(Announcement record, Long actor) {
        if (record == null) throw BusinessException.i18n("announcement.error.not_found");
        if (!Objects.equals(record.getOwnerUserId(), actor)
                && !permissions.hasPermission(actor, "announcement.announcement.manage"))
            throw new AccessDeniedException("Announcement management scope denied");
    }

    private static void requireVersion(Announcement record, Integer expected) {
        if (expected == null)
            throw new CasVersionRequiredException(
                    "Announcement revision is required",
                    Map.of("modelCode", "ab_announcement", "recordPid", record.getPid()));
        if (!expected.equals(record.getRowVersion())) throw conflict();
    }

    private static ConflictException conflict() {
        return new ConflictException(
                "CAS_VERSION_CONFLICT", "Announcement was changed by another operation", Map.of());
    }

    private static void requireTitleLength(String title) {
        if (title != null && title.length() > 256)
            throw BusinessException.i18n("announcement.error.title_length");
    }

    private static String text(Map<String, Object> input, String key, String fallback) {
        if (!input.containsKey(key)) return fallback;
        Object value = input.get(key);
        if (value == null) return "title".equals(key) ? "" : null;
        if (!(value instanceof String text))
            throw BusinessException.i18n("announcement.error.invalid_input");
        return text;
    }

    private static String importance(Map<String, Object> payload, String fallback) {
        String value = text(payload, "announcement_priority", fallback);
        if (!Set.of("normal", "important").contains(value == null ? "" : value))
            throw BusinessException.i18n("announcement.error.invalid_input");
        return value;
    }

    private static boolean bool(Map<String, Object> payload, boolean fallback) {
        if (!payload.containsKey("pinned")) return fallback;
        if (!(payload.get("pinned") instanceof Boolean value))
            throw BusinessException.i18n("announcement.error.invalid_input");
        return value;
    }

    private static Instant expiry(Map<String, Object> payload, Instant fallback) {
        if (!payload.containsKey("expires_at")) return fallback;
        Object value = payload.get("expires_at");
        if (value == null || "".equals(value)) return null;
        if (value instanceof Instant instant) return instant;
        try {
            return Instant.parse(value.toString());
        } catch (java.time.format.DateTimeParseException error) {
            throw BusinessException.i18n("announcement.error.invalid_input");
        }
    }

    private String serialize(Object value) {
        try {
            return objectMapper
                    .copy()
                    .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid announcement JSON", error);
        }
    }

    private Map<String, Object> deserialize(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Invalid persisted announcement result", error);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
