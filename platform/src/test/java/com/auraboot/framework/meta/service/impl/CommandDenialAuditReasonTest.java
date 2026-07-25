package com.auraboot.framework.meta.service.impl;

import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan.PhaseDecision;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A boundary denial is folded into the failure audit as a machine-readable reason, not just a generic
 * exception string (authz §11.15 — the audit consumes the DENIED verdict/plan, closing the
 * silent-failure gap). This proves the audited reason the executor writes to {@code
 * ab_command_audit_log} on a refusal.
 */
class CommandDenialAuditReasonTest {

    @Test
    @DisplayName("an authorization denial audits the verdict's reason and the required permissions")
    void auditsTheAuthorizationVerdictReason() {
        CommandPipelineContext ctx = ctx();
        ctx.setAuthorizationVerdict(CommandAuthorizationVerdict.denied(List.of("mkt.approve_purchase")));

        assertThat(CommandExecutorImpl.denialAuditReason(ctx))
                .isEqualTo("boundary_denied[authorization:permission_denied requires=mkt.approve_purchase]");
    }

    @Test
    @DisplayName("a denied permit plan audits the denying phase and its reason code")
    void auditsThePermitPlanDenial() {
        CommandPipelineContext ctx = ctx();
        ctx.setPermitPlan(CommandPermitPlan.fromPhaseDecisions(
                List.of(PhaseDecision.deny("sod_violation", "sod_check")), null, null, null));

        assertThat(CommandExecutorImpl.denialAuditReason(ctx))
                .isEqualTo("boundary_denied[sod_check:sod_violation]");
    }

    @Test
    @DisplayName("the permit plan's reason is preferred when both a plan and a verdict are present")
    void prefersThePlanOverTheVerdict() {
        CommandPipelineContext ctx = ctx();
        ctx.setAuthorizationVerdict(CommandAuthorizationVerdict.denied(List.of("x")));
        ctx.setPermitPlan(CommandPermitPlan.fromPhaseDecisions(
                List.of(PhaseDecision.deny("sod_violation", "sod_check")), null, null, null));

        assertThat(CommandExecutorImpl.denialAuditReason(ctx))
                .isEqualTo("boundary_denied[sod_check:sod_violation]");
    }

    @Test
    @DisplayName("a failure that was not a boundary denial audits no synthetic reason")
    void nonDenialFailureHasNoReason() {
        CommandPipelineContext ctx = ctx(); // no verdict, no plan — an ordinary failure
        assertThat(CommandExecutorImpl.denialAuditReason(ctx)).isNull();
    }

    @Test
    @DisplayName("an authorized verdict is not a denial")
    void authorizedVerdictIsNotADenial() {
        CommandPipelineContext ctx = ctx();
        ctx.setAuthorizationVerdict(CommandAuthorizationVerdict.authorized("mkt.approve_purchase"));

        assertThat(CommandExecutorImpl.denialAuditReason(ctx)).isNull();
    }

    private CommandPipelineContext ctx() {
        return CommandPipelineContext.builder()
                .commandCode("mkt:approve_purchase")
                .request(new CommandExecuteRequest())
                .tenantId(99L)
                .userId(10L)
                .startTime(System.currentTimeMillis())
                .build();
    }
}
