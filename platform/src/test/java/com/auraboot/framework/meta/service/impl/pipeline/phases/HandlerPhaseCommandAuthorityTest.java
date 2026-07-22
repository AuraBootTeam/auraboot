package com.auraboot.framework.meta.service.impl.pipeline.phases;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.dto.CommandExecuteRequest;
import com.auraboot.framework.meta.service.impl.pipeline.CommandAuthorizationVerdict;
import com.auraboot.framework.meta.service.impl.pipeline.CommandPipelineContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which verdicts may open the command's authority (DDR-2026-07-22 step 3).
 *
 * <p>This is the gate that keeps the change safe. ~200 commands across the plugin repos declare no
 * permissions at all — 23 of 23 in enterprise — and their boundary therefore grants nothing. If a
 * NOT_APPLICABLE verdict opened a scope, every one of them would become a tenant-wide write oracle,
 * silently and with no code change of their own.
 */
@DisplayName("Only an AUTHORIZED verdict opens the command's authority")
class HandlerPhaseCommandAuthorityTest {

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("an AUTHORIZED verdict opens a scope carrying the granting permission")
    void authorizedOpensTheScope() {
        HandlerPhase phase = phaseWithAuthorityEnabled(true);
        CommandPipelineContext ctx = context(CommandAuthorizationVerdict.authorized("qo.price.manage"));

        assertThat(authorityDuring(phase, ctx)).isEqualTo("qo.price.manage");
    }

    @Test
    @DisplayName("a command that declared nothing opens no scope")
    void undeclaredOpensNothing() {
        HandlerPhase phase = phaseWithAuthorityEnabled(true);
        CommandPipelineContext ctx = context(CommandAuthorizationVerdict
                .notApplicable(CommandAuthorizationVerdict.REASON_NO_DECLARED_PERMISSIONS));

        assertThat(authorityDuring(phase, ctx)).isNull();
    }

    @Test
    @DisplayName("a command with no user in context opens no scope")
    void noUserContextOpensNothing() {
        HandlerPhase phase = phaseWithAuthorityEnabled(true);
        CommandPipelineContext ctx = context(CommandAuthorizationVerdict
                .notApplicable(CommandAuthorizationVerdict.REASON_NO_USER_CONTEXT));

        assertThat(authorityDuring(phase, ctx)).isNull();
    }

    /** Defensive: a context that never reached the authorization phase grants nothing either. */
    @Test
    @DisplayName("a missing verdict opens no scope")
    void missingVerdictOpensNothing() {
        HandlerPhase phase = phaseWithAuthorityEnabled(true);

        assertThat(authorityDuring(phase, context(null))).isNull();
    }

    /** The rollout switch: until observe mode clears it, nothing inherits anything. */
    @Test
    @DisplayName("with the feature disabled, even an AUTHORIZED verdict opens no scope")
    void disabledOpensNothingEvenWhenAuthorized() {
        HandlerPhase phase = phaseWithAuthorityEnabled(false);
        CommandPipelineContext ctx = context(CommandAuthorizationVerdict.authorized("qo.price.manage"));

        assertThat(authorityDuring(phase, ctx)).isNull();
    }

    @Test
    @DisplayName("the scope closes when the handler stage returns")
    void theScopeClosesAfterTheStage() {
        HandlerPhase phase = phaseWithAuthorityEnabled(true);
        CommandPipelineContext ctx = context(CommandAuthorizationVerdict.authorized("qo.price.manage"));

        authorityDuring(phase, ctx);

        assertThat(MetaContext.hasCommandAuthority()).isFalse();
    }

    private String authorityDuring(HandlerPhase phase, CommandPipelineContext ctx) {
        AtomicReference<String> seen = new AtomicReference<>();
        phase.withCommandAuthority(ctx, () -> {
            seen.set(MetaContext.getCommandAuthority());
            return null;
        });
        return seen.get();
    }

    /**
     * Only the authority decision is under test, so the phase is built without its collaborators —
     * {@code withCommandAuthority} touches none of them. Constructing the real bean graph here would
     * test Spring wiring, not the property this class is about.
     */
    private HandlerPhase phaseWithAuthorityEnabled(boolean enabled) {
        HandlerPhase phase = new HandlerPhase(
                null, null, null, null, null, null, null, null, null);
        ReflectionTestUtils.setField(phase, "commandDataAuthorityEnabled", enabled);
        return phase;
    }

    private CommandPipelineContext context(CommandAuthorizationVerdict verdict) {
        CommandPipelineContext ctx = CommandPipelineContext.builder()
                .commandCode("qo_quote_common:batch_source_prices")
                .request(new CommandExecuteRequest())
                .tenantId(1L)
                .userId(42L)
                .startTime(System.currentTimeMillis())
                .build();
        ctx.setAuthorizationVerdict(verdict);
        return ctx;
    }
}
