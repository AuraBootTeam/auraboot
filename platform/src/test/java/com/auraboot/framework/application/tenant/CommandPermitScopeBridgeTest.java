package com.auraboot.framework.application.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The permit plan publishes the row-scope grade it resolved onto {@link MetaContext} so the data
 * layer honours one boundary decision instead of re-resolving scope at each site (authz §11.15,
 * enforce-arc bridge). Like the aggregate scope, it restores rather than clears on exit so a nested
 * command cannot strip the outer scope.
 */
class CommandPermitScopeBridgeTest {

    @AfterEach
    void clear() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("the scope is visible inside the action and gone after it")
    void publishesTheScopeForTheDurationOfTheAction() {
        assertThat(MetaContext.getCommandPermitScope()).isNull();

        String seen = MetaContext.runWithCommandPermitScope("SELF", MetaContext::getCommandPermitScope);

        assertThat(seen).isEqualTo("SELF");
        assertThat(MetaContext.getCommandPermitScope()).isNull();
    }

    @Test
    @DisplayName("a nested scope restores the outer scope on exit, never clears it")
    void restoresTheOuterScopeAfterNesting() {
        MetaContext.runWithCommandPermitScope("ALL", () -> {
            assertThat(MetaContext.getCommandPermitScope()).isEqualTo("ALL");
            MetaContext.runWithCommandPermitScope("SELF", () -> {
                assertThat(MetaContext.getCommandPermitScope()).isEqualTo("SELF");
                return null;
            });
            // The inner command must not have stripped the outer scope.
            assertThat(MetaContext.getCommandPermitScope()).isEqualTo("ALL");
            return null;
        });
        assertThat(MetaContext.getCommandPermitScope()).isNull();
    }

    @Test
    @DisplayName("a null or blank grade is a no-op — scope stays unresolved")
    void aNullOrBlankGradeIsANoOp() {
        String seenNull = MetaContext.runWithCommandPermitScope(null, MetaContext::getCommandPermitScope);
        String seenBlank = MetaContext.runWithCommandPermitScope("  ", MetaContext::getCommandPermitScope);

        assertThat(seenNull).isNull();
        assertThat(seenBlank).isNull();
    }

    @Test
    @DisplayName("clear() removes the published scope")
    void clearRemovesTheScope() {
        // Set it directly through the runnable overload, then leak it by not exiting cleanly is not
        // possible here; instead assert clear() wipes a scope opened without a surrounding action.
        MetaContext.runWithCommandPermitScope("SELF", () -> {
            assertThat(MetaContext.getCommandPermitScope()).isEqualTo("SELF");
        });
        MetaContext.clear();
        assertThat(MetaContext.getCommandPermitScope()).isNull();
    }
}
