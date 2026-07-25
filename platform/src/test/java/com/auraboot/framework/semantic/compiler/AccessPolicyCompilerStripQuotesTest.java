package com.auraboot.framework.semantic.compiler;

import com.auraboot.framework.semantic.dto.AccessPolicyDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // -- defence-in-depth: DENY denylist at compile time ---------------------

    /**
     * An author with DB write access could tamper with a persisted
     * {@code sql_filter} to smuggle a sub-SELECT past publish-time validation.
     * {@link AccessPolicyCompiler#injectRls} re-checks and must reject a bare
     * {@code SELECT} even though it carries no {@code ;}/{@code UNION}/{@code --}.
     */
    @Test
    void injectRlsRejectsSubquerySelectInSqlFilter() {
        AccessPolicyCompiler compiler = new AccessPolicyCompiler();
        AccessPolicyDTO tampered = new AccessPolicyDTO();
        tampered.setAccessGrant("g");
        tampered.setSqlFilter("region_code IN (SELECT code FROM ab_role)");
        assertThatThrownBy(() -> compiler.injectRls(
                new StringBuilder("1=1"), List.of(tampered), List.of(),
                new UserContext(1L, 1L, Map.of())))
                .isInstanceOf(AccessException.class)
                .satisfies(ex -> assertThat(((AccessException) ex).getErrorCode())
                        .isEqualTo("SQL_INJECTION_DETECTED"));
    }

    /**
     * False-positive guard mirroring the validator: a legitimate
     * {@code {user.<attr>}} filter with no sub-query compiles cleanly.
     */
    @Test
    void injectRlsAllowsPlainUserAttributeFilter() {
        AccessPolicyCompiler compiler = new AccessPolicyCompiler();
        AccessPolicyDTO ok = new AccessPolicyDTO();
        ok.setAccessGrant("g");
        ok.setSqlFilter("owner_user_id = {user.user_id}");
        StringBuilder where = new StringBuilder("1=1");
        List<Object> params = compiler.injectRls(where, List.of(ok), List.of(),
                new UserContext(42L, 1L, Map.of("user_id", "42")));
        assertThat(where.toString()).contains("owner_user_id = ?");
        assertThat(params).containsExactly("42");
    }
}
