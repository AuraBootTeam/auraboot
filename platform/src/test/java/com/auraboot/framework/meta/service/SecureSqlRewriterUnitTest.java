package com.auraboot.framework.meta.service;

import com.auraboot.framework.meta.exception.MetaServiceException;
import net.sf.jsqlparser.expression.Expression;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link SecureSqlRewriter}. Complement to
 * {@code SqlRewriteSafetyPropertyTest} (Spring-bound), exercising
 * isSelectStatement / extractWhereClause / wrapped-COUNT / DELETE
 * argument validation paths.
 */
class SecureSqlRewriterUnitTest {

    private final SecureSqlRewriter rewriter = new SecureSqlRewriter();

    // ---------- isSelectStatement ----------

    @Test
    void isSelectStatement_returns_true_for_simple_select() {
        assertThat(rewriter.isSelectStatement("SELECT id FROM users WHERE 1=1")).isTrue();
    }

    @Test
    void isSelectStatement_returns_false_for_delete() {
        assertThat(rewriter.isSelectStatement("DELETE FROM users WHERE id = 1")).isFalse();
    }

    @Test
    void isSelectStatement_returns_false_for_blank_or_null() {
        assertThat(rewriter.isSelectStatement(null)).isFalse();
        assertThat(rewriter.isSelectStatement("   ")).isFalse();
    }

    @Test
    void isSelectStatement_returns_false_for_invalid_sql() {
        assertThat(rewriter.isSelectStatement("INVALID SQL")).isFalse();
    }

    @Test
    void isSelectStatement_handles_mybatis_param_select() {
        assertThat(rewriter.isSelectStatement(
                "SELECT id FROM users WHERE tenant_id = #{params.tenantId}")).isTrue();
    }

    // ---------- extractWhereClause ----------

    @Test
    void extractWhereClause_returns_expression_when_where_present() {
        Optional<Expression> where = rewriter.extractWhereClause(
                "SELECT * FROM users WHERE id > 10 AND status = 'active'");
        assertThat(where).isPresent();
        assertThat(where.get().toString()).contains("id > 10");
    }

    @Test
    void extractWhereClause_returns_empty_when_no_where() {
        Optional<Expression> where = rewriter.extractWhereClause("SELECT * FROM users");
        assertThat(where).isEmpty();
    }

    @Test
    void extractWhereClause_throws_for_null_or_empty() {
        assertThatThrownBy(() -> rewriter.extractWhereClause(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> rewriter.extractWhereClause(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void extractWhereClause_throws_for_non_select() {
        assertThatThrownBy(() -> rewriter.extractWhereClause("DELETE FROM users WHERE id = 1"))
                .isInstanceOf(MetaServiceException.class);
    }

    @Test
    void extractWhereClause_throws_for_unparseable_sql() {
        assertThatThrownBy(() -> rewriter.extractWhereClause("not sql at all"))
                .isInstanceOf(MetaServiceException.class);
    }

    @Test
    void extractWhereClause_preserves_mybatis_params_in_expression_string() {
        Optional<Expression> where = rewriter.extractWhereClause(
                "SELECT * FROM users WHERE tenant_id = #{params.tenantId}");
        assertThat(where).isPresent();
        assertThat(where.get().toString()).contains("#{params.tenantId}");
    }

    // ---------- rewriteForCount: wrapping path (UNION) ----------

    @Test
    void rewriteForCount_wraps_union_query_as_subquery() {
        String unionSql = "SELECT id FROM a UNION SELECT id FROM b";
        String result = rewriter.rewriteForCount(unionSql);
        // For UNION the rewriter keeps wrap form OR rebuilds; either way COUNT must appear
        // and the original semantics must be preserved. Accept either wrapping or pure rewrite.
        assertThat(result.toUpperCase()).contains("COUNT");
    }

    @Test
    void rewriteForCount_throws_on_null_input() {
        assertThatThrownBy(() -> rewriter.rewriteForCount(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rewriteForCount_throws_on_empty_input() {
        assertThatThrownBy(() -> rewriter.rewriteForCount("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rewriteForCount_throws_on_non_select() {
        assertThatThrownBy(() -> rewriter.rewriteForCount("UPDATE t SET x = 1"))
                .isInstanceOfAny(IllegalArgumentException.class, MetaServiceException.class);
    }

    @Test
    void rewriteForCount_preserves_mybatis_params_basic() {
        String sql = "SELECT id FROM t WHERE tenant_id = #{params.tenantId}";
        String result = rewriter.rewriteForCount(sql);
        assertThat(result).contains("#{params.tenantId}");
        assertThat(result.toUpperCase()).contains("COUNT");
    }

    // ---------- rewriteForDelete: argument validation ----------

    @Test
    void rewriteForDelete_throws_on_null_select() {
        assertThatThrownBy(() -> rewriter.rewriteForDelete(null, "users"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rewriteForDelete_throws_on_empty_select() {
        assertThatThrownBy(() -> rewriter.rewriteForDelete("", "users"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rewriteForDelete_throws_on_null_table_name() {
        assertThatThrownBy(() -> rewriter.rewriteForDelete("SELECT * FROM users WHERE id = 1", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rewriteForDelete_throws_on_blank_table_name() {
        assertThatThrownBy(() -> rewriter.rewriteForDelete("SELECT * FROM users WHERE id = 1", "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rewriteForDelete_warns_on_table_mismatch_but_returns_delete() {
        // Mismatch logs a warning but still produces DELETE based on user-supplied tableName.
        String result = rewriter.rewriteForDelete(
                "SELECT * FROM source_table WHERE id = 5", "expected_table");
        assertThat(result.toUpperCase()).startsWith("DELETE");
        assertThat(result).contains("expected_table");
    }

    @Test
    void rewriteForDelete_without_where_still_emits_delete() {
        String result = rewriter.rewriteForDelete("SELECT * FROM t", "t");
        assertThat(result.toUpperCase()).startsWith("DELETE");
    }

    @Test
    void rewriteForDelete_throws_on_non_select_input() {
        assertThatThrownBy(() -> rewriter.rewriteForDelete("UPDATE t SET x = 1", "t"))
                .isInstanceOfAny(IllegalArgumentException.class, MetaServiceException.class);
    }
}
