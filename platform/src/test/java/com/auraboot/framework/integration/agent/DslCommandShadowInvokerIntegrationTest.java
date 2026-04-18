package com.auraboot.framework.integration.agent;

import com.auraboot.framework.agent.service.DryRunSupportRegistry;
import com.auraboot.framework.agent.service.DslCommandShadowInvoker;
import com.auraboot.framework.agent.service.ShadowEligibilityChecker;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.dto.CommandExecuteResult;
import com.auraboot.framework.meta.service.CommandExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * PR-40: DslCommandShadowInvoker — CommandPipeline dry-run path for
 * write drafts. Also verifies that the registry + eligibility classifier
 * now accept cmd_* / dsl.command as SIMULATED by platform default.
 */
@DisplayName("DslCommandShadowInvoker + CommandPipeline dry-run (PR-40)")
class DslCommandShadowInvokerIntegrationTest extends BaseIntegrationTest {

    @Autowired private DslCommandShadowInvoker invoker;
    @Autowired private DryRunSupportRegistry registry;
    @Autowired private ShadowEligibilityChecker checker;
    @MockBean  private CommandExecutor commandExecutor;

    @Test
    @DisplayName("supports() matches cmd_* and dsl.command; rejects everything else")
    void supports_predicate() {
        assertThat(invoker.supports("cmd_create_lead")).isTrue();
        assertThat(invoker.supports("dsl.command")).isTrue();
        assertThat(invoker.supports("cmd_")).isTrue();
        assertThat(invoker.supports("nq_leads")).isFalse();
        assertThat(invoker.supports(null)).isFalse();
    }

    @Test
    @DisplayName("cmd_<code> strips prefix and calls CommandExecutor with dryRun=true")
    void cmd_prefix_invokes_with_dry_run() {
        CommandExecuteResult result = CommandExecuteResult.builder()
                .commandCode("create_lead")
                .phaseReached("completed_dry_run")
                .data(Map.of("recordId", "LEAD_01"))
                .build();
        when(commandExecutor.execute(eq("create_lead"), any())).thenReturn(result);

        Map<String, Object> out = invoker.invokeShadow(10L, "cmd_create_lead",
                Map.of("payload", Map.of("name", "Alice")));

        ArgumentCaptor<CommandExecuteRequest> captor = ArgumentCaptor.forClass(CommandExecuteRequest.class);
        org.mockito.Mockito.verify(commandExecutor).execute(eq("create_lead"), captor.capture());
        CommandExecuteRequest req = captor.getValue();
        assertThat(req.isDryRun()).isTrue();
        assertThat(req.getPayload()).containsEntry("name", "Alice");

        assertThat(out.get("command_code")).isEqualTo("create_lead");
        assertThat(out.get("phase_reached")).isEqualTo("completed_dry_run");
    }

    @Test
    @DisplayName("dsl.command pulls command_code from args and sets dry-run")
    void dsl_command_from_args() {
        when(commandExecutor.execute(eq("archive_lead"), any())).thenReturn(
                CommandExecuteResult.builder().commandCode("archive_lead").phaseReached("completed_dry_run").build());

        Map<String, Object> out = invoker.invokeShadow(10L, "dsl.command",
                Map.of("command_code", "archive_lead"));
        assertThat(out.get("command_code")).isEqualTo("archive_lead");
    }

    @Test
    @DisplayName("dsl.command without command_code returns no_command_code and never calls executor")
    void dsl_missing_command_is_safe() {
        Map<String, Object> out = invoker.invokeShadow(10L, "dsl.command", Map.of());
        assertThat(out.get("status")).isEqualTo("no_command_code");
        org.mockito.Mockito.verifyNoInteractions(commandExecutor);
    }

    @Test
    @DisplayName("N12: executor exception propagates to caller (no silent-swallow)")
    void executor_exception_propagates() {
        when(commandExecutor.execute(eq("bad_cmd"), any()))
                .thenThrow(new RuntimeException("validation blew up"));

        // Previously this invoker caught the exception and returned a Map with
        // status=failed — ShadowExecutor then recorded shadowStatus="success"
        // because no exception bubbled up, inflating output_match_rate.
        // The caller's own try/catch (ShadowExecutor) now handles failure.
        assertThatThrownBy(() -> invoker.invokeShadow(10L, "cmd_bad_cmd", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("validation blew up");
    }

    @Test
    @DisplayName("N-R3-1: explicit success=true when CommandExecuteResult reaches completed_dry_run")
    void success_key_true_on_dry_run_completion() {
        when(commandExecutor.execute(eq("ok_cmd"), any())).thenReturn(
                CommandExecuteResult.builder()
                        .commandCode("ok_cmd")
                        .phaseReached("completed_dry_run")
                        .data(Map.of("recordId", "LEAD-1"))
                        .build());

        Map<String, Object> out = invoker.invokeShadow(10L, "cmd_ok_cmd",
                Map.of("payload", Map.of("name", "Alice")));

        assertThat(out).containsEntry("success", true);
    }

    @Test
    @DisplayName("N-R3-1: explicit success=false when phase_reached is not completed_dry_run")
    void success_key_false_on_non_dryrun_phase() {
        when(commandExecutor.execute(eq("partial_cmd"), any())).thenReturn(
                CommandExecuteResult.builder()
                        .commandCode("partial_cmd")
                        .phaseReached("validation")
                        .data(Map.of())
                        .build());

        Map<String, Object> out = invoker.invokeShadow(10L, "cmd_partial_cmd",
                Map.of("payload", Map.of("name", "Alice")));

        assertThat(out).containsEntry("success", false);
        assertThat(out).containsEntry("phase_reached", "validation");
    }

    // =========================================================================
    // Platform defaults upgraded to SIMULATED
    // =========================================================================

    @Test
    @DisplayName("platform defaults now classify cmd_* / dsl.command as SIMULATED (PR-40)")
    void platform_defaults_upgraded() {
        // Arbitrary tenant — no tenant override → platform default applies.
        long anyTenant = 9_750_000L + System.nanoTime() % 100_000;
        assertThat(registry.lookup(anyTenant, "cmd_anything"))
                .isEqualTo(DryRunSupportRegistry.SupportLevel.SIMULATED);
        assertThat(registry.lookup(anyTenant, "dsl.command"))
                .isEqualTo(DryRunSupportRegistry.SupportLevel.SIMULATED);
    }

    @Test
    @DisplayName("eligibility: write draft with cmd_* tool_ref now reports ELIGIBLE_DRY_RUN")
    void eligibility_write_dry_run() {
        String yaml = "substrate: dsl\naction_type: update\ntool_refs:\n  - cmd_update_lead\n";
        assertThat(checker.classify(9_700_000L, yaml))
                .isEqualTo(ShadowEligibilityChecker.Eligibility.ELIGIBLE_DRY_RUN);
    }
}
