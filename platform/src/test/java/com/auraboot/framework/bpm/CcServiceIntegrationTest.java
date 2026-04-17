package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.service.CcService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.smart.framework.engine.SmartEngine;
import com.auraboot.smart.framework.engine.constant.NotificationConstant;
import com.auraboot.smart.framework.engine.model.instance.NotificationInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CcService (SmartEngine notification backend)")
class CcServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private CcService ccService;
    @Autowired private TestBpmFixture fixture;
    @Autowired private SmartEngine smartEngine;

    @Test
    @DisplayName("Policy=all, initiator sends cc: SmartEngine stores 2 notifications with type=cc")
    void allPolicyInitiatorCc() {
        var setup = fixture.startProcess("cc-all-initiator", CcPolicy.ALL);

        ccService.cc(setup.taskId(), List.of(501L, 502L), "please be aware");

        List<NotificationInstance> r501 = smartEngine.createNotificationQuery()
                .receiverUserId("501")
                .notificationType(NotificationConstant.NotificationType.CC)
                .listPage(0, 10);
        List<NotificationInstance> r502 = smartEngine.createNotificationQuery()
                .receiverUserId("502")
                .notificationType(NotificationConstant.NotificationType.CC)
                .listPage(0, 10);

        assertThat(r501).hasSize(1);
        assertThat(r501.get(0).getProcessInstanceId()).isEqualTo(setup.instanceId());
        assertThat(r501.get(0).getContent()).isEqualTo("please be aware");
        assertThat(r501.get(0).getReadStatus()).isEqualTo(NotificationConstant.ReadStatus.UNREAD);
        assertThat(r502).hasSize(1);
    }

    @Test
    @DisplayName("Policy=all, assignee sends cc: accepted")
    void allPolicyAssigneeCc() {
        var setup = fixture.startProcess("cc-all-assignee-pos", CcPolicy.ALL);

        // Claim the task as user 888 so task.claimUserId == "888"
        smartEngine.getTaskCommandService().claim(
                setup.taskId(), "888", MetaContext.getCurrentTenantIdAsString());

        // Switch current user to the task assignee
        fixture.switchCurrentUserTo(setup.assigneeId());

        ccService.cc(setup.taskId(), List.of(777L), "assignee-sends-cc");

        List<NotificationInstance> r777 = smartEngine.createNotificationQuery()
                .receiverUserId("777")
                .notificationType(NotificationConstant.NotificationType.CC)
                .listPage(0, 10);
        assertThat(r777).hasSize(1);
        // Sender id is the assignee (888L)
        assertThat(r777.get(0).getSenderUserId()).isEqualTo(String.valueOf(setup.assigneeId()));
        assertThat(r777.get(0).getTitle()).isEqualTo("$i18n:bpm.cc.inbox.title");
    }

    @Test
    @DisplayName("Policy=initiator, assignee attempts cc: rejected")
    void initiatorPolicyRejectsAssignee() {
        var setup = fixture.startProcess("cc-initiator-only", CcPolicy.INITIATOR);
        fixture.switchCurrentUserTo(setup.assigneeId());

        assertThatThrownBy(() -> ccService.cc(setup.taskId(), List.of(501L), "assignee cc attempt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("policy");
    }

    @Test
    @DisplayName("Policy=assignee, initiator attempts cc: rejected")
    void assigneePolicyRejectsInitiator() {
        var setup = fixture.startProcess("cc-assignee-only", CcPolicy.ASSIGNEE);
        // current user is initiator by default in fixture
        assertThatThrownBy(() -> ccService.cc(setup.taskId(), List.of(501L), "initiator cc attempt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("policy");
    }

    @Test
    @DisplayName("Empty receivers rejected with IllegalArgumentException")
    void emptyReceiversRejected() {
        var setup = fixture.startProcess("cc-empty", CcPolicy.ALL);
        assertThatThrownBy(() -> ccService.cc(setup.taskId(), List.of(), "nobody"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Receivers list with null entry rejected")
    void nullReceiverEntryRejected() {
        var setup = fixture.startProcess("cc-null-entry", CcPolicy.ALL);
        var receivers = new java.util.ArrayList<Long>();
        receivers.add(501L);
        receivers.add(null);
        assertThatThrownBy(() -> ccService.cc(setup.taskId(), receivers, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("null");
    }
}
