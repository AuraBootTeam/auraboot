package com.auraboot.framework.integration.agent;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.mapper.CommandAuditLogMapper;
import com.auraboot.framework.meta.service.CommandExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PR-56 C4: verify that {@link CommandExecutor#execute} does NOT write a row
 * into {@code ab_command_audit_log} on its catch-path when the request is a
 * dry-run. The failure path is triggered by passing an unknown command code
 * (LoadPhase will throw), exercising the exact catch block that previously
 * called {@code effectExecutor.saveAuditLog(...)} unconditionally.
 */
@DisplayName("CommandExecutor — dry-run suppresses failure-path audit log (PR-56 C4)")
class AuditTrailDryRunIntegrationTest extends BaseIntegrationTest {

    @Autowired private CommandExecutor commandExecutor;
    @Autowired private CommandAuditLogMapper auditLogMapper;

    @Test
    @DisplayName("dryRun=true + failing command → no new ab_command_audit_log row")
    void dry_run_failure_skips_audit_log() {
        String bogusCommand = "pr56_nonexistent_" + System.nanoTime();
        Long tenantId = testTenant.getId();

        long before = auditLogMapper.countLogs(tenantId, bogusCommand, null, null, null);

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(Collections.emptyMap());
        req.setDryRun(true);

        // LoadPhase throws because the command definition does not exist —
        // exception still propagates (standards: do not swallow), we just
        // must not see an audit row.
        assertThatThrownBy(() -> commandExecutor.execute(bogusCommand, req))
                .isInstanceOf(Exception.class);

        long after = auditLogMapper.countLogs(tenantId, bogusCommand, null, null, null);
        assertThat(after).as("dry-run failure must NOT append to ab_command_audit_log")
                .isEqualTo(before);
    }

    @Test
    @DisplayName("dryRun=false + failing command → one ab_command_audit_log row (control)")
    void non_dry_run_failure_does_write_audit_log() {
        String bogusCommand = "pr56_nonexistent_ctrl_" + System.nanoTime();
        Long tenantId = testTenant.getId();

        long before = auditLogMapper.countLogs(tenantId, bogusCommand, null, null, null);

        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setPayload(Collections.emptyMap());
        req.setDryRun(false);

        assertThatThrownBy(() -> commandExecutor.execute(bogusCommand, req))
                .isInstanceOf(Exception.class);

        long after = auditLogMapper.countLogs(tenantId, bogusCommand, null, null, null);
        assertThat(after).as("non-dry-run failure should append exactly one audit row")
                .isEqualTo(before + 1);
    }
}
