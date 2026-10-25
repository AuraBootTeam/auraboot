package com.auraboot.framework.workbench.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.exception.ConflictException;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.plugin.extension.IdentityDirectoryAccessor;
import com.auraboot.framework.workbench.entity.Announcement;
import com.auraboot.framework.workbench.mapper.AnnouncementLifecycleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

class AnnouncementLifecycleServiceTest {
    private final AnnouncementLifecycleMapper mapper = mock(AnnouncementLifecycleMapper.class);
    private final UserPermissionService permissions = mock(UserPermissionService.class);
    private final IdentityDirectoryAccessor identities = mock(IdentityDirectoryAccessor.class);
    private final AnnouncementLifecycleService service =
            new AnnouncementLifecycleService(mapper, mock(com.auraboot.framework.workbench.mapper.AnnouncementReminderMapper.class), permissions, identities, new ObjectMapper(), mock(AnnouncementPolicyService.class));

    @BeforeEach
    void setup() {
        MetaContext.setContext(1L, 7L, "user-7", "author");
        when(identities.findActiveTenantUser(1L, "user-7"))
                .thenReturn(
                        new IdentityDirectoryAccessor.UserIdentity(
                                7L, "user-7", "author", "Author"));
        when(permissions.hasPermission(7L, "announcement.announcement.edit")).thenReturn(true);
        when(mapper.isEnabledActor(7L, "user-7")).thenReturn(true);
    }

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    @Test
    void forbiddenStateInputNeverClaimsOrWrites() {
        assertThatThrownBy(
                        () ->
                                service.execute(
                                        "announcement:create_announcement",
                                        null,
                                        Map.of("status", "published"),
                                        "op-1",
                                        null))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).claim(any(), any(), any(), any(), any());
    }

    @Test
    void missingOperationIdentityNeverClaimsOrWrites() {
        assertThatThrownBy(
                        () ->
                                service.execute(
                                        "announcement:create_announcement",
                                        null,
                                        Map.of(),
                                        null,
                                        null))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).claim(any(), any(), any(), any(), any());
    }

    @Test
    void publicationCannotCreateVersionOrDelivery() {
        assertThatThrownBy(
                        () -> service.execute("announcement:publish", "n-1", Map.of(), "op-1", 1))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).claim(any(), any(), any(), any(), any());
    }

    @Test
    void ownershipAndPermissionAreIndependent() {
        Announcement row = draft();
        row.setOwnerUserId(8L);
        when(mapper.lock(1L, "n-1")).thenReturn(row);
        assertThatThrownBy(
                        () ->
                                service.execute(
                                        "announcement:update_announcement",
                                        "n-1",
                                        Map.of(),
                                        "op-1",
                                        1))
                .isInstanceOf(AccessDeniedException.class);
        verify(mapper, never()).claim(any(), any(), any(), any(), any());
    }

    @Test
    void staleEditorCannotOverwrite() {
        Announcement row = draft();
        row.setRowVersion(2);
        when(mapper.lock(1L, "n-1")).thenReturn(row);
        when(mapper.claim(any(), any(), any(), any(), any())).thenReturn(1);
        assertThatThrownBy(
                        () ->
                                service.execute(
                                        "announcement:update_announcement",
                                        "n-1",
                                        Map.of("title", "stale"),
                                        "op-1",
                                        1))
                .isInstanceOf(ConflictException.class);
        verify(mapper, never())
                .saveDraft(any(), any(), any(), anyInt(), any(), any(), any(), anyBoolean(), any());
        verify(mapper, never())
                .audit(any(), any(), any(), any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void incompleteCreateProducesDraftAndAtomicEvidence() {
        when(mapper.claim(any(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.insertDraft(
                        eq(1L),
                        eq(7L),
                        anyString(),
                        eq(""),
                        isNull(),
                        eq("normal"),
                        eq(false),
                        isNull()))
                .thenReturn(1);
        when(mapper.audit(
                        any(),
                        eq(1L),
                        eq(7L),
                        anyString(),
                        isNull(),
                        any(),
                        eq("op-1"),
                        isNull(),
                        eq(1)))
                .thenReturn(1);
        when(mapper.finish(eq(1L), eq(7L), any(), eq("op-1"), anyString(), any())).thenReturn(1);
        Map<String, Object> result =
                service.execute("announcement:create_announcement", null, Map.of(), "op-1", null);
        assertThat(result).containsEntry("status", "draft").containsEntry("row_version", 1);
        assertThat(result.get("pid")).isEqualTo(result.get("recordPid"));
        verify(mapper)
                .finish(
                        eq(1L),
                        eq(7L),
                        eq("announcement:create_announcement"),
                        eq("op-1"),
                        eq((String) result.get("pid")),
                        contains("draft"));
    }

    @Test
    void deletedTargetRemainsBoundToItsOwnerForReplayAuthorization() {
        Announcement row = draft();
        row.setDeletedFlag(true);
        row.setRowVersion(3);
        when(mapper.readTarget(1L, "n-1")).thenReturn(row);
        when(permissions.hasPermission(7L, "announcement.announcement.delete")).thenReturn(true);
        assertThat(service.authorizeTarget("announcement:delete_announcement", "n-1")).isEqualTo(3);
        row.setOwnerUserId(8L);
        assertThatThrownBy(() -> service.authorizeTarget("announcement:delete_announcement", "n-1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void disabledIdentityCannotRetrieveAnOldOperation() {
        when(mapper.isEnabledActor(7L, "user-7")).thenReturn(false);
        assertThatThrownBy(() -> service.authorizeTarget("announcement:update_announcement", "n-1"))
                .isInstanceOf(AccessDeniedException.class);
        verify(mapper, never()).readTarget(any(), any());
    }

    @Test
    void tenantWideAudiencePermissionDoesNotGrantAnotherAuthorsRecord() {
        Announcement row = draft();
        row.setOwnerUserId(8L);
        when(mapper.readTarget(1L, "n-1")).thenReturn(row);
        when(permissions.hasPermission(7L, "announcement.announcement.publish_all"))
                .thenReturn(true);
        assertThatThrownBy(() -> service.authorizeTarget("announcement:update_announcement", "n-1"))
                .isInstanceOf(AccessDeniedException.class);
        when(permissions.hasPermission(7L, "announcement.announcement.manage")).thenReturn(true);
        assertThat(service.authorizeTarget("announcement:update_announcement", "n-1")).isEqualTo(1);
    }

    private void successfulOperation() {
        when(mapper.claim(any(), any(), any(), any(), any())).thenReturn(1);
        when(mapper.audit(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(1);
        when(mapper.finish(any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void savePreservesTypedEditableInputsAndAdvancesRevision() {
        successfulOperation();
        when(mapper.lock(1L, "n-1")).thenReturn(draft());
        when(mapper.saveDraft(
                        any(), any(), any(), anyInt(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(1);
        var expiry = java.time.Instant.parse("2030-01-01T00:00:00Z");
        var result =
                service.execute(
                        "announcement:update_announcement",
                        "n-1",
                        Map.of(
                                "title",
                                "Revised",
                                "content",
                                "Body",
                                "announcement_priority",
                                "important",
                                "pinned",
                                true,
                                "expires_at",
                                expiry.toString()),
                        "save-1",
                        1);
        assertThat(result).containsEntry("row_version", 2).containsEntry("status", "draft");
        verify(mapper).saveDraft(1L, 7L, "n-1", 1, "Revised", "Body", "important", true, expiry);
    }

    @Test
    void deleteDraftRequiresItsIndependentActionAndNoHistory() {
        successfulOperation();
        when(permissions.hasPermission(7L, "announcement.announcement.delete")).thenReturn(true);
        when(mapper.lock(1L, "n-1")).thenReturn(draft());
        when(mapper.deleteDraft(1L, 7L, "n-1", 1)).thenReturn(1);
        assertThat(
                        service.execute(
                                "announcement:delete_announcement", "n-1", Map.of(), "delete-1", 1))
                .containsEntry("status", "deleted");
        when(mapper.versionCount(1L, "n-1")).thenReturn(1);
        assertThatThrownBy(
                        () ->
                                service.execute(
                                        "announcement:delete_announcement",
                                        "n-1",
                                        Map.of(),
                                        "delete-2",
                                        1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void withdrawClearsTheActiveVersionAndAdvancesBothControls() {
        successfulOperation();
        Announcement row = draft();
        row.setStatus("published");
        row.setCurrentVersionPid("v-1");
        row.setActiveVersionPid("v-1");
        when(permissions.hasPermission(7L, "announcement.announcement.withdraw")).thenReturn(true);
        when(mapper.lock(1L, "n-1")).thenReturn(row);
        when(mapper.withdraw(1L, 7L, "n-1", 1)).thenReturn(1);
        when(mapper.withdrawVersion(1L, "v-1")).thenReturn(1);
        assertThat(service.execute("announcement:archive", "n-1", Map.of(), "withdraw-1", 1))
                .containsEntry("status", "withdrawn")
                .containsEntry("row_version", 2);
    }

    @Test
    void revisionUsesTheFrozenContentWithoutRepublishing() {
        successfulOperation();
        Announcement row = draft();
        row.setStatus("withdrawn");
        row.setCurrentVersionPid("v-1");
        when(mapper.lock(1L, "n-1")).thenReturn(row);
        when(mapper.versionSnapshot(1L, "v-1"))
                .thenReturn("{\"title\":\"Frozen\",\"content\":\"Body\"}");
        when(mapper.revise(1L, 7L, "n-1", 1, "Frozen", "Body")).thenReturn(1);
        assertThat(service.execute("announcement:republish", "n-1", Map.of(), "revise-1", 1))
                .containsEntry("status", "draft");
        verify(mapper, never()).withdrawVersion(any(), any());
    }

    @Test
    void invalidDraftValuesCannotReachPersistence() {
        when(mapper.claim(any(), any(), any(), any(), any())).thenReturn(1);
        for (var input :
                java.util.List.of(
                        Map.<String, Object>of("title", "x".repeat(257)),
                        Map.<String, Object>of("title", 7),
                        Map.<String, Object>of("announcement_priority", "urgent"),
                        Map.<String, Object>of("pinned", "yes"),
                        Map.<String, Object>of("expires_at", "yesterday"))) {
            assertThatThrownBy(
                            () ->
                                    service.execute(
                                            "announcement:create_announcement",
                                            null,
                                            input,
                                            "invalid-1",
                                            null))
                    .isInstanceOf(BusinessException.class);
        }
        verify(mapper, never())
                .insertDraft(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void revokedActionAndMissingMembershipAreDenied() {
        when(permissions.hasPermission(7L, "announcement.announcement.edit")).thenReturn(false);
        assertThatThrownBy(() -> service.authorizeTarget("announcement:update_announcement", "n-1"))
                .isInstanceOf(AccessDeniedException.class);
        when(identities.findActiveTenantUser(1L, "user-7")).thenReturn(null);
        assertThatThrownBy(() -> service.authorizeTarget("announcement:update_announcement", "n-1"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingVersionAndNonDraftEditsAreRejected() {
        when(mapper.claim(any(), any(), any(), any(), any())).thenReturn(1);
        Announcement row = draft();
        when(mapper.lock(1L, "n-1")).thenReturn(row);
        assertThatThrownBy(
                        () ->
                                service.execute(
                                        "announcement:update_announcement",
                                        "n-1",
                                        Map.of(),
                                        "missing-1",
                                        null))
                .isInstanceOf(com.auraboot.framework.exception.CasVersionRequiredException.class);
        row.setStatus("published");
        assertThatThrownBy(
                        () ->
                                service.execute(
                                        "announcement:update_announcement",
                                        "n-1",
                                        Map.of(),
                                        "invalid-2",
                                        1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void pinningCannotSmuggleContentOrPolicyChanges() {
        assertThatThrownBy(() -> service.execute(AnnouncementLifecycleService.PIN, "n-1",
                Map.of("pinned", true, "content", "Changed after approval"), "pin-1", 1))
                .isInstanceOf(BusinessException.class);
        verify(mapper, never()).claim(any(), any(), any(), any(), any());
        verify(mapper, never()).setPinned(any(), any(), any(), anyInt(), anyBoolean());
    }

    private Announcement draft() {
        Announcement row = new Announcement();
        row.setPid("n-1");
        row.setTenantId(1L);
        row.setOwnerUserId(7L);
        row.setRowVersion(1);
        row.setStatus("draft");
        row.setAnnouncementPriority("normal");
        return row;
    }
}
