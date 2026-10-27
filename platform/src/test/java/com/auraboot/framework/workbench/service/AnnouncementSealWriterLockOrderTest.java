package com.auraboot.framework.workbench.service;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real official entry methods must establish the shared identity lock before any announcement root lock. */
class AnnouncementSealWriterLockOrderTest {
    @Test void officialApprovalSubmitLocksIdentityBeforeAnnouncementRoot() {
        var service=mock(AnnouncementApprovalService.class,CALLS_REAL_METHODS);
        var content=mock(AnnouncementContentSealService.class);var access=mock(AnnouncementAccessService.class);
        ReflectionTestUtils.setField(service,"contentSeals",content);ReflectionTestUtils.setField(service,"access",access);
        var sentinel=new IllegalStateException("stop-at-first-root-lock");
        when(access.manage("root","submit",true)).thenThrow(sentinel);
        assertSame(sentinel,assertThrows(IllegalStateException.class,()->service.execute(AnnouncementApprovalService.SUBMIT,"root",Map.of(),"operation",1)));
        var order=inOrder(content,access);order.verify(content).lockActor();order.verify(access).manage("root","submit",true);
    }
    @Test void officialExemptionLocksIdentityBeforeAnnouncementRoot() {
        var service=mock(AnnouncementReleaseService.class,CALLS_REAL_METHODS);
        var content=mock(AnnouncementContentSealService.class);var access=mock(AnnouncementAccessService.class);
        ReflectionTestUtils.setField(service,"contentSeals",content);ReflectionTestUtils.setField(service,"access",access);
        var sentinel=new IllegalStateException("stop-at-first-root-lock");
        when(access.manage("root","publish",true)).thenThrow(sentinel);
        assertSame(sentinel,assertThrows(IllegalStateException.class,()->service.execute(AnnouncementReleaseService.PUBLISH,"root",Map.of(),"operation",1)));
        var order=inOrder(content,access);order.verify(content).lockActor();order.verify(access).manage("root","publish",true);
    }
}
