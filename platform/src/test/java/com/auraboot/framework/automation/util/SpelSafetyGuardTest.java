package com.auraboot.framework.automation.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for SpelSafetyGuard.
 */
class SpelSafetyGuardTest {

    // =========================================================
    // isSafe — null / blank / normal
    // =========================================================

    @Test
    void isSafe_null_returnsTrue() {
        assertThat(SpelSafetyGuard.isSafe(null)).isTrue();
    }

    @Test
    void isSafe_emptyString_returnsTrue() {
        assertThat(SpelSafetyGuard.isSafe("")).isTrue();
    }

    @Test
    void isSafe_simpleComparisonExpression_returnsTrue() {
        assertThat(SpelSafetyGuard.isSafe("#status == 'active'")).isTrue();
    }

    @Test
    void isSafe_numericComparison_returnsTrue() {
        assertThat(SpelSafetyGuard.isSafe("#amount > 100")).isTrue();
    }

    @Test
    void isSafe_booleanLiteral_returnsTrue() {
        assertThat(SpelSafetyGuard.isSafe("true")).isTrue();
        assertThat(SpelSafetyGuard.isSafe("false")).isTrue();
    }

    // =========================================================
    // isSafe — length limit
    // =========================================================

    @Test
    void isSafe_exactlyMaxLength_returnsTrue() {
        String expr = "a".repeat(SpelSafetyGuard.MAX_EXPRESSION_LENGTH);
        assertThat(SpelSafetyGuard.isSafe(expr)).isTrue();
    }

    @Test
    void isSafe_oneOverMaxLength_returnsFalse() {
        String expr = "a".repeat(SpelSafetyGuard.MAX_EXPRESSION_LENGTH + 1);
        assertThat(SpelSafetyGuard.isSafe(expr)).isFalse();
    }

    @Test
    void isSafe_wellOverMaxLength_returnsFalse() {
        String longExpr = "true".repeat(200); // 800 chars > 500
        assertThat(SpelSafetyGuard.isSafe(longExpr)).isFalse();
    }

    // =========================================================
    // isSafe — dangerous patterns
    // =========================================================

    @ParameterizedTest
    @ValueSource(strings = {
        "T(java.lang.Runtime).getRuntime().exec('ls')",
        "T(Runtime).getRuntime()",
        "new java.lang.ProcessBuilder('ls').start()",
        "new String('x')",
        "#root.getClass()",
        "#this.getClass()",
        "T(System).exit(0)",
        "T(Thread).currentThread()",
        "T(Class).forName('java.lang.Runtime')",
        "T(java.lang.reflect.Method).invoke(null)",
        "'foo'.class.forName('java.lang.Runtime')",
        "T(java.lang.Runtime).exec('cmd')",
    })
    void isSafe_dangerousExpression_returnsFalse(String expression) {
        assertThat(SpelSafetyGuard.isSafe(expression))
                .as("Expected expression to be rejected: %s", expression)
                .isFalse();
    }

    // =========================================================
    // requireSafe — throws on unsafe
    // =========================================================

    @Test
    void requireSafe_safeExpression_doesNotThrow() {
        assertThatNoException().isThrownBy(() ->
                SpelSafetyGuard.requireSafe("#status == 'active'"));
    }

    @Test
    void requireSafe_dangerousExpression_throwsIllegalArgument() {
        assertThatThrownBy(() ->
                SpelSafetyGuard.requireSafe("T(java.lang.Runtime).getRuntime()"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsafe SpEL expression rejected");
    }

    @Test
    void requireSafe_overLength_throwsIllegalArgument() {
        String longExpr = "a".repeat(SpelSafetyGuard.MAX_EXPRESSION_LENGTH + 1);
        assertThatThrownBy(() -> SpelSafetyGuard.requireSafe(longExpr))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
