package com.auraboot.framework.permission.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.DynamicDataService;
import com.auraboot.framework.permission.constants.MetaPermission;
import com.auraboot.framework.permission.entity.RecordShare;
import com.auraboot.framework.permission.service.RecordShareService;
import com.auraboot.framework.permission.service.UserPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Behavioral authz test for {@link RecordShareController}.
 *
 * <p>Audit 2026-06-28 (tenant-mutation-authorization-audit, HIGH): record sharing was
 * tenant-isolated only — any tenant member could share/unshare ANY record, including
 * records they cannot see, granting themselves access (within-tenant escalation). The
 * controller now requires the caller to either hold {@code data.record_share.manage} or
 * be the record owner (its {@code created_by}); removal additionally allows the share's
 * creator. These tests exercise that decision logic directly (no Spring context).
 */
@ExtendWith(MockitoExtension.class)
class RecordShareControllerAuthzTest {

    private static final long TENANT_ID = 1L;
    private static final long CALLER_ID = 5L;
    private static final String RESOURCE = "crm_opportunity";
    private static final String RECORD_PID = "rec-1";

    @Mock private RecordShareService recordShareService;
    @Mock private DynamicDataService dynamicDataService;
    @Mock private UserPermissionService userPermissionService;

    @InjectMocks private RecordShareController controller;

    @BeforeEach
    void setUp() {
        MetaContext.setContext(TENANT_ID, CALLER_ID, "caller-pid", "caller");
    }

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    // ---------------------------------------------------------------- share (POST)

    @Test
    void shareRecord_deniedForNonOwnerWithoutAdminPermission() {
        when(userPermissionService.hasPermission(CALLER_ID, MetaPermission.RECORD_SHARE_MANAGE)).thenReturn(false);
        when(dynamicDataService.getById(eq(RESOURCE), eq(RECORD_PID)))
                .thenReturn(Map.<String, Object>of("created_by", 99L)); // owned by someone else

        assertThrows(AccessDeniedException.class, () -> controller.shareRecord(shareRequest()));
        verifyNoInteractions(recordShareService);
    }

    @Test
    void shareRecord_allowedForRecordOwner() {
        when(userPermissionService.hasPermission(CALLER_ID, MetaPermission.RECORD_SHARE_MANAGE)).thenReturn(false);
        when(dynamicDataService.getById(eq(RESOURCE), eq(RECORD_PID)))
                .thenReturn(Map.<String, Object>of("created_by", CALLER_ID));

        controller.shareRecord(shareRequest());

        verify(recordShareService).shareRecordByPid(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void shareRecord_allowedForAdministrator() {
        when(userPermissionService.hasPermission(CALLER_ID, MetaPermission.RECORD_SHARE_MANAGE)).thenReturn(true);

        controller.shareRecord(shareRequest());

        verify(recordShareService).shareRecordByPid(any(), any(), any(), any(), any(), any(), any(), any());
    }

    // ------------------------------------------------------------ unshare (DELETE)

    @Test
    void removeShare_deniedWhenShareNotFound() {
        when(recordShareService.getByIdInTenant(TENANT_ID, 7L)).thenReturn(null);

        assertThrows(AccessDeniedException.class, () -> controller.removeShare(7L));
        verify(recordShareService, never()).removeById(any(), any());
    }

    @Test
    void removeShare_deniedForNonOwnerNonCreatorWithoutAdmin() {
        when(recordShareService.getByIdInTenant(TENANT_ID, 7L)).thenReturn(share(77L));
        when(userPermissionService.hasPermission(CALLER_ID, MetaPermission.RECORD_SHARE_MANAGE)).thenReturn(false);
        when(dynamicDataService.getById(eq(RESOURCE), eq(RECORD_PID)))
                .thenReturn(Map.<String, Object>of("created_by", 99L));

        assertThrows(AccessDeniedException.class, () -> controller.removeShare(7L));
        verify(recordShareService, never()).removeById(any(), any());
    }

    @Test
    void removeShare_allowedForShareCreator() {
        when(recordShareService.getByIdInTenant(TENANT_ID, 7L)).thenReturn(share(CALLER_ID));
        when(userPermissionService.hasPermission(CALLER_ID, MetaPermission.RECORD_SHARE_MANAGE)).thenReturn(false);

        controller.removeShare(7L);

        verify(recordShareService).removeById(TENANT_ID, 7L);
    }

    @Test
    void removeShare_allowedForRecordOwner() {
        when(recordShareService.getByIdInTenant(TENANT_ID, 7L)).thenReturn(share(77L)); // created by someone else
        when(userPermissionService.hasPermission(CALLER_ID, MetaPermission.RECORD_SHARE_MANAGE)).thenReturn(false);
        when(dynamicDataService.getById(eq(RESOURCE), eq(RECORD_PID)))
                .thenReturn(Map.<String, Object>of("created_by", CALLER_ID));

        controller.removeShare(7L);

        verify(recordShareService).removeById(TENANT_ID, 7L);
    }

    @Test
    void removeShare_allowedForAdministrator() {
        when(recordShareService.getByIdInTenant(TENANT_ID, 7L)).thenReturn(share(77L));
        when(userPermissionService.hasPermission(CALLER_ID, MetaPermission.RECORD_SHARE_MANAGE)).thenReturn(true);

        controller.removeShare(7L);

        verify(recordShareService).removeById(TENANT_ID, 7L);
    }

    // ----------------------------------------------------------------- helpers

    private RecordShareController.RecordShareRequest shareRequest() {
        RecordShareController.RecordShareRequest req = new RecordShareController.RecordShareRequest();
        req.setResourceCode(RESOURCE);
        req.setRecordPid(RECORD_PID);
        req.setSubjectType("member");
        req.setSubjectPid("sub-1");
        req.setPermissionMask("read");
        return req;
    }

    private RecordShare share(long createdBy) {
        RecordShare s = new RecordShare();
        s.setId(7L);
        s.setTenantId(TENANT_ID);
        s.setResourceCode(RESOURCE);
        s.setRecordPid(RECORD_PID);
        s.setCreatedBy(createdBy);
        return s;
    }
}
