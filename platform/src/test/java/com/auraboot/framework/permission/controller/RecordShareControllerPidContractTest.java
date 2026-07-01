package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordShareControllerPidContractTest {

    @Mock private RecordShareService recordShareService;
    @Mock private DynamicDataService dynamicDataService;
    @Mock private UserPermissionService userPermissionService;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void listSharesUsesRecordPidOnly() {
        MetaContext.setCurrentTenantId(7L);
        RecordShare share = new RecordShare();
        share.setRecordPid("01KSHAREPID");
        when(recordShareService.listByRecordPid(7L, "crm_lead", "01KSHAREPID"))
                .thenReturn(List.of(share));

        RecordShareController controller =
                new RecordShareController(recordShareService, dynamicDataService, userPermissionService);
        ApiResponse<List<RecordShare>> response = controller.listShares("crm_lead", "01KSHAREPID");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).extracting(RecordShare::getRecordPid).containsExactly("01KSHAREPID");
        verify(recordShareService).listByRecordPid(7L, "crm_lead", "01KSHAREPID");
        verify(recordShareService, never()).listByRecord(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shareRecordUsesRecordPidOnly() {
        // Authenticated caller with the record-share admin permission, so the share
        // passes the owner/admin gate and we can assert the PID-only delegation contract.
        MetaContext.setContext(7L, 5L, "caller-pid", "caller");
        when(userPermissionService.hasPermission(5L, MetaPermission.RECORD_SHARE_MANAGE)).thenReturn(true);
        RecordShareController.RecordShareRequest request = new RecordShareController.RecordShareRequest();
        request.setResourceCode("crm_lead");
        request.setRecordPid("01KSHAREPID");
        request.setSubjectType("member");
        request.setSubjectPid("01KMEMBERPID");
        request.setPermissionMask("read");
        request.setExpiresAt(Instant.parse("2026-07-01T00:00:00Z"));

        RecordShareController controller =
                new RecordShareController(recordShareService, dynamicDataService, userPermissionService);
        ApiResponse<Void> response = controller.shareRecord(request);

        assertThat(response.isSuccess()).isTrue();
        verify(recordShareService).shareRecordByPid(
                7L,
                "crm_lead",
                "01KSHAREPID",
                "member",
                null,
                "01KMEMBERPID",
                "read",
                Instant.parse("2026-07-01T00:00:00Z"));
        verify(recordShareService, never()).shareRecord(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void requestDtoDoesNotExposeRecordId() {
        assertThat(Arrays.stream(RecordShareController.RecordShareRequest.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain("recordId");
    }
}
