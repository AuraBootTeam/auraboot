package com.auraboot.framework.decision.ast;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates a Condition AST against a {@link DecisionContext} under AuraBoot's three-valued
 * logic (docs/1.md §14). The platform owns this semantic so front-end preview, back-end
 * authority and adapters do not drift.
 *
 * <p>Key rules (docs/1.md §14.7, §14.8):
 * <ul>
 *   <li>missing field → UNKNOWN; present null compared with a value → UNKNOWN</li>
 *   <li>IS_NULL(null)=TRUE, IS_NULL(missing)=TRUE; IS_EMPTY of null/""/[] = TRUE</li>
 *   <li>numeric compare for numeric dataTypes (no implicit string→number elsewhere)</li>
 *   <li>enum/dict compare by code; string compare is case-sensitive, no implicit trim</li>
 *   <li>only TRUE is a match; UNKNOWN is surfaced with reasons, never silently false-matched</li>
 * </ul>
 *
 * <p>An unregistered function or other hard failure throws — the runtime maps that to
 * {@code status=ERROR} rather than masking it as a non-match.
 */
public final class ConditionAstEvaluator {

    private final FunctionRegistry functions;

    public ConditionAstEvaluator(FunctionRegistry functions) {
        this.functions = functions != null ? functions : FunctionRegistry.withDefaults();
    }

    public ConditionAstEvaluator() {
        this(FunctionRegistry.withDefaults());
    }

    public EvalTrace evaluate(ConditionNode root, DecisionContext ctx) {
        EvalTrace.Collector collector = new EvalTrace.Collector();
        Truth result = eval(root, ctx, collector);
        return collector.finish(result);
    }

    private Truth eval(ConditionNode node, DecisionContext ctx, EvalTrace.Collector trace) {
        return switch (node) {
            case ConditionNode.GroupNode g -> evalGroup(g, ctx, trace);
            case ConditionNode.NotNode n -> eval(n.child(), ctx, trace).negate();
            case ConditionNode.CompareNode c -> evalCompare(c, ctx, trace);
        };
    }

    private Truth evalGroup(ConditionNode.GroupNode g, DecisionContext ctx, EvalTrace.Collector trace) {
        List<Truth> childResults = new ArrayList<>();
        if (g.children() != null) {
            for (ConditionNode child : g.children()) {
                // a disabled leaf is treated as absent in its group
                if (child instanceof ConditionNode.CompareNode c && !c.active()) {
                    continue;
                }
                childResults.add(eval(child, ctx, trace));
            }
        }
        if (childResults.isEmpty()) {
            trace.addUnknownReason("empty group has no active children");
            return Truth.UNKNOWN;
        }
        Truth acc = childResults.get(0);
        boolean and = g.op() == ConditionNode.BoolOp.AND;
        for (int i = 1; i < childResults.size(); i++) {
            acc = and ? acc.and(childResults.get(i)) : acc.or(childResults.get(i));
        }
        return acc;
    }

    private Truth evalCompare(ConditionNode.CompareNode c, DecisionContext ctx, EvalTrace.Collector trace) {
        Operator op = c.operator();
        String expr = renderExpr(c);

        if (op == Operator.CHANGED) {
            Truth r = evalChanged(c, ctx);
            trace.addStep(expr, null, null, r);
            return r;
        }

        ResolvedOperand left = resolve(c.left(), ctx);

        // Unary operators inspect presence/nullness directly.
        switch (op) {
            case IS_NULL -> { return step(trace, expr, left.value, null, Truth.of(!left.present || left.value == null)); }
            case IS_NOT_NULL -> { return step(trace, expr, left.value, null, Truth.of(left.present && left.value != null)); }
            case IS_EMPTY -> { return step(trace, expr, left.value, null, Truth.of(isEmpty(left))); }
            case IS_NOT_EMPTY -> { return step(trace, expr, left.value, null, Truth.of(!isEmpty(left))); }
            default -> { /* binary / set / range below */ }
        }

        if (!left.present) {
            trace.addUnknownReason("path not present for operator " + op.code() + " in [" + expr + "]");
            return step(trace, expr, null, null, Truth.UNKNOWN);
        }
        if (left.value == null) {
            trace.addUnknownReason("left value is null compared with a value in [" + expr + "]");
            return step(trace, expr, null, null, Truth.UNKNOWN);
        }

        ResolvedOperand right = resolve(c.right(), ctx);
        Object rv = right.value;
        DataType dt = effectiveType(c.left());
        Truth r = switch (op) {
            case EQ -> Truth.of(valueEquals(left.value, rv, dt));
            case NE -> Truth.of(!valueEquals(left.value, rv, dt));
            case GT -> numericCompare(left.value, rv, cmp -> cmp > 0);
            case GTE -> numericCompare(left.value, rv, cmp -> cmp >= 0);
            case LT -> numericCompare(left.value, rv, cmp -> cmp < 0);
            case LTE -> numericCompare(left.value, rv, cmp -> cmp <= 0);
            case IN -> Truth.of(inSet(left.value, rv, dt));
            case NOT_IN -> Truth.of(!inSet(left.value, rv, dt));
            case BETWEEN -> between(left.value, rv);
            case CONTAINS_TEXT -> Truth.of(String.valueOf(left.value).contains(String.valueOf(rv)));
            case STARTS_WITH -> Truth.of(String.valueOf(left.value).startsWith(String.valueOf(rv)));
            case ENDS_WITH -> Truth.of(String.valueOf(left.value).endsWith(String.valueOf(rv)));
            case CONTAINS_ELEMENT -> Truth.of(left.value instanceof List<?> l && containsByCode(l, rv, dt));
            case MATCHES -> matches(left.value, rv);
            default -> Truth.UNKNOWN;
        };
        return step(trace, expr, left.value, rv, r);
    }

    private Truth step(EvalTrace.Collector trace, String expr, Object left, Object right, Truth r) {
        trace.addStep(expr, left, right, r);
        return r;
    }

    // ── operand resolution ────────────────────────────────────────────────

    private record ResolvedOperand(boolean present, Object value) {}

    private ResolvedOperand resolve(Operand operand, DecisionContext ctx) {
        if (operand == null) {
            return new ResolvedOperand(false, null);
        }
        return switch (operand) {
            case Operand.LiteralOperand lit -> new ResolvedOperand(true, lit.value());
            case Operand.PathOperand p -> {
                DecisionContext.PathValue pv = ctx.resolve(p.scope(), p.path());
                yield new ResolvedOperand(pv.present(), pv.value());
            }
            case Operand.FunctionCallOperand f -> {
                List<Object> args = new ArrayList<>();
                if (f.args() != null) {
                    for (Operand a : f.args()) {
                        args.add(resolve(a, ctx).value());
                    }
                }
                yield new ResolvedOperand(true, functions.invoke(f.name(), args));
            }
        };
    }

    private DataType effectiveType(Operand left) {
        return left == null ? null : left.dataType();
    }

    // ── comparison helpers ────────────────────────────────────────────────

    private boolean isEmpty(ResolvedOperand left) {
        if (!left.present || left.value == null) {
            return true;
        }
        Object v = left.value;
        if (v instanceof String s) {
            return s.isEmpty();
        }
        if (v instanceof List<?> l) {
            return l.isEmpty();
        }
        return false;
    }

    private boolean valueEquals(Object left, Object right, DataType dt) {
        if (dt != null && dt.isNumeric()) {
            BigDecimal a = toBigDecimal(left);
            BigDecimal b = toBigDecimal(right);
            return a != null && b != null && a.compareTo(b) == 0;
        }
        if (dt != null && dt.isCodeCompared()) {
            return Objects.equals(asCode(left), asCode(right));
        }
        // default: case-sensitive, no trim
        return Objects.equals(String.valueOf(left), String.valueOf(right));
    }

    private boolean inSet(Object left, Object right, DataType dt) {
        return containsByCode(DecisionContext.asList(right), left, dt);
    }

    private boolean containsByCode(List<?> list, Object target, DataType dt) {
        for (Object item : list) {
            if (valueEquals(target, item, dt)) {
                return true;
            }
        }
        return false;
    }

    private Truth between(Object left, Object right) {
        List<?> bounds = DecisionContext.asList(right);
        if (bounds.size() != 2) {
            return Truth.UNKNOWN;
        }
        BigDecimal v = toBigDecimal(left);
        BigDecimal lo = toBigDecimal(bounds.get(0));
        BigDecimal hi = toBigDecimal(bounds.get(1));
        if (v == null || lo == null || hi == null) {
            return Truth.UNKNOWN;
        }
        return Truth.of(v.compareTo(lo) >= 0 && v.compareTo(hi) <= 0);
    }

    private interface CmpPredicate { boolean test(int cmp); }

    private Truth numericCompare(Object left, Object right, CmpPredicate predicate) {
        BigDecimal a = toBigDecimal(left);
        BigDecimal b = toBigDecimal(right);
        if (a == null || b == null) {
            return Truth.UNKNOWN; // no implicit string→number coercion
        }
        return Truth.of(predicate.test(a.compareTo(b)));
    }

    private Truth matches(Object left, Object right) {
        if (right == null) {
            return Truth.UNKNOWN;
        }
        try {
            return Truth.of(String.valueOf(left).matches(String.valueOf(right)));
        } catch (RuntimeException e) {
            return Truth.UNKNOWN;
        }
    }

    private Truth evalChanged(ConditionNode.CompareNode c, DecisionContext ctx) {
        if (!(c.left() instanceof Operand.PathOperand p)) {
            return Truth.UNKNOWN;
        }
        DecisionContext.PathValue before = ctx.resolve(Scope.BEFORE, p.path());
        DecisionContext.PathValue after = ctx.resolve(Scope.AFTER, p.path());
        if (!before.present() && !after.present()) {
            return Truth.UNKNOWN;
        }
        return Truth.of(!Objects.equals(before.value(), after.value()));
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String asCode(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static String renderExpr(ConditionNode.CompareNode c) {
        String l = c.left() instanceof Operand.PathOperand p ? p.scope().code() + "." + p.path() : String.valueOf(c.left());
        String r = c.right() instanceof Operand.LiteralOperand lit ? String.valueOf(lit.value()) : "";
        return (l + " " + c.operator().code() + (r.isEmpty() ? "" : " " + r)).trim();
    }
}
