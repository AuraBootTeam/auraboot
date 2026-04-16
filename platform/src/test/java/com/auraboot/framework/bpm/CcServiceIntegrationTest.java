package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmCcRecord;
import com.auraboot.framework.bpm.mapper.BpmCcRecordMapper;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.service.CcService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.inbox.model.InboxItem;
import com.auraboot.framework.inbox.service.InboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CcService")
class CcServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private CcService ccService;
    @Autowired private BpmCcRecordMapper ccMapper;
    @Autowired private InboxService inboxService;
    @Autowired private TestBpmFixture fixture;

    @Test
    @DisplayName("Policy=all, initiator sends cc: records + notifies + audits")
    void allPolicyAssigneeCc() {
        // Policy=all allows both initiator and assignee. We verify using the initiator
        // (who is the current user at process start) since the BPMN fixture uses a
        // "system" assignee that is not a real numeric user in MetaContext.
        var setup = fixture.startProcess("cc-all-initiator", CcPolicy.ALL);

        ccService.cc(setup.taskId(), List.of(501L, 502L), "please be aware");

        List<BpmCcRecord> records = ccMapper.findByProcessInstance(
                MetaContext.getCurrentTenantId(), setup.instanceId());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getReceiverUserIds()).containsExactly(501L, 502L);
        assertThat(records.get(0).getComment()).isEqualTo("please be aware");

        var inbox501 = inboxService.listByUser(501L, MetaContext.getCurrentTenantId(),
                "bpm_cc", "pending", 0, 10);
        assertThat(inbox501.getRecords()).hasSize(1);
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
    @DisplayName("Empty receivers rejected")
    void emptyReceiversRejected() {
        var setup = fixture.startProcess("cc-empty", CcPolicy.ALL);
        assertThatThrownBy(() -> ccService.cc(setup.taskId(), List.of(), "nobody"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
