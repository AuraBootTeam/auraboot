package com.auraboot.framework.semantic.compiler;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for {@link AccessPolicyCompiler#stripQuotes(String)},
 * regression net for Bug 10 (ida/docs/26 §1.10): a user_attribute stored as
 * {@code 'CN','US'} was previously bound as the literal strings {@code 'CN'}
 * and {@code 'US'} (quotes included), which made {@code WHERE col IN (?, ?)}
 * never match anything.
 */
class AccessPolicyCompilerStripQuotesTest {

    @Test
    void stripsSurroundingSingleQuotes() {
        assertThat(AccessPolicyCompiler.stripQuotes("'CN'")).isEqualTo("CN");
        assertThat(AccessPolicyCompiler.stripQuotes("'United States'")).isEqualTo("United States");
    }

    @Test
    void leavesUnquotedValueAlone() {
        assertThat(AccessPolicyCompiler.stripQuotes("CN")).isEqualTo("CN");
        assertThat(AccessPolicyCompiler.stripQuotes("EU")).isEqualTo("EU");
    }

    @Test
    void preservesInnerQuotes() {
        // Don't claim "this is a quoted string" if only one side is quoted
        assertThat(AccessPolicyCompiler.stripQuotes("Co's")).isEqualTo("Co's");
        assertThat(AccessPolicyCompiler.stripQuotes("'Co's")).isEqualTo("'Co's");
    }

    @Test
    void handlesEdgeCases() {
        assertThat(AccessPolicyCompiler.stripQuotes(null)).isNull();
        assertThat(AccessPolicyCompiler.stripQuotes("")).isEqualTo("");
        assertThat(AccessPolicyCompiler.stripQuotes("'")).isEqualTo("'");
        assertThat(AccessPolicyCompiler.stripQuotes("''")).isEqualTo("");
    }
}
