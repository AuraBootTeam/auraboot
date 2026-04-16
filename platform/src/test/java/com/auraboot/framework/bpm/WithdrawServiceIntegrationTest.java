package com.auraboot.framework.bpm;

import com.auraboot.framework.bpm.audit.BpmAuditOperation;
import com.auraboot.framework.bpm.model.WithdrawPolicy;
import com.auraboot.framework.bpm.service.WithdrawService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WithdrawService")
class WithdrawServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WithdrawService withdrawService;

    @Autowired
    private TestBpmFixture fixture;

    @Test
    @DisplayName("Strict policy: initiator can withdraw before any approval")
    void strictAllowsWithdrawBeforeApproval() {
        var setup = fixture.startProcess("strict-before-approve", WithdrawPolicy.STRICT);

        withdrawService.withdraw(setup.taskId(), "typo in form");

        assertThat(fixture.getProcessStatus(setup.instanceId())).isEqualTo("withdrawn");
        assertThat(fixture.findAuditRecords(setup.instanceId()))
                .anyMatch(r -> BpmAuditOperation.WITHDRAW.matches(r.getOperation()));
    }

    @Test
    @DisplayName("Strict policy: rejects withdraw after first approval")
    void strictRejectsAfterApproval() {
        var setup = fixture.startProcess("strict-after-approve", WithdrawPolicy.STRICT);
        fixture.approveTask(setup.taskId(), "lgtm");

        var newTaskId = fixture.currentTaskId(setup.instanceId());
        assertThat(newTaskId).as("A second task should exist after the first approval").isNotNull();

        assertThatThrownBy(() -> withdrawService.withdraw(newTaskId, "too late"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already approved");
    }

    @Test
    @DisplayName("Loose policy: initiator can withdraw even after approvals")
    void looseAllowsWithdrawAfterApproval() {
        var setup = fixture.startProcess("loose-after-approve", WithdrawPolicy.LOOSE);
        fixture.approveTask(setup.taskId(), "lgtm");

        var newTaskId = fixture.currentTaskId(setup.instanceId());
        assertThat(newTaskId).as("A second task should exist after the first approval").isNotNull();

        withdrawService.withdraw(newTaskId, "late change of mind");

        assertThat(fixture.getProcessStatus(setup.instanceId())).isEqualTo("withdrawn");
    }

    @Test
    @DisplayName("None policy: withdraw is always rejected")
    void noneRejectsAlways() {
        var setup = fixture.startProcess("none-policy", WithdrawPolicy.NONE);
        assertThatThrownBy(() -> withdrawService.withdraw(setup.taskId(), "try"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("disabled");
    }

    @Test
    @DisplayName("Rejects withdraw by non-initiator user")
    void rejectsNonInitiator() {
        var setup = fixture.startProcessAsUser("non-initiator-test", 999L, WithdrawPolicy.STRICT);
        fixture.switchCurrentUserTo(1000L);
        assertThatThrownBy(() -> withdrawService.withdraw(setup.taskId(), "not mine"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("initiator");
    }
}
