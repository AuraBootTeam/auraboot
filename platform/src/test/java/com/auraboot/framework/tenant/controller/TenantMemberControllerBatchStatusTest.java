package com.auraboot.framework.tenant.controller;

import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.tenant.controller.request.BatchMemberStatusRequest;
import com.auraboot.framework.tenant.service.CurrentUserTeamResolver;
import com.auraboot.framework.tenant.service.TenantMemberApplicationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantMemberControllerBatchStatusTest {

    @Mock
    private TenantMemberApplicationService memberApplicationService;
    @Mock
    private CurrentUserTeamResolver currentUserTeamResolver;

    @InjectMocks
    private TenantMemberController controller;

    private BatchMemberStatusRequest request(List<String> pids, String action) {
        BatchMemberStatusRequest request = new BatchMemberStatusRequest();
        request.setMemberPids(pids);
        request.setAction(action);
        request.setReason("parity verification");
        return request;
    }

    @Test
    void batchStatus_delegatesEachMemberWithSameAction() {
        var response = controller.batchUpdateMemberStatus(
            request(List.of("m-1", "m-2"), "suspended"), 42L);

        verify(memberApplicationService).updateMemberStatus(
            eq("m-1"), eq("suspended"), eq("parity verification"), isNull(), eq(42L));
        verify(memberApplicationService).updateMemberStatus(
            eq("m-2"), eq("suspended"), eq("parity verification"), isNull(), eq(42L));
        assertThat(response.getData()).isEqualTo(Map.of("succeeded", 2, "failed", List.of()));
    }

    @Test
    void batchStatus_aggregatesPartialFailures() {
        when(memberApplicationService.updateMemberStatus(
                eq("m-1"), any(), any(), isNull(), eq(42L)))
            .thenReturn(true);
        doThrow(new RuntimeException("member missing"))
            .when(memberApplicationService)
            .updateMemberStatus(eq("m-bad"), any(), any(), isNull(), eq(42L));

        var response = controller.batchUpdateMemberStatus(
            request(List.of("m-1", "m-bad"), "active"), 42L);

        assertThat(response.getData().get("succeeded")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        List<Map<String, String>> failed = (List<Map<String, String>>) response.getData().get("failed");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0)).containsEntry("memberPid", "m-bad");
    }

    @Test
    void batchStatus_rejectsEmptyPidList() {
        assertThatThrownBy(() -> controller.batchUpdateMemberStatus(
            request(List.of(), "active"), 42L))
            .isInstanceOf(RootUnCheckedException.class);
        verify(memberApplicationService, never()).updateMemberStatus(
            any(), any(), any(), any(), any());
    }

    @Test
    void batchStatus_rejectsOversizedBatch() {
        List<String> oversize = IntStream.rangeClosed(1, 101)
            .mapToObj(i -> "m-" + i).toList();
        assertThatThrownBy(() -> controller.batchUpdateMemberStatus(
            request(oversize, "active"), 42L))
            .isInstanceOf(RootUnCheckedException.class);
    }
}
