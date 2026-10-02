import { createExpressionParser } from './parser';
import type { ExpressionContext } from './context';

/**
 * Minimal built-ins for scoped evaluation. Deliberately deny-all on
 * hasPermission: scoped expressions are data conditions, not authorization
 * checks — an expression that calls hasPermission should not silently pass.
 */
const BASE_CONTEXT = {
    locale: 'zh-CN',
    hasPermission: () => false,
    hasRole: () => false,
    formatDate: (value: unknown): string =>
        value instanceof Date ? value.toISOString() : String(value ?? ''),
    formatCurrency: (value: unknown): string => String(value ?? ''),
};

/**
 * Evaluate a DSL/row expression inside the sandboxed AST interpreter with an
 * ad-hoc data record as the identifier scope.
 *
 * This is the ONLY sanctioned path for ad-hoc expression evaluation. Direct
 * `new Function` compilation bypasses FORBIDDEN_GLOBALS and is a script
 * injection vector when the expression is user-editable (tenant-exemption
 * campaign sibling finding, 2026-10-01).
 *
 * The interpreter resolves identifiers via `name in context`, so spreading the
 * data record over the minimal base makes every data key addressable while
 * unknown identifiers stay lenient (undefined) — matching the previous
 * `new Function(...keys, ...)` semantics.
 */
export function evaluateScopedExpression(
    expr: string,
    data: Record<string, unknown> = {},
    options: { strict?: boolean } = {},
): unknown {
    const context: ExpressionContext = options.strict
        ? strictContext(data)
        : ({ ...BASE_CONTEXT, ...data } as unknown as ExpressionContext);
    return createExpressionParser(context).evaluate(expr);
}

/**
 * Strict context for engines whose contract REQUIRES expression errors to
 * throw (fallbackValue/stale/onError handling): the parser's `name in context`
 * probe always succeeds (has trap), and unknown fields throw instead of
 * silently evaluating to undefined. Must be used AS the context object —
 * spreading a proxy would silently strip the traps.
 */
function strictContext(
    data: Record<string, unknown>,
): ExpressionContext {
    const target: Record<string, unknown> = Object.assign(
        Object.create(null),
        BASE_CONTEXT,
        data,
    );
    return new Proxy(target, {
        has: () => true,
        get(t, key) {
            if (typeof key === 'symbol') return undefined;
            if (key in t) return t[key as string];
            throw new Error(`Unknown field: ${String(key)}`);
        },
    }) as unknown as ExpressionContext;
}

/**
 * Boolean variant for condition/assert evaluation. Errors are OBSERVABLE but
 * non-throwing: logged with the offending expression and resolved to `false`
 * (deny). Validators must not silently skip on broken expressions.
 */
export function evaluateScopedCondition(
    expr: string,
    data: Record<string, unknown> = {},
): boolean {
    try {
        return !!evaluateScopedExpression(expr, data);
    } catch (error) {
        console.error(
            `[expression] scoped condition evaluation failed (→ false/deny): ${expr}`,
            error instanceof Error ? error.message : error,
        );
        return false;
    }
}
