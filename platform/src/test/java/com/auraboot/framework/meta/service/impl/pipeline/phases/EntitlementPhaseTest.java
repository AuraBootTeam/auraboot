package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.entitlement.spi.EntitlementChecker;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.entity.CommandDefinition;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPermitPlan;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The entitlement phase as a permit-plan <em>gate</em> (authz §11.15.1): it may refuse (DENY) but
 * never grants (no PERMIT), so a satisfied entitlement can never, by itself, turn an undeclared
 * command into an authorized plan.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntitlementPhaseTest {

    @Mock private EntitlementChecker entitlementChecker;

    @Test
    @DisplayName("an inactive plugin records a DENY before throwing")
    void recordsADenyWhenThePluginIsNotActive() {
        EntitlementPhase phase = new EntitlementPhase(entitlementChecker);
        when(entitlementChecker.isPluginActive("qo")).thenReturn(false);
        CommandPipelineContext ctx = context("qo_quote_common", null);

        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Plugin entitlement required");

        assertThat(ctx.getPhaseDecisions()).singleElement().satisfies(d -> {
            assertThat(d.decision()).isEqualTo(CommandPermitPlan.Decision.DENY);
            assertThat(d.reasonCode()).isEqualTo(EntitlementPhase.REASON_PLUGIN_ENTITLEMENT_REQUIRED);
            assertThat(d.phaseName()).isEqualTo("entitlement_check");
        });
    }

    @Test
    @DisplayName("a missing required feature records a DENY before throwing")
    void recordsADenyWhenTheRequiredFeatureIsMissing() {
        EntitlementPhase phase = new EntitlementPhase(entitlementChecker);
        when(entitlementChecker.isPluginActive("qo")).thenReturn(true);
        when(entitlementChecker.hasFeature("qo", "advanced")).thenReturn(false);
        CommandPipelineContext ctx = context("qo_quote_common", "advanced");

        assertThatThrownBy(() -> phase.execute(ctx))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Feature entitlement required");

        assertThat(ctx.getPhaseDecisions()).singleElement().satisfies(d -> {
            assertThat(d.decision()).isEqualTo(CommandPermitPlan.Decision.DENY);
            assertThat(d.reasonCode()).isEqualTo(EntitlementPhase.REASON_FEATURE_ENTITLEMENT_REQUIRED);
        });
    }

    /**
     * Satisfied entitlement abstains, never permits: a gate has no objection but does not grant. If
     * it granted, an undeclared command in an entitled namespace would combine to an authorized plan.
     */
    @Test
    @DisplayName("satisfied entitlement abstains rather than permits")
    void abstainsRatherThanPermitsWhenEntitlementIsSatisfied() {
        EntitlementPhase phase = new EntitlementPhase(entitlementChecker);
        when(entitlementChecker.isPluginActive("qo")).thenReturn(true);
        CommandPipelineContext ctx = context("qo_quote_common", null);

        phase.execute(ctx);

        assertThat(ctx.getPhaseDecisions()).singleElement()
                .extracting(CommandPermitPlan.PhaseDecision::decision)
                .isEqualTo(CommandPermitPlan.Decision.ABSTAIN);
    }

    @Test
    @DisplayName("a command with no model abstains")
    void abstainsWhenTheCommandHasNoModel() {
        EntitlementPhase phase = new EntitlementPhase(entitlementChecker);
        CommandPipelineContext ctx = context(null, null);

        phase.execute(ctx);

        assertThat(ctx.getPhaseDecisions()).singleElement()
                .extracting(CommandPermitPlan.PhaseDecision::decision)
                .isEqualTo(CommandPermitPlan.Decision.ABSTAIN);
    }

    private CommandPipelineContext context(String modelCode, String requiredFeature) {
        CommandDefinition command = new CommandDefinition();
        command.setModelCode(modelCode);
        command.setRequiredFeature(requiredFeature);
        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("qo_quote_common:do")
                .request(new CommandExecuteRequest())
                .tenantId(1L)
                .userId(42L)
                .startTime(System.currentTimeMillis())
                .build();
        ctx.setCommand(command);
        return ctx;
    }
}
