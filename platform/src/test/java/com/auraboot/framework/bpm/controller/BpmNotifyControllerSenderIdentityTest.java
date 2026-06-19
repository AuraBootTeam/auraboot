package com.auraboot.framework.bpm.controller;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.service.BpmNotifyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Deep-review finding (BpmNotify senderUserId spoofing, from the perm-004 triage).
 *
 * <p>cc/urge previously read {@code senderUserId} from the request body, so any authenticated
 * user could impersonate another as the notification sender. The sender must be the
 * authenticated caller ({@code MetaContext.getCurrentUserId()}).
 */
class BpmNotifyControllerSenderIdentityTest {

    private final BpmNotifyService notifyService = mock(BpmNotifyService.class);
    private final BpmNotifyController controller = new BpmNotifyController(notifyService);

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("sendCarbonCopy ignores a body-supplied senderUserId and uses the authenticated user")
    void ccUsesAuthenticatedSender() {
        MetaContext.setContext(1L, 42L, "pid-42", "alice");

        controller.sendCarbonCopy(Map.of(
                "taskId", "t1",
                "processInstanceId", "p1",
                "senderUserId", 999,                 // forged — must be ignored
                "recipientUserIds", List.of(7),
                "content", "hi"));

        // 42 (authenticated) is passed through, NOT 999 (forged body value).
        verify(notifyService).sendCarbonCopy("t1", "p1", 42L, List.of(7L), "hi");
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
