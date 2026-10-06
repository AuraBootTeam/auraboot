package com.auraboot.framework.application.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Command permit execution context")
class MetaContextCommandPermitTest {

    @AfterEach
    void clearContext() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("publishes scope and server-captured version, then closes both")
    void publishesScopeAndVersionThenCloses() {
        MetaContext.runWithCommandPermitPlan("SELF", 7L, "quote", "q-42", () -> {
            assertThat(MetaContext.getCommandPermitScope()).isEqualTo("SELF");
            assertThat(MetaContext.getCommandPermitScopeFor("quote")).isEqualTo("SELF");
            assertThat(MetaContext.hasCommandPermitScopeFor("quote")).isTrue();
            assertThat(MetaContext.getCommandPermitScopeFor("shared_config")).isNull();
            assertThat(MetaContext.hasCommandPermitScopeFor("shared_config")).isFalse();
            assertThat(MetaContext.getCommandExpectedVersion("quote", "q-42")).isEqualTo(7L);
            assertThat(MetaContext.getCommandExpectedVersion("quote_line", "q-42")).isNull();
        });

        assertThat(MetaContext.getCommandPermitScope()).isNull();
        assertThat(MetaContext.getCommandPermitScopeFor("quote")).isNull();
        assertThat(MetaContext.getCommandExpectedVersion("quote", "q-42")).isNull();
    }

    @Test
    @DisplayName("legacy explicit scope remains global because it has no target model")
    void explicitScopeRemainsGlobal() {
        MetaContext.runWithCommandPermitScope("SELF", () -> {
            assertThat(MetaContext.getCommandPermitScopeFor("quote")).isEqualTo("SELF");
            assertThat(MetaContext.getCommandPermitScopeFor("shared_config")).isEqualTo("SELF");
        });
    }

    @Test
    @DisplayName("nested explicit ALL work restores the outer SELF plan and version")
    void nestedAllRestoresOuterPlan() {
        AtomicReference<String> nestedScope = new AtomicReference<>();

        MetaContext.runWithCommandPermitPlan("SELF", 3L, "quote", "q-9", () -> {
            MetaContext.runWithCommandPermitScope("ALL", () ->
                    nestedScope.set(MetaContext.getCommandPermitScope()));

            assertThat(MetaContext.getCommandPermitScope()).isEqualTo("SELF");
            assertThat(MetaContext.getCommandExpectedVersion("quote", "q-9")).isEqualTo(3L);
        });

        assertThat(nestedScope).hasValue("ALL");
    }

    @Test
    @DisplayName("advancing the target version affects only the matching target")
    void advancesOnlyMatchingTarget() {
        MetaContext.runWithCommandPermitPlan("ALL", 11L, "quote", "q-11", () -> {
            MetaContext.advanceCommandExpectedVersion("quote_line", "q-11");
            assertThat(MetaContext.getCommandExpectedVersion("quote", "q-11")).isEqualTo(11L);

            MetaContext.advanceCommandExpectedVersion("quote", "q-11");
            assertThat(MetaContext.getCommandExpectedVersion("quote", "q-11")).isEqualTo(12L);
        });
    }
    @Test
    void independentSourceReadRestoresOuterPlanAndIdentity() {
        MetaContext.setContext(1L, 2L, "user", "Actor");
        MetaContext.setMemberId(3L);
        MetaContext.runWithCommandPermitPlan("ALL", 7L, "asset", "record", () -> {
            String value = MetaContext.runWithoutCommandPermit(() -> {
                assertThat(MetaContext.hasCommandPermitScope()).isFalse();
                assertThat(MetaContext.getCommandExpectedVersion("asset", "record")).isNull();
                assertThat(MetaContext.getCurrentUserId()).isEqualTo(2L);
                assertThat(MetaContext.getCurrentMemberId()).isEqualTo(3L);
                return "source";
            });
            assertThat(value).isEqualTo("source");
            assertThat(MetaContext.getCommandPermitScopeFor("asset")).isEqualTo("ALL");
            assertThat(MetaContext.getCommandExpectedVersion("asset", "record")).isEqualTo(7L);
        });
        assertThat(MetaContext.hasCommandPermitScope()).isFalse();
    }

    @Test
    void independentSourceFailureAndNestedPermitRestoreExactOuterPlan() {
        MetaContext.runWithCommandPermitPlan("SELF", 11L, "quote", "q-11", () -> {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> MetaContext.runWithoutCommandPermit(() -> {
                        MetaContext.runWithCommandPermitScope("ALL", () -> {
                            assertThat(MetaContext.getCommandPermitScope()).isEqualTo("ALL");
                        });
                        assertThat(MetaContext.hasCommandPermitScope()).isFalse();
                        throw new IllegalStateException("source denied");
                    }));
            assertThat(MetaContext.getCommandPermitScopeFor("quote")).isEqualTo("SELF");
            assertThat(MetaContext.getCommandExpectedVersion("quote", "q-11")).isEqualTo(11L);
        });
        assertThat(MetaContext.hasCommandPermitScope()).isFalse();
    }

    @Test
    void independentSourceReadWithoutOuterPermitDoesNotCreateOne() {
        MetaContext.runWithoutCommandPermit(() -> {
            assertThat(MetaContext.hasCommandPermitScope()).isFalse();
            return null;
        });
        assertThat(MetaContext.hasCommandPermitScope()).isFalse();
    }

}
