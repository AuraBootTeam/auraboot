package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.mapper.RecordShareMapper;
import com.auraboot.framework.permission.service.RecordShareService.MemberShare;
import com.auraboot.framework.rbac.service.UserRoleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RecordShareServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
class RecordShareServiceImplTest {

    @Mock
    private RecordShareMapper recordShareMapper;

    @Mock
    private UserRoleService userRoleService;


    private RecordShareServiceImpl service;

    @BeforeEach
    void setUpMetaContext() {
        MockitoAnnotations.openMocks(this);
        service = new RecordShareServiceImpl(
                recordShareMapper, userRoleService, new RecordSharePresenceCache(recordShareMapper));
        MetaContext.setContext(100L, 99L, "user-pid", "tester");
        // Existing cases exercise the original subject/action authorization branches.
        lenient().when(recordShareMapper.hasActiveSharesByRecordPid(any(), any(), any(), any()))
                .thenReturn(true);
    }

    @AfterEach
    void clearMetaContext() {
        MetaContext.clear();
    }

    @Test
    void shareRecordInsertsShareEntry() {
        Instant expires = Instant.now().plusSeconds(3600);

        service.shareRecord(100L, "model.user", 10L, "member", 5L, "read", expires);

        ArgumentCaptor<RecordShare> captor = ArgumentCaptor.forClass(RecordShare.class);
        verify(recordShareMapper).insert(captor.capture());

        RecordShare share = captor.getValue();
        assertThat(share.getTenantId()).isEqualTo(100L);
        assertThat(share.getResourceCode()).isEqualTo("model.user");
        assertThat(share.getRecordId()).isEqualTo(10L);
        assertThat(share.getSubjectType()).isEqualTo("member");
        assertThat(share.getSubjectId()).isEqualTo(5L);
        assertThat(share.getPermissionMask()).isEqualTo("read");
        assertThat(share.getExpiresAt()).isEqualTo(expires);
        assertThat(share.getPid()).isNotBlank();
        assertThat(share.getCreatedAt()).isNotNull();
        assertThat(share.getCreatedBy()).isEqualTo(99L);
    }

    @Test
    void shareRecordByPidStoresPublicRecordAndSubjectPids() {
        Instant expires = Instant.now().plusSeconds(3600);

        service.shareRecordByPid(100L, "model.user", "rec_10", "member", "mem_5", "read", expires);

        ArgumentCaptor<RecordShare> captor = ArgumentCaptor.forClass(RecordShare.class);
        verify(recordShareMapper).upsertByPublicPids(captor.capture());

        RecordShare share = captor.getValue();
        assertThat(share.getTenantId()).isEqualTo(100L);
        assertThat(share.getResourceCode()).isEqualTo("model.user");
        assertThat(share.getRecordId()).isNull();
        assertThat(share.getRecordPid()).isEqualTo("rec_10");
        assertThat(share.getSubjectType()).isEqualTo("member");
        assertThat(share.getSubjectId()).isNull();
        assertThat(share.getSubjectPid()).isEqualTo("mem_5");
        assertThat(share.getPermissionMask()).isEqualTo("read");
        assertThat(share.getExpiresAt()).isEqualTo(expires);
        assertThat(share.getPid()).isNotBlank();
        assertThat(share.getCreatedAt()).isNotNull();
        assertThat(share.getCreatedBy()).isEqualTo(99L);
    }

    @Test
    void sharesMembersUsingOneBatchWithTheOriginalActorAndPermissionMasks() {
        service.shareMembersByPids(100L, List.of(
                new MemberShare("model.customer", "record-1", 5L, "member-5", "read,update"),
                new MemberShare("model.history", "record-2", 6L, "member-6", "read")));

        var captor = ArgumentCaptor.<List<RecordShare>>captor();
        verify(recordShareMapper).upsertByPublicPidsBatch(captor.capture());
        verify(recordShareMapper, never()).upsertByPublicPids(any());
        var rows = captor.getValue();
        assertThat(rows).extracting(RecordShare::getResourceCode).containsExactly("model.customer", "model.history");
        assertThat(rows).extracting(RecordShare::getRecordPid).containsExactly("record-1", "record-2");
        assertThat(rows).extracting(RecordShare::getSubjectPid).containsExactly("member-5", "member-6");
        assertThat(rows).extracting(RecordShare::getSubjectId).containsExactly(5L, 6L);
        assertThat(rows).extracting(RecordShare::getPermissionMask).containsExactly("read,update", "read");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getTenantId()).isEqualTo(100L);
            assertThat(row.getCreatedBy()).isEqualTo(99L);
            assertThat(row.getExpiresAt()).isNull();
            assertThat(row.getSubjectType()).isEqualTo("member");
        });
    }

    @Test
    void shareRecordByPidRejectsPastExpiry() {
        assertThatThrownBy(() -> service.shareRecordByPid(
                100L,
                "model.user",
                "rec_10",
                "member",
                "mem_5",
                "read",
                Instant.now().minusSeconds(1)))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageContaining("future");

        verify(recordShareMapper, never()).upsertByPublicPids(any());
    }

    @Test
    void shareRecordDefaultsBlankPermissionMaskToRead() {
        service.shareRecord(100L, "model.user", 10L, "member", 5L, "  ", null);

        ArgumentCaptor<RecordShare> captor = ArgumentCaptor.forClass(RecordShare.class);
        verify(recordShareMapper).insert(captor.capture());

        assertThat(captor.getValue().getPermissionMask()).isEqualTo("read");
    }

    @Test
    void unshareRecordDelegatesToMapper() {
        when(recordShareMapper.deleteShare(100L, "model.user", 10L, "member", 5L)).thenReturn(1);

        service.unshareRecord(100L, "model.user", 10L, "member", 5L);

        verify(recordShareMapper).deleteShare(100L, "model.user", 10L, "member", 5L);
    }

    @Test
    void isSharedReturnsTrueWhenDirectShareExists() {
        when(recordShareMapper.countByRecordAndUser(
                eq(100L), eq("model.user"), eq(10L), eq(5L), eq("read"), any()))
                .thenReturn(1);

        boolean result = service.isShared(100L, "model.user", 10L, 5L, "READ");

        assertThat(result).isTrue();
        verify(userRoleService, never()).getRoleIdsByMemberIdAndTenantId(anyLong(), anyLong());
    }

    @Test
    void isSharedByPidReturnsTrueWhenDirectSubjectPidShareExists() {
        when(recordShareMapper.findShareCandidatesByRecordPid(
                eq(100L), eq("model.user"), eq("rec_10"), eq(5L), eq("mem_5"), eq("read"), any()))
                .thenReturn(new RecordShareMapper.ShareCandidates(true, false));

        boolean result = service.isSharedByPid(100L, "model.user", "rec_10", 5L, "mem_5", "read");

        assertThat(result).isTrue();
        verify(userRoleService, never()).getRoleIdsByMemberIdAndTenantId(anyLong(), anyLong());
    }

    @Test
    void absentActiveSharesRejectWithoutSubjectOrRoleQueries() {
        when(recordShareMapper.hasActiveSharesByRecordPid(eq(100L), eq("model.user"), eq("rec_10"), any()))
                .thenReturn(false);

        assertThat(service.isSharedByPid(100L, "model.user", " rec_10 ", 5L, "mem_5", "READ")).isFalse();

        verify(recordShareMapper).hasActiveSharesByRecordPid(eq(100L), eq("model.user"), eq("rec_10"), any());
        verify(recordShareMapper, never()).findShareCandidatesByRecordPid(any(), any(), any(), any(), any(), any(), any());
        verify(recordShareMapper, never()).countByRecordPidAndRoles(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(userRoleService);
    }

    @Test
    void aFreshGrantIsObservedImmediatelyBecauseTheGrantEvictsThePresenceMemo() {
        when(recordShareMapper.hasActiveSharesByRecordPid(eq(100L), eq("model.user"), eq("rec_10"), any()))
                .thenReturn(false, true);
        lenient().when(recordShareMapper.findShareCandidatesByRecordPid(
                eq(100L), eq("model.user"), eq("rec_10"), eq(5L), eq("mem_5"), eq("read"), any()))
                .thenReturn(new RecordShareMapper.ShareCandidates(true, false));

        assertThat(service.isSharedByPid(100L, "model.user", "rec_10", 5L, "mem_5", "read")).isFalse();
        // The grant path itself (shareRecordByPid) evicts the presence memo, so the very
        // next independent check observes the fresh grant without waiting out the TTL.
        service.shareRecordByPid(100L, "model.user", "rec_10", "member", 5L, "mem_5", "read", null);
        assertThat(service.isSharedByPid(100L, "model.user", "rec_10", 5L, "mem_5", "read")).isTrue();
        verify(recordShareMapper, times(2)).hasActiveSharesByRecordPid(eq(100L), eq("model.user"), eq("rec_10"), any());
        verify(recordShareMapper).findShareCandidatesByRecordPid(
                eq(100L), eq("model.user"), eq("rec_10"), eq(5L), eq("mem_5"), eq("read"), any());
        verifyNoInteractions(userRoleService);
    }

    @Test
    void pidOnlySubjectRetainsItsOriginalDirectGrantAndTheSameExpiryInstant() {
        when(recordShareMapper.findShareCandidatesByRecordPid(
                eq(100L), eq("model.user"), eq("rec_10"), isNull(), eq("mem_5"), eq("read"), any()))
                .thenReturn(new RecordShareMapper.ShareCandidates(true, false));

        assertThat(service.isSharedByPid(100L, "model.user", "rec_10", null, " mem_5 ", "READ")).isTrue();

        ArgumentCaptor<Instant> now = ArgumentCaptor.forClass(Instant.class);
        verify(recordShareMapper).hasActiveSharesByRecordPid(eq(100L), eq("model.user"), eq("rec_10"), now.capture());
        verify(recordShareMapper).findShareCandidatesByRecordPid(
                100L, "model.user", "rec_10", null, "mem_5", "read", now.getValue());
        verifyNoInteractions(userRoleService);
    }

    @Test
    void absentSubjectRetainsItsZeroQueryRejection() {
        assertThat(service.isSharedByPid(100L, "model.user", "rec_10", null, " ", "read")).isFalse();
        verifyNoInteractions(recordShareMapper, userRoleService);
    }

    @Test
    void activeSharesNeverGrantAnUnmatchedSubjectOrAction() {
        when(recordShareMapper.findShareCandidatesByRecordPid(
                eq(100L), eq("model.user"), eq("rec_10"), eq(5L), eq("mem_5"), eq("update"), any()))
                .thenReturn(new RecordShareMapper.ShareCandidates(false, false));

        assertThat(service.isSharedByPid(100L, "model.user", "rec_10", 5L, "mem_5", "update")).isFalse();

        verifyNoInteractions(userRoleService);
        verify(recordShareMapper, never()).countByRecordPidAndRoles(any(), any(), any(), any(), any(), any());
    }

    @Test
    void legacyMemberAndCanonicalRoleGrantsRetainTheirIndependentChecks() {
        when(recordShareMapper.findShareCandidatesByRecordPid(
                eq(100L), eq("model.user"), eq("rec_10"), eq(5L), isNull(), eq("read"), any())).thenReturn(
                        new RecordShareMapper.ShareCandidates(true, false),
                        new RecordShareMapper.ShareCandidates(false, true));
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(7L));
        when(recordShareMapper.countByRecordPidAndRoles(
                eq(100L), eq("model.user"), eq("rec_10"), eq(List.of(7L)), eq("read"), any())).thenReturn(1);

        assertThat(service.isSharedByPid(100L, "model.user", "rec_10", 5L, null, "read")).isTrue();
        verifyNoInteractions(userRoleService);
        assertThat(service.isSharedByPid(100L, "model.user", "rec_10", 5L, null, "read")).isTrue();
        verify(userRoleService).getRoleIdsByMemberIdAndTenantId(5L, 100L);
        verify(recordShareMapper).countByRecordPidAndRoles(
                eq(100L), eq("model.user"), eq("rec_10"), eq(List.of(7L)), eq("read"), any());
    }

    @Test
    void roleCandidateAloneDoesNotGrantAccess() {
        when(recordShareMapper.findShareCandidatesByRecordPid(
                eq(100L), eq("model.user"), eq("rec_10"), eq(5L), eq("mem_5"), eq("read"), any()))
                .thenReturn(new RecordShareMapper.ShareCandidates(false, true));
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(7L));
        when(recordShareMapper.countByRecordPidAndRoles(
                eq(100L), eq("model.user"), eq("rec_10"), eq(List.of(7L)), eq("read"), any()))
                .thenReturn(0);

        assertThat(service.isSharedByPid(100L, "model.user", "rec_10", 5L, "mem_5", "read")).isFalse();
    }

    @Test
    void aGrantRevokedAfterAnEarlierPositiveCheckIsNotCached() {
        when(recordShareMapper.findShareCandidatesByRecordPid(
                eq(100L), eq("model.user"), eq("rec_10"), eq(5L), eq("mem_5"), eq("read"), any()))
                .thenReturn(new RecordShareMapper.ShareCandidates(true, false),
                        new RecordShareMapper.ShareCandidates(false, false));

        assertThat(service.isSharedByPid(100L, "model.user", "rec_10", 5L, "mem_5", "read")).isTrue();
        // The presence memo may still answer "has shares", but the per-subject queries stay
        // uncached — a revoked share denies on the very next check regardless of the memo.
        assertThat(service.isSharedByPid(100L, "model.user", "rec_10", 5L, "mem_5", "read")).isFalse();
    }

    @Test
    void existenceQueryFailurePropagatesInsteadOfBecomingAbsence() {
        IllegalStateException unavailable = new IllegalStateException("share store unavailable");
        when(recordShareMapper.hasActiveSharesByRecordPid(eq(100L), eq("model.user"), eq("rec_10"), any()))
                .thenThrow(unavailable);
        assertThatThrownBy(() -> service.isSharedByPid(100L, "model.user", "rec_10", 5L, "mem_5", "read"))
                .isSameAs(unavailable);
        verifyNoInteractions(userRoleService);
    }

    @Test
    void originalSubjectQueryFailureStillPropagatesAfterPositiveExistence() {
        IllegalStateException unavailable = new IllegalStateException("subject store unavailable");
        when(recordShareMapper.findShareCandidatesByRecordPid(
                eq(100L), eq("model.user"), eq("rec_10"), eq(5L), eq("mem_5"), eq("read"), any()))
                .thenThrow(unavailable);
        assertThatThrownBy(() -> service.isSharedByPid(100L, "model.user", "rec_10", 5L, "mem_5", "read"))
                .isSameAs(unavailable);
    }

    @Test
    void isSharedFallsBackToRolesWhenNoDirectShare() {
        when(recordShareMapper.countByRecordAndUser(
                eq(100L), eq("model.user"), eq(10L), eq(5L), eq("update"), any()))
                .thenReturn(0);
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(7L, 8L));
        when(recordShareMapper.countByRecordAndRoles(
                eq(100L), eq("model.user"), eq(10L), eq(List.of(7L, 8L)), eq("update"), any()))
                .thenReturn(1);

        assertThat(service.isShared(100L, "model.user", 10L, 5L, "update")).isTrue();
    }

    @Test
    void isSharedReturnsFalseWhenNoDirectAndNoRoles() {
        when(recordShareMapper.countByRecordAndUser(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), any()))
                .thenReturn(0);
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of());

        assertThat(service.isShared(100L, "model.user", 10L, 5L, "read")).isFalse();
        verify(recordShareMapper, never()).countByRecordAndRoles(
                anyLong(), anyString(), anyLong(), anyList(), anyString(), any());
    }

    @Test
    void isSharedReturnsFalseWhenRolesNull() {
        when(recordShareMapper.countByRecordAndUser(
                anyLong(), anyString(), anyLong(), anyLong(), anyString(), any()))
                .thenReturn(0);
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(null);

        assertThat(service.isShared(100L, "model.user", 10L, 5L, "read")).isFalse();
    }

    @Test
    void getSharedRecordIdsPassesEmptyListWhenNoRoles() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(null);
        when(recordShareMapper.findSharedRecordIds(
                eq(100L), eq("model.user"), eq(5L), eq(List.of()), eq("read"), any()))
                .thenReturn(List.of(1L, 2L));

        List<Long> ids = service.getSharedRecordIds(100L, "model.user", 5L, "read");

        assertThat(ids).containsExactly(1L, 2L);
    }

    @Test
    void getSharedRecordIdsPassesRolesWhenAvailable() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(7L));
        when(recordShareMapper.findSharedRecordIds(
                eq(100L), eq("model.user"), eq(5L), eq(List.of(7L)), eq("read"), any()))
                .thenReturn(List.of(3L));

        assertThat(service.getSharedRecordIds(100L, "model.user", 5L, "read")).containsExactly(3L);
    }

    @Test
    void getSharedRecordPidsIncludesDirectPidAndRoleGrantsForRequestedAction() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(7L));
        when(recordShareMapper.findSharedRecordPids(
                eq(100L), eq("model.user"), eq(5L), eq("mem_5"), eq(List.of(7L)), eq("update"), any()))
                .thenReturn(List.of("rec_3"));

        assertThat(service.getSharedRecordPids(100L, "model.user", 5L, " mem_5 ", "UPDATE"))
                .containsExactly("rec_3");
    }

    @Test
    void listByRecordDelegatesToMapper() {
        RecordShare share = new RecordShare();
        when(recordShareMapper.findByRecord(eq(100L), eq("model.user"), eq(10L), any())).thenReturn(List.of(share));

        assertThat(service.listByRecord(100L, "model.user", 10L)).hasSize(1);
    }

    @Test
    void listByRecordPidDelegatesToMapper() {
        RecordShare share = new RecordShare();
        when(recordShareMapper.findByRecordPid(eq(100L), eq("model.user"), eq("rec_10"), any())).thenReturn(List.of(share));

        assertThat(service.listByRecordPid(100L, "model.user", "rec_10")).hasSize(1);
    }

    @Test
    void listByRecordPidForManagementIncludesMapperResultWithoutExpiryFiltering() {
        RecordShare expired = new RecordShare();
        expired.setExpiresAt(Instant.now().minusSeconds(60));
        when(recordShareMapper.findByRecordPidForManagement(
                eq(100L), eq("model.user"), eq("rec_10"), any()))
                .thenReturn(List.of(expired));

        assertThat(service.listByRecordPidForManagement(100L, "model.user", "rec_10"))
                .containsExactly(expired);
    }

    @Test
    void updateByPidRenewsExistingShareWithoutSubjectOrRecordIdentifiers() {
        Instant renewedUntil = Instant.now().plusSeconds(3600);
        when(recordShareMapper.updatePolicyByPidInTenant(
                eq(100L),
                eq("share-99"),
                eq("read,update"),
                eq(renewedUntil)))
                .thenReturn(1);

        service.updateByPid(100L, " share-99 ", "read, update", renewedUntil);

        verify(recordShareMapper).updatePolicyByPidInTenant(
                eq(100L),
                eq("share-99"),
                eq("read,update"),
                eq(renewedUntil));
    }

    @Test
    void updateByPidRejectsPastExpiryWithoutDatabaseMutation() {
        assertThatThrownBy(() -> service.updateByPid(
                100L, "share-99", "read", Instant.now().minusSeconds(1)))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageContaining("future");

        verify(recordShareMapper, never()).updatePolicyByPidInTenant(
                anyLong(), anyString(), anyString(), any());
    }

    @Test
    void removeByPidThrowsWhenNotFound() {
        when(recordShareMapper.findByPidInTenant(100L, "share-99")).thenReturn(null);

        assertThatThrownBy(() -> service.removeByPid(100L, "share-99"))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void removeByPidDoesNotResolveCrossTenantShare() {
        when(recordShareMapper.findByPidInTenant(100L, "share-99")).thenReturn(null);

        assertThatThrownBy(() -> service.removeByPid(100L, "share-99"))
                .isInstanceOf(RootUnCheckedException.class);

        verify(recordShareMapper, never()).deleteByPidInTenant(anyLong(), anyString());
    }

    @Test
    void removeByPidDeletesWhenTenantMatches() {
        RecordShare share = new RecordShare();
        share.setPid("share-99");
        share.setTenantId(100L);
        share.setResourceCode("model.user");
        share.setRecordPid("rec-10");
        when(recordShareMapper.findByPidInTenant(100L, "share-99")).thenReturn(share);

        service.removeByPid(100L, "share-99");

        verify(recordShareMapper).deleteByPidInTenant(100L, "share-99");
    }

    @Test
    void removeByPidsDeletesEachUniqueTenantScopedShare() {
        var normalized = new String[]{"share-1", "share-2"};
        when(recordShareMapper.deleteByPidsInTenant(100L, normalized)).thenReturn(2);

        service.removeByPids(100L, List.of(" share-1 ", "share-2"));

        verify(recordShareMapper).deleteByPidsInTenant(100L, normalized);
    }

    @Test
    void removeByPidsRejectsDuplicatesBeforeDatabaseMutation() {
        assertThatThrownBy(() -> service.removeByPids(100L, List.of("share-1", "share-1")))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageContaining("unique");

        verify(recordShareMapper, never()).deleteByPidsInTenant(anyLong(), any(String[].class));
    }
}
