import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { evaluateCondition as evaluateExpressionCondition } from '~/framework/meta/runtime/expression/evaluator';
import { TableBlockRenderer } from '../TableBlockRenderer';

/**
 * Regression: a `table` block's row-action `visibleWhen` must gate PER ROW.
 *
 * The list-page / sub-table renderers (ListPageContent, SubTableViewer) expose the
 * current row as BOTH `row` and `record`, so dozens of shipped pages write
 * `record.<status> === ...` in row-action visibleWhen. TableBlockRenderer used to pass
 * only `row` (record = the page context's record, undefined on a custom workbench), so
 * `record.<field>` resolved to undefined → the action was silently hidden on EVERY row
 * (the green-but-broken found in the S5/S6 work). TableBlockRenderer now aliases
 * `record: row` to match the convention; both `record.` and `row.` gate per-row.
 */
function makeRuntime(data: Record<string, any>): SchemaRuntime {
  const context: Record<string, any> = { locale: 'en-US', t: (k: string) => k, form: {}, global: {}, state: {} };
  const stub = {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: (expr: string, ctx: any = context) => evaluateExpressionCondition(expr, ctx),
      evaluateTemplate: (tpl: string) => tpl,
      evaluateObject: (obj: any) => obj,
    }),
    getDataSourceManager: () => ({
      getData: (id: string) => data[id],
      has: (id: string) => Object.prototype.hasOwnProperty.call(data, id),
      register: vi.fn(),
      reload: vi.fn().mockResolvedValue(undefined),
    }),
    getStateManager: () => ({ updateState: vi.fn(), getContext: () => context }),
    getScopeId: () => 'scope-1',
    getSchema: () => ({ id: 'test_schema', modelCode: 'test_model' }),
  };
  return stub as unknown as SchemaRuntime;
}

const ROWS = [
  { pid: 'r1', status: 'active' },
  { pid: 'r2', status: 'archived' },
];

function tableBlock(visibleWhen: string, code: string): BlockConfig {
  return {
    id: 'tbl',
    blockType: 'table',
    dataSource: 'rows',
    columns: [{ field: 'status', label: 'Status' }],
    rowActions: [
      { code, label: code, visibleWhen, action: { type: 'command', command: 'x:act' } },
    ],
  } as unknown as BlockConfig;
}

describe('TableBlockRenderer — row-action record alias (per-row gating)', () => {
  it('record.<field> in visibleWhen gates per-row (shows only on the matching row)', () => {
    render(<TableBlockRenderer block={tableBlock("record.status === 'active'", 'archive')} runtime={makeRuntime({ rows: ROWS })} />);
    // both rows render; the action appears only on the single 'active' row.
    expect(screen.getByTestId('table-row-r1')).toBeTruthy();
    expect(screen.getByTestId('table-row-r2')).toBeTruthy();
    expect(screen.getAllByTestId('row-action-archive')).toHaveLength(1);
  });

  it('row.<field> still works (backward compatible) and gates per-row', () => {
    render(<TableBlockRenderer block={tableBlock("row.status === 'archived'", 'restore')} runtime={makeRuntime({ rows: ROWS })} />);
    expect(screen.getAllByTestId('row-action-restore')).toHaveLength(1);
  });
});
