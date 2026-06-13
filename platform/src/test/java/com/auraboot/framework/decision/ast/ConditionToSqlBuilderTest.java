package com.auraboot.framework.decision.ast;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ConditionToSqlBuilder}.
 *
 * <p>The builder compiles a {@link ConditionNode} AST into a safe SQL WHERE fragment using an
 * <em>allowlist</em> model: column identifiers come from {@link ConditionToSqlBuilder.Bindings}
 * (which rejects anything not a valid identifier), literals are type-rendered and single-quote
 * escaped, and only SQL-translatable operators are accepted. Anything untranslatable is rejected
 * (caller maps to a fail-closed {@code 1=0}). This replaces the legacy free-form
 * {@code scope_expression} raw-SQL blocklist path.
 */
class ConditionToSqlBuilderTest {

    private final ConditionToSqlBuilder builder = new ConditionToSqlBuilder();

    /** Test bindings: record path → identity column (strip "data." prefix, reject "evil*"); actor.id → 42. */
    private final ConditionToSqlBuilder.Bindings bindings = new ConditionToSqlBuilder.Bindings() {
        @Override
        public String column(String recordPath) {
            String p = recordPath.startsWith("data.") ? recordPath.substring("data.".length()) : recordPath;
            return p.matches("[a-zA-Z_][a-zA-Z0-9_]*") ? p : null;
        }

        @Override
        public Object resolve(Scope scope, String path) {
            if (scope == Scope.ACTOR && "id".equals(path)) {
                return 42L;
            }
            if (scope == Scope.TENANT && "id".equals(path)) {
                return 7L;
            }
            return null; // unresolvable
        }
    };

    private static Operand record(String path) {
        return new Operand.PathOperand(Scope.RECORD, path, DataType.STRING);
    }

    private static Operand lit(Object value) {
        return new Operand.LiteralOperand(value, null);
    }

    private static Operand actorId() {
        return new Operand.PathOperand(Scope.ACTOR, "id", DataType.USER);
    }

    private static ConditionNode cmp(Operand l, Operator op, Operand r) {
        return ConditionNode.CompareNode.of(l, op, r);
    }

    // ── basic comparisons ─────────────────────────────────────────────────

    @Test
    void eq_recordColumn_to_stringLiteral() {
        String sql = builder.toSql(cmp(record("region"), Operator.EQ, lit("EAST")), bindings);
        assertThat(sql).isEqualTo("region = 'EAST'");
    }

    @Test
    void eq_recordColumn_to_actorPath_resolvesValue() {
        String sql = builder.toSql(cmp(record("owner_id"), Operator.EQ, actorId()), bindings);
        assertThat(sql).isEqualTo("owner_id = 42");
    }

    @Test
    void numericLiteral_renderedWithoutQuotes() {
        String sql = builder.toSql(cmp(record("amount"), Operator.GT, lit(new BigDecimal("1000.50"))), bindings);
        assertThat(sql).isEqualTo("amount > 1000.50");
    }

    @Test
    void booleanLiteral_renderedAsKeyword() {
        String sql = builder.toSql(cmp(record("active"), Operator.EQ, lit(Boolean.TRUE)), bindings);
        assertThat(sql).isEqualTo("active = TRUE");
    }

    @Test
    void comparisonOperators_mapToSqlSymbols() {
        assertThat(builder.toSql(cmp(record("a"), Operator.NE, lit(1)), bindings)).isEqualTo("a <> 1");
        assertThat(builder.toSql(cmp(record("a"), Operator.GTE, lit(1)), bindings)).isEqualTo("a >= 1");
        assertThat(builder.toSql(cmp(record("a"), Operator.LT, lit(1)), bindings)).isEqualTo("a < 1");
        assertThat(builder.toSql(cmp(record("a"), Operator.LTE, lit(1)), bindings)).isEqualTo("a <= 1");
    }

    // ── set / range ───────────────────────────────────────────────────────

    @Test
    void in_rendersParenthesizedList() {
        String sql = builder.toSql(cmp(record("region"), Operator.IN, lit(List.of("EAST", "WEST"))), bindings);
        assertThat(sql).isEqualTo("region IN ('EAST', 'WEST')");
    }

    @Test
    void notIn_rendersNotInList() {
        String sql = builder.toSql(cmp(record("status"), Operator.NOT_IN, lit(List.of("DRAFT"))), bindings);
        assertThat(sql).isEqualTo("status NOT IN ('DRAFT')");
    }

    @Test
    void between_rendersRange() {
        String sql = builder.toSql(cmp(record("amount"), Operator.BETWEEN, lit(List.of(10, 20))), bindings);
        assertThat(sql).isEqualTo("amount BETWEEN 10 AND 20");
    }

    // ── unary ─────────────────────────────────────────────────────────────

    @Test
    void isNull_and_isNotNull() {
        assertThat(builder.toSql(cmp(record("deleted_at"), Operator.IS_NULL, null), bindings))
                .isEqualTo("deleted_at IS NULL");
        assertThat(builder.toSql(cmp(record("owner_id"), Operator.IS_NOT_NULL, null), bindings))
                .isEqualTo("owner_id IS NOT NULL");
    }

    @Test
    void isEmpty_and_isNotEmpty() {
        assertThat(builder.toSql(cmp(record("note"), Operator.IS_EMPTY, null), bindings))
                .isEqualTo("(note IS NULL OR note = '')");
        assertThat(builder.toSql(cmp(record("note"), Operator.IS_NOT_EMPTY, null), bindings))
                .isEqualTo("(note IS NOT NULL AND note <> '')");
    }

    // ── text / LIKE ───────────────────────────────────────────────────────

    @Test
    void containsText_rendersEscapedLike() {
        String sql = builder.toSql(cmp(record("name"), Operator.CONTAINS_TEXT, lit("abc")), bindings);
        assertThat(sql).isEqualTo("name LIKE '%abc%' ESCAPE '\\'");
    }

    @Test
    void startsWith_and_endsWith() {
        assertThat(builder.toSql(cmp(record("name"), Operator.STARTS_WITH, lit("abc")), bindings))
                .isEqualTo("name LIKE 'abc%' ESCAPE '\\'");
        assertThat(builder.toSql(cmp(record("name"), Operator.ENDS_WITH, lit("abc")), bindings))
                .isEqualTo("name LIKE '%abc' ESCAPE '\\'");
    }

    @Test
    void containsText_escapesLikeWildcards() {
        String sql = builder.toSql(cmp(record("name"), Operator.CONTAINS_TEXT, lit("50%_x")), bindings);
        assertThat(sql).isEqualTo("name LIKE '%50\\%\\_x%' ESCAPE '\\'");
    }

    // ── boolean structure ─────────────────────────────────────────────────

    @Test
    void andGroup_parenthesized() {
        ConditionNode g = new ConditionNode.GroupNode(ConditionNode.BoolOp.AND, List.of(
                cmp(record("region"), Operator.EQ, lit("EAST")),
                cmp(record("amount"), Operator.GT, lit(100))));
        assertThat(builder.toSql(g, bindings)).isEqualTo("(region = 'EAST' AND amount > 100)");
    }

    @Test
    void orGroup_parenthesized() {
        ConditionNode g = new ConditionNode.GroupNode(ConditionNode.BoolOp.OR, List.of(
                cmp(record("owner_id"), Operator.EQ, actorId()),
                cmp(record("region"), Operator.EQ, lit("WEST"))));
        assertThat(builder.toSql(g, bindings)).isEqualTo("(owner_id = 42 OR region = 'WEST')");
    }

    @Test
    void notNode_negates() {
        ConditionNode n = new ConditionNode.NotNode(cmp(record("region"), Operator.EQ, lit("EAST")));
        assertThat(builder.toSql(n, bindings)).isEqualTo("NOT (region = 'EAST')");
    }

    @Test
    void disabledLeaf_skippedInGroup() {
        ConditionNode disabled = new ConditionNode.CompareNode(null, Boolean.FALSE, record("x"), Operator.EQ, lit(1));
        ConditionNode g = new ConditionNode.GroupNode(ConditionNode.BoolOp.AND, List.of(
                cmp(record("region"), Operator.EQ, lit("EAST")),
                disabled));
        assertThat(builder.toSql(g, bindings)).isEqualTo("(region = 'EAST')");
    }

    // ── security: escaping ────────────────────────────────────────────────

    @Test
    void singleQuoteInLiteral_isEscaped_noInjection() {
        String sql = builder.toSql(cmp(record("name"), Operator.EQ, lit("O'Brien")), bindings);
        assertThat(sql).isEqualTo("name = 'O''Brien'");
    }

    @Test
    void injectionAttemptInLiteral_isNeutralizedByEscaping() {
        String evil = "x'; DROP TABLE ab_user; --";
        String sql = builder.toSql(cmp(record("name"), Operator.EQ, lit(evil)), bindings);
        // The quote is doubled, so the whole payload stays inside one string literal.
        assertThat(sql).isEqualTo("name = 'x''; DROP TABLE ab_user; --'");
    }

    // ── reject (fail-closed) ──────────────────────────────────────────────

    @Test
    void invalidColumnIdentifier_isRejected() {
        Operand evilCol = new Operand.PathOperand(Scope.RECORD, "evil; DROP", DataType.STRING);
        assertThatThrownBy(() -> builder.toSql(cmp(evilCol, Operator.EQ, lit("x")), bindings))
                .isInstanceOf(ConditionToSqlBuilder.RejectedConditionException.class);
    }

    @Test
    void leftOperandNotRecordScope_isRejected() {
        // Only record columns may appear on the left of a row filter.
        assertThatThrownBy(() -> builder.toSql(cmp(actorId(), Operator.EQ, lit(1)), bindings))
                .isInstanceOf(ConditionToSqlBuilder.RejectedConditionException.class);
    }

    @Test
    void functionOperand_isRejected() {
        Operand fn = new Operand.FunctionCallOperand("now", List.of(), DataType.DATETIME);
        assertThatThrownBy(() -> builder.toSql(cmp(record("created_at"), Operator.LT, fn), bindings))
                .isInstanceOf(ConditionToSqlBuilder.RejectedConditionException.class);
    }

    @Test
    void unresolvableActorValue_isRejected() {
        Operand unknown = new Operand.PathOperand(Scope.ACTOR, "department", DataType.STRING);
        assertThatThrownBy(() -> builder.toSql(cmp(record("dept"), Operator.EQ, unknown), bindings))
                .isInstanceOf(ConditionToSqlBuilder.RejectedConditionException.class);
    }

    @Test
    void nonSqlTranslatableOperators_areRejected() {
        assertThatThrownBy(() -> builder.toSql(cmp(record("x"), Operator.CHANGED, null), bindings))
                .isInstanceOf(ConditionToSqlBuilder.RejectedConditionException.class);
        assertThatThrownBy(() -> builder.toSql(cmp(record("x"), Operator.MATCHES, lit(".*")), bindings))
                .isInstanceOf(ConditionToSqlBuilder.RejectedConditionException.class);
    }

    @Test
    void emptyGroup_isRejected() {
        ConditionNode g = new ConditionNode.GroupNode(ConditionNode.BoolOp.AND, List.of());
        assertThatThrownBy(() -> builder.toSql(g, bindings))
                .isInstanceOf(ConditionToSqlBuilder.RejectedConditionException.class);
    }

    @Test
    void nullRoot_isRejected() {
        assertThatThrownBy(() -> builder.toSql(null, bindings))
                .isInstanceOf(ConditionToSqlBuilder.RejectedConditionException.class);
    }
}
