package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.BpmNotifyService;
import com.auraboot.framework.bpm.service.CcService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Deep-review finding (BpmNotify senderUserId spoofing, from the perm-004 triage).
 *
 * <p>CC must use the same policy-guarded application service as task API and
 * automation CC. A body-supplied sender/process identity is never trusted.
 */
class BpmNotifyControllerSenderIdentityTest {

    private final BpmNotifyService notifyService = mock(BpmNotifyService.class);
    private final CcService ccService = mock(CcService.class);
    private final BpmNotifyController controller = new BpmNotifyController(notifyService, ccService);

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("sendCarbonCopy delegates receiver pids to the guarded CC service")
    void ccUsesPolicyGuardedCommandService() {
        MetaContext.setContext(1L, 42L, "pid-42", "alice");

        controller.sendCarbonCopy(Map.of(
                "taskId", "t1",
                "processInstanceId", "p1",
                "senderUserId", 999,                 // forged — must be ignored
                "recipientUserIds", List.of("pid-7"), // pid strings (MemberPicker identity)
                "content", "hi"));

        verify(ccService).cc("t1", List.of("pid-7"), "hi", "UI");
        verifyNoInteractions(notifyService);
    }

    @Test
    @DisplayName("sendUrge ignores a body-supplied senderUserId and uses the authenticated user")
    void urgeUsesAuthenticatedSender() {
        MetaContext.setContext(1L, 42L, "pid-42", "alice");

        controller.sendUrge(Map.of(
                "taskId", "t1",
                "processInstanceId", "p1",
                "senderUserId", 999,                 // forged — must be ignored
                "assigneeUserId", 8,
                "content", "please act"));

        verify(notifyService).sendUrge("t1", "p1", 42L, 8L, "please act");
    }
}
