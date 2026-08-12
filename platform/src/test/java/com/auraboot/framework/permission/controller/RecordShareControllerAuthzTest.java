package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.meta.dto.MetaModelDTO;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.auraboot.framework.notification.service.NotificationService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.dto.UserSearchDTO;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecordShareControllerAuthzTest {

    private static final long TENANT_ID = 1L;
    private static final long CALLER_ID = 5L;
    private static final String CALLER_PID = "caller-pid";
    private static final String RESOURCE = "crm_account_common";
    private static final String RECORD_PID = "rec-1";
    private static final String SHARE_PID = "share-1";
    private static final String SUBJECT_PID = "subject-pid";

    @Mock private RecordShareService recordShareService;
    @Mock private DynamicDataService dynamicDataService;
    @Mock private UserPermissionService userPermissionService;
    @Mock private MetaModelService metaModelService;
    @Mock private UserService userService;
    @Mock private NotificationService notificationService;
    @Mock private I18nService i18nService;

    @InjectMocks private RecordShareController controller;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, CALLER_ID, CALLER_PID, "caller");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void shareRecordDeniedForNonOwnerWithoutAdminPermission() {
        stubBusinessOwner("other-owner");

        assertThrows(AccessDeniedException.class, () -> controller.shareRecord(shareRequest()));
        verify(recordShareService, never()).shareRecordByPid(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shareRecordAllowedForConfiguredBusinessOwnerEvenWhenCreatorDiffers() {
        stubBusinessOwner(CALLER_PID);
        stubSubject();
        User recipient = new User();
        recipient.setId(19L);
        recipient.setEnabled(true);
        when(userService.findByPid(SUBJECT_PID)).thenReturn(recipient);
        when(i18nService.getValue(any(), eq("record_share.notification_permission_read"), any()))
                .thenReturn("仅查看");
        when(i18nService.getValue(any(), eq("record_share.notification_title"), any()))
                .thenReturn("客户协作权限已更新");
        when(i18nService.getMessage(any(), eq("record_share.notification_content"), any()))
                .thenReturn("你已获得一条共享记录的仅查看权限，请从对应的协作视图查看。");

        controller.shareRecord(shareRequest());

        verify(recordShareService).shareRecordByPid(
                TENANT_ID, RESOURCE, RECORD_PID, "member", SUBJECT_PID, "read", null);
        verify(notificationService).sendInApp(
                19L,
                "客户协作权限已更新",
                "你已获得一条共享记录的仅查看权限，请从对应的协作视图查看。",
                "business",
                RESOURCE,
                RECORD_PID);
    }

    @Test
    void shareRecordRejectsMemberOutsideCurrentTenant() {
        stubBusinessOwner(CALLER_PID);
        when(userService.findInTenantByPid(TENANT_ID, SUBJECT_PID)).thenReturn(null);

        assertThrows(RootUnCheckedException.class, () -> controller.shareRecord(shareRequest()));
        verify(recordShareService, never()).shareRecordByPid(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shareRecordRejectsNonHumanMember() {
        stubBusinessOwner(CALLER_PID);
        stubSubject();
        User serviceAccount = new User();
        serviceAccount.setId(19L);
        serviceAccount.setEnabled(true);
        serviceAccount.setUserType("service_account");
        when(userService.findByPid(SUBJECT_PID)).thenReturn(serviceAccount);

        assertThrows(RootUnCheckedException.class, () -> controller.shareRecord(shareRequest()));
        verify(recordShareService, never()).shareRecordByPid(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shareRecordFailsClosedWhenOwnerMetadataCannotBeResolved() {
        when(userPermissionService.hasPermission(CALLER_ID, MetaPermission.RECORD_SHARE_MANAGE))
                .thenReturn(false);
        when(dynamicDataService.getById(RESOURCE, RECORD_PID))
                .thenReturn(Map.of("created_by", CALLER_ID));
        when(metaModelService.findByCode(RESOURCE)).thenThrow(new IllegalStateException("metadata unavailable"));

        assertThrows(AccessDeniedException.class, () -> controller.shareRecord(shareRequest()));
        verify(recordShareService, never()).shareRecordByPid(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void listSharesRequiresOwnerAndReturnsOnlyPublicProjection() {
        stubBusinessOwner(CALLER_PID);
        RecordShare share = share(77L);
        share.setSubjectPid(SUBJECT_PID);
        share.setPermissionMask("read,update");
        when(recordShareService.listByRecordPid(TENANT_ID, RESOURCE, RECORD_PID))
                .thenReturn(List.of(share));
        stubSubject();

        var response = controller.listShares(RESOURCE, RECORD_PID);

        assertThat(response.getData()).singleElement().satisfies(item -> {
            assertThat(item.pid()).isEqualTo(SHARE_PID);
            assertThat(item.subjectName()).isEqualTo("Sales Two");
            assertThat(item.permissionMask()).isEqualTo("read,update");
        });
    }

    @Test
    void capabilityIsFalseForReadOnlyCollaborator() {
        stubBusinessOwner("other-owner");

        assertThat(controller.getManageCapability(RESOURCE, RECORD_PID).getData().canManage())
                .isFalse();
    }

    @Test
    void capabilityIsFalseForAdministratorWhenRecordDoesNotExist() {
        when(dynamicDataService.getById(RESOURCE, RECORD_PID)).thenReturn(Map.of());

        assertThat(controller.getManageCapability(RESOURCE, RECORD_PID).getData().canManage())
                .isFalse();
        verify(userPermissionService, never()).hasPermission(any(Long.class), any(String.class));
    }

    @Test
    void removeShareDeniedWhenShareNotFound() {
        when(recordShareService.getByPidInTenant(TENANT_ID, SHARE_PID)).thenReturn(null);

        assertThrows(AccessDeniedException.class, () -> controller.removeShare(SHARE_PID));
        verify(recordShareService, never()).removeByPid(any(), any());
    }

    @Test
    void removeShareDeniedForOriginalCreatorAfterBusinessOwnershipChanged() {
        when(recordShareService.getByPidInTenant(TENANT_ID, SHARE_PID)).thenReturn(share(CALLER_ID));
        stubBusinessOwner("new-owner");

        assertThrows(AccessDeniedException.class, () -> controller.removeShare(SHARE_PID));
        verify(recordShareService, never()).removeByPid(any(), any());
    }

    @Test
    void removeShareAllowedForCurrentBusinessOwner() {
        when(recordShareService.getByPidInTenant(TENANT_ID, SHARE_PID)).thenReturn(share(77L));
        stubBusinessOwner(CALLER_PID);

        controller.removeShare(SHARE_PID);

        verify(recordShareService).removeByPid(TENANT_ID, SHARE_PID);
    }

    @Test
    void removeShareAllowedForAdministrator() {
        when(recordShareService.getByPidInTenant(TENANT_ID, SHARE_PID)).thenReturn(share(77L));
        when(userPermissionService.hasPermission(CALLER_ID, MetaPermission.RECORD_SHARE_MANAGE))
                .thenReturn(true);

        controller.removeShare(SHARE_PID);

        verify(recordShareService).removeByPid(TENANT_ID, SHARE_PID);
    }

    private void stubBusinessOwner(String ownerPid) {
        when(userPermissionService.hasPermission(CALLER_ID, MetaPermission.RECORD_SHARE_MANAGE))
                .thenReturn(false);
        when(metaModelService.findByCode(RESOURCE)).thenReturn(MetaModelDTO.builder()
                .code(RESOURCE)
                .extension(Map.of("dataScope", Map.of("ownerField", "crm_acc_owner")))
                .build());
        when(dynamicDataService.getById(RESOURCE, RECORD_PID))
                .thenReturn(Map.of("crm_acc_owner", ownerPid, "created_by", 77L));
    }

    private void stubSubject() {
        when(userService.findInTenantByPid(TENANT_ID, SUBJECT_PID)).thenReturn(
                UserSearchDTO.builder().pid(SUBJECT_PID).displayName("Sales Two").build());
    }

    private RecordShareController.RecordShareRequest shareRequest() {
        RecordShareController.RecordShareRequest request = new RecordShareController.RecordShareRequest();
        request.setResourceCode(RESOURCE);
        request.setRecordPid(RECORD_PID);
        request.setSubjectType("member");
        request.setSubjectPid(SUBJECT_PID);
        request.setPermissionMask("read");
        return request;
    }

    private RecordShare share(long createdBy) {
        RecordShare share = new RecordShare();
        share.setPid(SHARE_PID);
        share.setTenantId(TENANT_ID);
        share.setResourceCode(RESOURCE);
        share.setRecordPid(RECORD_PID);
        share.setSubjectType("member");
        share.setCreatedBy(createdBy);
        return share;
    }
}
