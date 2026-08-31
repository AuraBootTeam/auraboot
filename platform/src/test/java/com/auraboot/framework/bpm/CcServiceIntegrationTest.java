package com.auraboot.framework.bpm;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.bpm.entity.BpmNotifyRecord;
import com.auraboot.framework.bpm.mapper.BpmNotifyRecordMapper;
import com.auraboot.framework.bpm.model.CcPolicy;
import com.auraboot.framework.bpm.service.CcService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.smart.framework.engine.SmartEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CcService (AuraBoot BPM notify store)")
class CcServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private CcService ccService;
    @Autowired private TestBpmFixture fixture;
    @Autowired private SmartEngine smartEngine;
    @Autowired private BpmNotifyRecordMapper notifyRecordMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Policy=all, initiator sends cc: AuraBoot stores one notify row per receiver")
    void allPolicyInitiatorCc() {
        var setup = fixture.startProcess("cc-all-initiator", CcPolicy.ALL);

        ccService.ccForUserIds(setup.taskId(), List.of(501L, 502L), "please be aware");

        List<BpmNotifyRecord> r501 = notifyRecords(setup, 501L);
        List<BpmNotifyRecord> r502 = notifyRecords(setup, 502L);
        assertThat(r501).hasSize(1);
        assertThat(r501.getFirst().getProcessInstanceId()).isEqualTo(setup.instanceId());
        assertThat(r501.getFirst().getContent()).isEqualTo("please be aware");
        assertThat(r501.getFirst().getIsRead()).isFalse();
        assertThat(r501.getFirst().getSourceType()).isEqualTo("AUTOMATION");
        assertThat(r502).hasSize(1);
    }

    @Test
    @DisplayName("Policy=all, claimed assignee sends cc: accepted")
    void allPolicyAssigneeCc() {
        var setup = fixture.startProcess("cc-all-assignee-pos", CcPolicy.ALL);
        smartEngine.getTaskCommandService().claim(
                setup.taskId(), "888", MetaContext.getCurrentTenantIdAsString());
        fixture.switchCurrentUserTo(setup.assigneeId());

        ccService.ccForUserIds(setup.taskId(), List.of(777L), "assignee-sends-cc");

        List<BpmNotifyRecord> r777 = notifyRecords(setup, 777L);
        assertThat(r777).hasSize(1);
        assertThat(r777.getFirst().getSenderUserId()).isEqualTo(setup.assigneeId());
        assertThat(r777.getFirst().getTitle()).isEqualTo("$i18n:bpm.cc.inbox.title");
    }

    @Test
    @DisplayName("Explicit dedup key is idempotent per receiver")
    void explicitDedupKeyPreventsDuplicateFanOut() {
        var setup = fixture.startProcess("cc-dedup", CcPolicy.ALL);

        ccService.ccForUserIds(setup.taskId(), List.of(503L), "once", "AUTOMATION", "cc-dedup-key");
        ccService.ccForUserIds(setup.taskId(), List.of(503L), "retry", "AUTOMATION", "cc-dedup-key");

        assertThat(notifyRecords(setup, 503L)).hasSize(1);
        assertThat(notifyRecords(setup, 503L).getFirst().getContent()).isEqualTo("once");
    }

    @Test
    @DisplayName("Policy=initiator rejects assignee; policy=assignee rejects initiator")
    void policyGatesRemainEnforced() {
        var initiatorOnly = fixture.startProcess("cc-initiator-only", CcPolicy.INITIATOR);
        fixture.switchCurrentUserTo(initiatorOnly.assigneeId());
        assertThatThrownBy(() -> ccService.ccForUserIds(
                initiatorOnly.taskId(), List.of(501L), "assignee cc attempt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("policy");

        var assigneeOnly = fixture.startProcess("cc-assignee-only", CcPolicy.ASSIGNEE);
        assertThatThrownBy(() -> ccService.ccForUserIds(
                assigneeOnly.taskId(), List.of(501L), "initiator cc attempt"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("policy");
    }

    @Test
    @DisplayName("Bad receiver input is rejected before fan-out")
    void badReceiverInputRejected() {
        var setup = fixture.startProcess("cc-bad-input", CcPolicy.ALL);
        assertThatThrownBy(() -> ccService.cc(setup.taskId(), List.of(), "nobody"))
                .isInstanceOf(IllegalArgumentException.class);
        var receivers = new java.util.ArrayList<Long>();
        receivers.add(501L);
        receivers.add(null);
        assertThatThrownBy(() -> ccService.ccForUserIds(setup.taskId(), receivers, "null entry"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SmartEngine notification storage is removed")
    void engineNotificationStorageIsRemoved() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = current_schema() AND table_name = 'se_notification_instance'",
                Integer.class);
        assertThat(count).isZero();
    }

    private List<BpmNotifyRecord> notifyRecords(
            TestBpmFixture.ProcessSetup setup, Long recipientId) {
        return notifyRecordMapper.findByRecipient(
                        MetaContext.getCurrentTenantId(), recipientId, "CC")
                .stream()
                .filter(record -> setup.instanceId().equals(record.getProcessInstanceId()))
                .toList();
    }
}
