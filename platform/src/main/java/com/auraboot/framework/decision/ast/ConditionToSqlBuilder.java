package com.auraboot.framework.decision.ast;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a {@link ConditionNode} AST into a safe SQL {@code WHERE} fragment (no leading
 * {@code AND}), used to enforce row-level data-scope policies whose condition is authored as a
 * structured AST rather than a free-form SQL string.
 *
 * <h2>Why an allowlist</h2>
 * The legacy custom data-scope path stored a raw SQL fragment in {@code scope_expression} and
 * guarded it with a blocklist ({@code SqlSafetyUtils.validateSqlFragment}). This builder is the
 * allowlist replacement: the only things that can appear are (1) column identifiers supplied by
 * {@link Bindings#column(String)} (which a caller validates against
 * {@code SqlSafetyUtils.validateIdentifier} / a field vocabulary), (2) actor/tenant values
 * resolved by {@link Bindings#resolve} to plain Java values, and (3) literals from the AST. Every
 * value is type-rendered (numbers as digits, booleans as keywords) or single-quote escaped, so it
 * can never break out of its position. Anything that cannot be safely translated (function
 * operands, non-record left operands, regex/changed operators, unresolvable values, empty groups)
 * raises {@link RejectedConditionException} — the caller maps that to a fail-closed {@code 1=0}.
 *
 * <h2>Three-valued logic</h2>
 * The AST's UNKNOWN&rarr;deny semantic (only TRUE matches) is preserved by SQL's native NULL
 * propagation: a row whose column is NULL yields NULL for {@code col = x}, which a {@code WHERE}
 * clause treats as not-matching — i.e. denied. No extra NULL guards are emitted.
 */
public final class ConditionToSqlBuilder {

    /** Supplies validated column names and resolves non-record operand values for one evaluation. */
    public interface Bindings {
        /**
         * Map a record-scope path (e.g. {@code data.owner_id}) to a validated physical column name,
         * or {@code null} to reject the condition (fail-closed).
         */
        String column(String recordPath);

        /** Resolve a non-record path operand (actor/tenant/...) to a literal value, or null if unavailable. */
        Object resolve(Scope scope, String path);
    }

    /** Raised when an AST node cannot be safely translated to SQL; the caller denies (1=0). */
    public static final class RejectedConditionException extends RuntimeException {
        public RejectedConditionException(String message) {
            super(message);
        }
    }

    /**
     * Compile {@code root} into a SQL WHERE fragment (without a leading {@code AND}).
     *
     * @throws RejectedConditionException if any node cannot be safely translated
     */
    public String toSql(ConditionNode root, Bindings bindings) {
        if (root == null) {
            throw new RejectedConditionException("null condition");
        }
        return render(root, bindings);
    }

    private String render(ConditionNode node, Bindings bindings) {
        return switch (node) {
            case ConditionNode.GroupNode g -> renderGroup(g, bindings);
            case ConditionNode.NotNode n -> "NOT (" + render(n.child(), bindings) + ")";
            case ConditionNode.CompareNode c -> renderCompare(c, bindings);
        };
    }

    private String renderGroup(ConditionNode.GroupNode g, Bindings bindings) {
        List<String> parts = new ArrayList<>();
        if (g.children() != null) {
            for (ConditionNode child : g.children()) {
                if (child instanceof ConditionNode.CompareNode c && !c.active()) {
                    continue; // a disabled leaf is treated as absent in its group
                }
                parts.add(render(child, bindings));
            }
        }
        if (parts.isEmpty()) {
            throw new RejectedConditionException("empty group has no active children");
        }
        String joiner = g.op() == ConditionNode.BoolOp.OR ? " OR " : " AND ";
        return "(" + String.join(joiner, parts) + ")";
    }

    private String renderCompare(ConditionNode.CompareNode c, Bindings bindings) {
        String column = recordColumn(c.left(), bindings);
        Operator op = c.operator();
        return switch (op) {
            case IS_NULL -> column + " IS NULL";
            case IS_NOT_NULL -> column + " IS NOT NULL";
            case IS_EMPTY -> "(" + column + " IS NULL OR " + column + " = '')";
            case IS_NOT_EMPTY -> "(" + column + " IS NOT NULL AND " + column + " <> '')";
            case EQ -> column + " = " + scalar(c.right(), bindings);
            case NE -> column + " <> " + scalar(c.right(), bindings);
            case GT -> column + " > " + scalar(c.right(), bindings);
            case GTE -> column + " >= " + scalar(c.right(), bindings);
            case LT -> column + " < " + scalar(c.right(), bindings);
            case LTE -> column + " <= " + scalar(c.right(), bindings);
            case IN -> column + " IN (" + renderList(c.right(), bindings) + ")";
            case NOT_IN -> column + " NOT IN (" + renderList(c.right(), bindings) + ")";
            case BETWEEN -> renderBetween(column, c.right(), bindings);
            case CONTAINS_TEXT -> like(column, c.right(), bindings, true, true);
            case STARTS_WITH -> like(column, c.right(), bindings, false, true);
            case ENDS_WITH -> like(column, c.right(), bindings, true, false);
            case CONTAINS_ELEMENT, MATCHES, CHANGED ->
                    throw new RejectedConditionException("operator not translatable to row-filter SQL: " + op.code());
        };
    }

    private String recordColumn(Operand left, Bindings bindings) {
        if (left instanceof Operand.PathOperand p && p.scope() == Scope.RECORD) {
            String col = bindings.column(p.path());
            if (col == null) {
                throw new RejectedConditionException("rejected record column: " + p.path());
            }
            return col;
        }
        throw new RejectedConditionException("left operand must be a record column");
    }

    /** Resolve a scalar operand to a rendered SQL literal token. */
    private String scalar(Operand operand, Bindings bindings) {
        return renderValue(resolveValue(operand, bindings));
    }

    private Object resolveValue(Operand operand, Bindings bindings) {
        if (operand == null) {
            throw new RejectedConditionException("missing operand");
        }
        return switch (operand) {
            case Operand.LiteralOperand lit -> lit.value();
            case Operand.PathOperand p -> {
                if (p.scope() == Scope.RECORD) {
                    // record-to-record column comparison is not supported on the value side
                    throw new RejectedConditionException("record column not allowed as a value operand");
                }
                Object v = bindings.resolve(p.scope(), p.path());
                if (v == null) {
                    throw new RejectedConditionException("unresolvable operand: " + p.scope().code() + "." + p.path());
                }
                yield v;
            }
            case Operand.FunctionCallOperand f ->
                    throw new RejectedConditionException("function operand not allowed in row-filter SQL: " + f.name());
        };
    }

    private String renderList(Operand operand, Bindings bindings) {
        List<?> items = asList(resolveValue(operand, bindings));
        if (items.isEmpty()) {
            throw new RejectedConditionException("empty list operand");
        }
        List<String> rendered = new ArrayList<>(items.size());
        for (Object item : items) {
            rendered.add(renderValue(item));
        }
        return String.join(", ", rendered);
    }

    private String renderBetween(String column, Operand operand, Bindings bindings) {
        List<?> bounds = asList(resolveValue(operand, bindings));
        if (bounds.size() != 2) {
            throw new RejectedConditionException("BETWEEN requires a 2-element list");
        }
        return column + " BETWEEN " + renderValue(bounds.get(0)) + " AND " + renderValue(bounds.get(1));
    }

    private String like(String column, Operand operand, Bindings bindings, boolean leadingPct, boolean trailingPct) {
        Object v = resolveValue(operand, bindings);
        if (!(v instanceof CharSequence)) {
            throw new RejectedConditionException("LIKE operand must be text");
        }
        String pattern = (leadingPct ? "%" : "") + escapeLike(v.toString()) + (trailingPct ? "%" : "");
        return column + " LIKE '" + sqlEscape(pattern) + "' ESCAPE '\\'";
    }

    private static List<?> asList(Object v) {
        if (v instanceof List<?> l) {
            return l;
        }
        throw new RejectedConditionException("expected a list operand");
    }

    private String renderValue(Object value) {
        if (value == null) {
            throw new RejectedConditionException("null value operand");
        }
        if (value instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (value instanceof Number n) {
            return toBigDecimal(n).toPlainString();
        }
        if (value instanceof List<?>) {
            throw new RejectedConditionException("unexpected list in scalar position");
        }
        return "'" + sqlEscape(String.valueOf(value)) + "'";
    }

    private static BigDecimal toBigDecimal(Number n) {
        return n instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
    }

    private static String sqlEscape(String s) {
        return s.replace("'", "''");
    }

    /** Escape LIKE metacharacters with backslash; the caller appends {@code ESCAPE '\'}. */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
