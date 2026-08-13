package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordShareControllerPidContractTest {

    @Mock private RecordShareService recordShareService;
    @Mock private DynamicDataService dynamicDataService;
    @Mock private UserPermissionService userPermissionService;
    @Mock private MetaModelService metaModelService;
    @Mock private UserService userService;
    @Mock private NotificationService notificationService;
    @Mock private I18nService i18nService;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void listProjectionAndDeletePathDoNotExposeInternalNumericIds() {
        MetaContext.setContext(7L, 5L, "caller-pid", "caller");
        when(userPermissionService.hasPermission(5L, MetaPermission.RECORD_SHARE_MANAGE))
                .thenReturn(true);
        when(dynamicDataService.getById("crm_lead_common", "01KRECORDPID"))
                .thenReturn(Map.of("pid", "01KRECORDPID"));
        RecordShare share = new RecordShare();
        share.setId(991L);
        share.setPid("01KSHAREPID");
        share.setSubjectId(992L);
        share.setSubjectPid("01KMEMBERPID");
        share.setSubjectType("member");
        share.setPermissionMask("read");
        when(recordShareService.listByRecordPidForManagement(7L, "crm_lead_common", "01KRECORDPID"))
                .thenReturn(List.of(share));
        when(userService.findInTenantByPid(7L, "01KMEMBERPID"))
                .thenReturn(UserSearchDTO.builder().pid("01KMEMBERPID").displayName("Member").build());

        RecordShareController controller = controller();
        var response = controller.listShares("crm_lead_common", "01KRECORDPID");

        assertThat(response.getData()).extracting(RecordShareController.RecordShareResponse::pid)
                .containsExactly("01KSHAREPID");
        assertThat(Arrays.stream(RecordShareController.RecordShareResponse.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain("id", "subjectId", "recordId", "recordPid", "subjectPid", "createdBy");
    }

    @Test
    void shareRecordDelegatesWithPublicPidsOnly() {
        MetaContext.setContext(7L, 5L, "caller-pid", "caller");
        when(userPermissionService.hasPermission(5L, MetaPermission.RECORD_SHARE_MANAGE))
                .thenReturn(true);
        when(dynamicDataService.getById("crm_lead_common", "01KRECORDPID"))
                .thenReturn(Map.of("pid", "01KRECORDPID"));
        when(userService.findInTenantByPid(7L, "01KMEMBERPID"))
                .thenReturn(UserSearchDTO.builder().pid("01KMEMBERPID").displayName("Member").build());
        User recipient = new User();
        recipient.setId(99L);
        recipient.setEnabled(true);
        when(userService.findByPid("01KMEMBERPID")).thenReturn(recipient);
        RecordShareController.RecordShareRequest request = new RecordShareController.RecordShareRequest();
        request.setResourceCode("crm_lead_common");
        request.setRecordPid("01KRECORDPID");
        request.setSubjectType("member");
        request.setSubjectPid("01KMEMBERPID");
        request.setPermissionMask("read, update");
        request.setExpiresAt(Instant.parse("2099-07-01T00:00:00Z"));

        var response = controller().shareRecord(request);

        assertThat(response.isSuccess()).isTrue();
        verify(recordShareService).shareRecordByPid(
                7L, "crm_lead_common", "01KRECORDPID", "member", "01KMEMBERPID",
                "read,update", Instant.parse("2099-07-01T00:00:00Z"));
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
    void requestDtoDoesNotExposeInternalIds() {
        assertThat(Arrays.stream(RecordShareController.RecordShareRequest.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain("recordId", "subjectId", "id");
    }

    @Test
    void updateDtoAndPathUsePublicSharePidOnly() {
        MetaContext.setContext(7L, 5L, "caller-pid", "caller");
        RecordShare share = new RecordShare();
        share.setPid("01KSHAREPID");
        share.setTenantId(7L);
        share.setResourceCode("crm_lead_common");
        share.setRecordPid("01KRECORDPID");
        share.setSubjectPid("01KMEMBERPID");
        when(recordShareService.getByPidInTenant(7L, "01KSHAREPID")).thenReturn(share);
        when(userPermissionService.hasPermission(5L, MetaPermission.RECORD_SHARE_MANAGE))
                .thenReturn(true);
        RecordShareController.RecordShareUpdateRequest request =
                new RecordShareController.RecordShareUpdateRequest();
        request.setPermissionMask("read, update");
        request.setExpiresAt(Instant.parse("2099-08-01T00:00:00Z"));

        var response = controller().updateShare("01KSHAREPID", request);

        assertThat(response.isSuccess()).isTrue();
        verify(recordShareService).updateByPid(
                7L, "01KSHAREPID", "read,update", Instant.parse("2099-08-01T00:00:00Z"));
        assertThat(Arrays.stream(RecordShareController.RecordShareUpdateRequest.class.getDeclaredFields())
                .map(Field::getName))
                .containsExactlyInAnyOrder("permissionMask", "expiresAt")
                .doesNotContain("subjectPid", "recordPid", "subjectId", "recordId", "id");
    }

    private RecordShareController controller() {
        return new RecordShareController(
                recordShareService,
                dynamicDataService,
                userPermissionService,
                metaModelService,
                userService,
                notificationService,
                i18nService);
    }
}
