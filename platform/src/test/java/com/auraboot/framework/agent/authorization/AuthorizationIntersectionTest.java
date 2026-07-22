package com.auraboot.framework.agent.authorization;

import com.auraboot.framework.agent.service.StepContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The authorization decision is now computed rather than asserted.
 *
 * <p>It used to return a grant for everything and write a row claiming a policy
 * had allowed it. Nothing had evaluated anything, and the constraints that were
 * genuinely enforced — the capability ceiling, the tool ACL, the approval gate —
 * appeared in the record not at all. An audit that cannot distinguish "allowed"
 * from "never asked" answers the question confidently and wrongly.
 *
 * <p>The intersection is taken over the sources that are configured. An absent
 * source contributes no constraint, which is exactly the behaviour the runtime
 * has always had: this narrows nothing that was previously wide. Turning absence
 * into denial is a defensible policy and a breaking one, so it stays a decision
 * somebody takes on purpose rather than a side effect of computing an
 * intersection.
 */
@DisplayName("Authorization intersects the constraints that exist")
class AuthorizationIntersectionTest {

    private final DefaultRuntimeAuthorizationService service = new DefaultRuntimeAuthorizationService(
            mock(JdbcTemplate.class), new com.fasterxml.jackson.databind.ObjectMapper());

    private RuntimeAuthorizationService.ToolCallIntent intent(Set<EffectClass> effects) {
        return new RuntimeAuthorizationService.ToolCallIntent(
                7L, "run-1", 0, 0, "cmd:crm:delete_account", "crm.delete",
                "plan-hash", effects, BlastRadius.IRREVERSIBLE, "argh", Map.of(), "sess-1");
    }

    @AfterEach
    void clearCeiling() {
        StepContext.clearCapabilityCeiling();
    }

    @ParameterizedTest(name = "{0} ceiling refuses a mutating effect")
    @ValueSource(strings = {"READ_ONLY", "PROPOSE_ONLY", "NO_TOOLS"})
    void ceilingRefusesMutatingEffects(String ceiling) {
        StepContext.setCapabilityCeiling(ceiling);

        var decision = service.authorizeIncremental(intent(Set.of(EffectClass.WRITE_PLATFORM_STATE)));

        assertThat(decision.granted())
                .as("a ceiling that exists to keep writes out must keep writes out here too")
                .isFalse();
        assertThat(decision.rejectedReason()).contains(ceiling);
    }

    @ParameterizedTest(name = "{0} ceiling still permits reads")
    @ValueSource(strings = {"READ_ONLY", "PROPOSE_ONLY"})
    void ceilingStillPermitsReads(String ceiling) {
        // The control. A rule that refused everything would satisfy the case
        // above while making a read-only agent useless, which is the failure
        // mode of over-tightening rather than under-tightening.
        StepContext.setCapabilityCeiling(ceiling);

        assertThat(service.authorizeIncremental(intent(Set.of(EffectClass.READ_PLATFORM_DATA))).granted())
                .isTrue();
    }

    @Test
    @DisplayName("a write-capable ceiling permits writes")
    void writeCapableCeilingPermitsWrites() {
        StepContext.setCapabilityCeiling("WRITE_CAPABLE");

        assertThat(service.authorizeIncremental(intent(Set.of(EffectClass.WRITE_PLATFORM_STATE))).granted())
                .isTrue();
    }

    @Test
    @DisplayName("with no ceiling in scope nothing is narrowed — the behaviour that already existed")
    void absentCeilingChangesNothing() {
        // This is the compatibility guarantee. A deployment where nothing sets a
        // ceiling must behave exactly as it did before this class learned to
        // compute anything; if that ever stops being true, this test is what
        // notices, rather than someone's tools silently disappearing.

        assertThat(service.authorizeIncremental(intent(Set.of(
                EffectClass.WRITE_PLATFORM_STATE, EffectClass.EXTERNAL_NETWORK))).granted())
                .isTrue();
    }
}
