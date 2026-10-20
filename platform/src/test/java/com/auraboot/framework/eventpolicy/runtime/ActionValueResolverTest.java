package com.auraboot.framework.eventpolicy.runtime;

import com.auraboot.framework.decision.ast.DecisionContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionValueResolverTest {
    private final DecisionContext context = DecisionContext.builder().record(Map.of(
            "amount", new BigDecimal("68000.00"), "accepted", true, "name", "Delivery A")).build();

    @Test
    void preservesTypedValuesInNestedCommandParameters() {
        Object result = ActionValueResolver.resolve(Map.of("params", Map.of(
                "amount", "${record.data.amount}", "accepted", "${record.data.accepted}",
                "items", List.of("${record.data.amount}"))), context);
        assertThat(result).isEqualTo(Map.of("params", Map.of("amount", new BigDecimal("68000.00"),
                "accepted", true, "items", List.of(new BigDecimal("68000.00")))));
    }

    @Test
    void rendersBusinessMessageWithoutChangingLiteralText() {
        assertThat(ActionValueResolver.resolve("Review ${record.data.name}", context))
                .isEqualTo("Review Delivery A");
        assertThat(ActionValueResolver.resolve("No variables", context)).isEqualTo("No variables");
    }

    @Test
    void missingReferenceDoesNotSilentlyBecomeNull() {
        assertThatThrownBy(() -> ActionValueResolver.resolve("${record.data.missing}", context))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unavailable");
    }

    @Test
    void doesNotExecuteExpressionsOrFlattenObjectsIntoMessages() {
        assertThatThrownBy(() -> ActionValueResolver.resolve("${record.data.amount + 1}", context))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActionValueResolver.resolve("Data: ${record.data}", context))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
