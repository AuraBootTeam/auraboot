package com.auraboot.framework.meta.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        req.setAuditContext(java.util.Map.of("source", "unified-designer-runtime-preview"));
        req.setTargetRecordId("ID123");
        req.setOperationType("UPDATE");
        // All fields preserved, dryRun still true
        assertThat(req.isDryRun()).isTrue();
        assertThat(req.getPayload()).containsEntry("x", 1);
        assertThat(req.getAuditContext()).containsEntry("source", "unified-designer-runtime-preview");
        assertThat(req.getTargetRecordId()).isEqualTo("ID123");
        assertThat(req.getOperationType()).isEqualTo("UPDATE");
    }

    @Test
    @DisplayName("targetRecordPid is the only public JSON target field")
    void target_record_pid_public_json_contract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CommandExecuteRequest req = new CommandExecuteRequest();

        req.setTargetRecordId("internal-pid");
        String json = mapper.writeValueAsString(req);

        assertThat(json).contains("\"targetRecordPid\":\"internal-pid\"");
        assertThat(json).doesNotContain("targetRecordId");

        CommandExecuteRequest fromPublicPid =
                mapper.readValue("{\"targetRecordPid\":\"public-pid\"}", CommandExecuteRequest.class);
        assertThat(fromPublicPid.getTargetRecordId()).isEqualTo("public-pid");
        assertThat(fromPublicPid.getTargetRecordPid()).isEqualTo("public-pid");

        CommandExecuteRequest fromLegacyId =
                mapper.readValue("{\"targetRecordId\":\"legacy-pid\"}", CommandExecuteRequest.class);
        assertThat(fromLegacyId.getTargetRecordId()).isNull();
    }
}
