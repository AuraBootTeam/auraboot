package com.auraboot.framework.meta.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR-48: guards on the dryRun flag added in PR-40.
 * Integration coverage lives in DslCommandShadowInvokerIntegrationTest;
 * this test just nails down the DTO defaults so a future Lombok upgrade
 * or field reorder cannot silently flip the default to true.
 */
@DisplayName("CommandExecuteRequest.dryRun semantics (PR-48)")
class CommandExecuteRequestDryRunTest {

    @Test
    @DisplayName("new request defaults dryRun=false — normal writes must commit")
    void default_is_false() {
        CommandExecuteRequest req = new CommandExecuteRequest();
        assertThat(req.isDryRun()).isFalse();
    }

    @Test
    @DisplayName("setter toggles dryRun to true")
    void setter_flips_flag() {
        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setDryRun(true);
        assertThat(req.isDryRun()).isTrue();
    }

    @Test
    @DisplayName("dryRun flag is independent of other fields")
    void dry_run_orthogonal() {
        CommandExecuteRequest req = new CommandExecuteRequest();
        req.setDryRun(true);
        req.setPayload(java.util.Map.of("x", 1));
        req.setTargetRecordId("ID123");
        req.setOperationType("UPDATE");
        // All fields preserved, dryRun still true
        assertThat(req.isDryRun()).isTrue();
        assertThat(req.getPayload()).containsEntry("x", 1);
        assertThat(req.getTargetRecordId()).isEqualTo("ID123");
        assertThat(req.getOperationType()).isEqualTo("UPDATE");
    }
}
