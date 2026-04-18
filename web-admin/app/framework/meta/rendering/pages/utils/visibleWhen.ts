import { createExpressionContext } from '~/framework/meta/runtime/expression/context'
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator'

type VisibleWhenRecord = Record<string, unknown>

export interface VisibleWhenScope {
  record?: VisibleWhenRecord
  row?: VisibleWhenRecord
  form?: VisibleWhenRecord
  state?: Record<string, unknown>
}

export function evaluateVisibleWhen(
  condition: string | undefined,
  scope: VisibleWhenScope = {},
): boolean {
  if (!condition) return true

  const record = scope.record ?? scope.row ?? scope.form ?? {}
  const row = scope.row ?? record
  const form = scope.form ?? record

  return evaluateCondition(
    condition,
    createExpressionContext({
      record,
      row,
      form,
      state: scope.state,
    }),
  )
}
