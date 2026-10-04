package com.auraboot.framework.semantic.compiler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticExpressionReferencesTest {
    @Test
    void followsScalarAstWithoutTreatingLiteralsFunctionsOrTypesAsColumns() {
        assertThat(SemanticExpressionReferences.resolve(
                "CASE WHEN status = 'salary' THEN COALESCE(amount, discount)::numeric ELSE unit_cost END"))
                .containsExactlyInAnyOrder("status", "amount", "discount", "unit_cost");
    }

    @Test
    void retainsQuotedIdentifierCaseAndFoldsUnquotedIdentifiers() {
        assertThat(SemanticExpressionReferences.resolve("AMOUNT + \"Salary\" + source.discount"))
                .containsExactlyInAnyOrder("amount", "Salary", "discount");
    }

    @Test
    void extractsPredicateColumnsAndExcludesParametersAndStringValues() {
        assertThat(SemanticExpressionReferences.resolve("tenant_id = ? AND region IN (?, ?) AND secret <> 'amount'"))
                .containsExactlyInAnyOrder("tenant_id", "region", "secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "source.*", "amount, salary", "amount FROM private_table", "(SELECT salary FROM private_table)",
            "amount ORDER BY salary", "amount LIMIT 1", "amount OFFSET 1", "amount +",
            "amount HAVING salary > 0", "amount INTO private_table", "DISTINCT amount", "amount FOR UPDATE"})
    void refusesUnresolvedOrNonScalarExpressions(String expression) {
        assertThatThrownBy(() -> SemanticExpressionReferences.resolve(expression))
                .isInstanceOf(MetricCompileException.class)
                .hasMessageContaining("unresolved column provenance");
    }
}
