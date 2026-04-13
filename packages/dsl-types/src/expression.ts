export type ExpressionOp =
  | 'eq' | 'ne' | 'in' | 'notIn'
  | 'gt' | 'gte' | 'lt' | 'lte'
  | 'contains' | 'startsWith' | 'endsWith'
  | 'isNull' | 'isNotNull'
  | 'and' | 'or' | 'not'

/**
 * Declarative expression — preferred form. Composable, safe, statically
 * analyzable. The runtime evaluates these against `record` / `user` / `ctx`.
 */
export interface DeclarativeExpression {
  field?: string
  op: ExpressionOp
  value?: unknown
  args?: DeclarativeExpression[]
}

/**
 * Expression — declarative form only in v0.x. A future minor may add a
 * sandboxed string form, but raw JS eval is never permitted.
 */
export type Expression = DeclarativeExpression
