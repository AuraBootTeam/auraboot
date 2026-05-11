package com.auraboot.framework.permission.service.impl;

import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.mapper.RecordShareMapper;
import com.auraboot.framework.rbac.service.UserRoleService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
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
import static org.mockito.Mockito.never;
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

    @InjectMocks
    private RecordShareServiceImpl service;

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
    }

    @Test
    void shareRecordByPidStoresRecordAndSubjectPidAliases() {
        Instant expires = Instant.now().plusSeconds(3600);

        service.shareRecordByPid(100L, "model.user", "rec_10", "member", "mem_5", "read", expires);

        ArgumentCaptor<RecordShare> captor = ArgumentCaptor.forClass(RecordShare.class);
        verify(recordShareMapper).insert(captor.capture());

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
    }

    @Test
    void unshareRecordDelegatesToMapper() {
        when(recordShareMapper.deleteShare(100L, "model.user", 10L, "member", 5L)).thenReturn(1);

        service.unshareRecord(100L, "model.user", 10L, "member", 5L);

        verify(recordShareMapper).deleteShare(100L, "model.user", 10L, "member", 5L);
    }

    @Test
    void isSharedReturnsTrueWhenDirectShareExists() {
        when(recordShareMapper.countByRecordAndUser(eq(100L), eq("model.user"), eq(10L), eq(5L), any()))
                .thenReturn(1);

        boolean result = service.isShared(100L, "model.user", 10L, 5L);

        assertThat(result).isTrue();
        verify(userRoleService, never()).getRoleIdsByMemberIdAndTenantId(anyLong(), anyLong());
    }

    @Test
    void isSharedByPidReturnsTrueWhenDirectSubjectPidShareExists() {
        when(recordShareMapper.countByRecordPidAndSubjectPid(
                eq(100L), eq("model.user"), eq("rec_10"), eq("member"), eq("mem_5"), any()))
                .thenReturn(1);

        boolean result = service.isSharedByPid(100L, "model.user", "rec_10", 5L, "mem_5");

        assertThat(result).isTrue();
        verify(userRoleService, never()).getRoleIdsByMemberIdAndTenantId(anyLong(), anyLong());
    }

    @Test
    void isSharedFallsBackToRolesWhenNoDirectShare() {
        when(recordShareMapper.countByRecordAndUser(eq(100L), eq("model.user"), eq(10L), eq(5L), any()))
                .thenReturn(0);
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(7L, 8L));
        when(recordShareMapper.countByRecordAndRoles(eq(100L), eq("model.user"), eq(10L), eq(List.of(7L, 8L)), any()))
                .thenReturn(1);

        assertThat(service.isShared(100L, "model.user", 10L, 5L)).isTrue();
    }

    @Test
    void isSharedReturnsFalseWhenNoDirectAndNoRoles() {
        when(recordShareMapper.countByRecordAndUser(anyLong(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(0);
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of());

        assertThat(service.isShared(100L, "model.user", 10L, 5L)).isFalse();
        verify(recordShareMapper, never()).countByRecordAndRoles(anyLong(), anyString(), anyLong(), anyList(), any());
    }

    @Test
    void isSharedReturnsFalseWhenRolesNull() {
        when(recordShareMapper.countByRecordAndUser(anyLong(), anyString(), anyLong(), anyLong(), any()))
                .thenReturn(0);
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(null);

        assertThat(service.isShared(100L, "model.user", 10L, 5L)).isFalse();
    }

    @Test
    void getSharedRecordIdsPassesEmptyListWhenNoRoles() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(null);
        when(recordShareMapper.findSharedRecordIds(eq(100L), eq("model.user"), eq(5L), eq(List.of()), any()))
                .thenReturn(List.of(1L, 2L));

        List<Long> ids = service.getSharedRecordIds(100L, "model.user", 5L, "read");

        assertThat(ids).containsExactly(1L, 2L);
    }

    @Test
    void getSharedRecordIdsPassesRolesWhenAvailable() {
        when(userRoleService.getRoleIdsByMemberIdAndTenantId(5L, 100L)).thenReturn(List.of(7L));
        when(recordShareMapper.findSharedRecordIds(eq(100L), eq("model.user"), eq(5L), eq(List.of(7L)), any()))
                .thenReturn(List.of(3L));

        assertThat(service.getSharedRecordIds(100L, "model.user", 5L, "read")).containsExactly(3L);
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
    void removeByIdThrowsWhenNotFound() {
        when(recordShareMapper.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> service.removeById(100L, 99L))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageContaining("99");
    }

    @Test
    void removeByIdThrowsWhenCrossTenant() {
        RecordShare share = new RecordShare();
        share.setId(99L);
        share.setTenantId(999L); // different tenant
        share.setResourceCode("model.user");
        share.setRecordId(10L);
        when(recordShareMapper.selectById(99L)).thenReturn(share);

        assertThatThrownBy(() -> service.removeById(100L, 99L))
                .isInstanceOf(RootUnCheckedException.class);

        verify(recordShareMapper, never()).deleteById(anyLong());
    }

    @Test
    void removeByIdDeletesWhenTenantMatches() {
        RecordShare share = new RecordShare();
        share.setId(99L);
        share.setTenantId(100L);
        share.setResourceCode("model.user");
        share.setRecordId(10L);
        when(recordShareMapper.selectById(99L)).thenReturn(share);

        service.removeById(100L, 99L);

        verify(recordShareMapper).deleteById(99L);
    }
}
