import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { evaluateCondition as evaluateExpressionCondition } from '~/framework/meta/runtime/expression/evaluator';
import { TableBlockRenderer } from '../TableBlockRenderer';

/**
 * Inline cell editing on a workbench `table` block.
 *
 * ListTable has offered this for kind:list pages through the same InlineEditCell all along, but a
 * blockType:table renders through TableBlockRenderer and had no path to it — the same JSON shape
 * behaving differently depending on which page kind it sat on.
 *
 * Two properties matter beyond "it saves":
 *  - the write is a command, because on a workbench block a backend write always is;
 *  - the payload names the MODEL field, not the projected column. These tables read namedQueries
 *    whose column names are projections (`qty_per_set`), so writing the column name back would
 *    address a field that does not exist on the model.
 */

const executeSimpleWorkbenchAction = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));

vi.mock('../workbenchBlockUtils', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../workbenchBlockUtils')>();
  return { ...actual, executeSimpleWorkbenchAction };
});

function makeRuntime(data: Record<string, any>): SchemaRuntime {
  const context: Record<string, any> = {
    locale: 'en-US',
    t: (k: string) => k,
    form: {},
    global: {},
    state: {},
  };
  const stub = {
    getContext: () => context,
    getEvaluator: () => ({
      evaluateCondition: (expr: string, ctx: any = context) =>
        evaluateExpressionCondition(expr, ctx),
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

const ROWS = [{ pid: 'LINE-1', qty_per_set: 2, bom_qty: 200 }];

function block(inlineEdit: any, editable = true): BlockConfig {
  return {
    id: 'waterfall',
    blockType: 'table',
    dataSource: 'rows',
    table: {
      rowKey: 'pid',
      inlineEdit,
      columns: [
        { field: 'qty_per_set', label: 'Qty/Set', editable, editField: 'qo_ql_qty_per_set' },
        { field: 'bom_qty', label: 'Total Qty' },
      ],
    },
    columns: [
      { field: 'qty_per_set', label: 'Qty/Set', editable, editField: 'qo_ql_qty_per_set' },
      { field: 'bom_qty', label: 'Total Qty' },
    ],
  } as unknown as BlockConfig;
}

describe('TableBlockRenderer — inline cell editing', () => {
  beforeEach(() => {
    executeSimpleWorkbenchAction.mockClear();
  });

  it('writes the model field named by editField, through a command, for the edited row', async () => {
    render(
      <TableBlockRenderer
        block={block({
          command: 'qo_quote_line_common:update',
          reload: ['bomPriceWaterfall', 'bomPriceMetrics'],
        })}
        runtime={makeRuntime({ rows: ROWS })}
      />,
    );

    const cell = screen.getByText('2');
    fireEvent.doubleClick(cell);
    const input = (await screen.findByDisplayValue('2')) as HTMLInputElement;
    fireEvent.change(input, { target: { value: '5' } });
    fireEvent.keyDown(input, { key: 'Enter' });

    await waitFor(() => expect(executeSimpleWorkbenchAction).toHaveBeenCalledTimes(1));
    const [, config] = executeSimpleWorkbenchAction.mock.calls[0];
    expect(config.action).toBe('command.execute');
    expect(config.args.command).toBe('qo_quote_line_common:update');
    expect(config.args.targetRecordPid).toBe('LINE-1');
    // qty_per_set is the namedQuery's projection; qo_ql_qty_per_set is the field on the model.
    expect(config.args.payload).toEqual({ qo_ql_qty_per_set: '5' });
    expect(config.args.reload).toEqual(['bomPriceWaterfall', 'bomPriceMetrics']);
  });

  it('stays read-only when the table declares no inlineEdit command', async () => {
    render(<TableBlockRenderer block={block(undefined)} runtime={makeRuntime({ rows: ROWS })} />);

    fireEvent.doubleClick(screen.getByText('2'));

    expect(screen.queryByDisplayValue('2')).toBeNull();
    expect(executeSimpleWorkbenchAction).not.toHaveBeenCalled();
  });

  it('leaves columns that did not opt in read-only', async () => {
    render(
      <TableBlockRenderer
        block={block({ command: 'qo_quote_line_common:update' })}
        runtime={makeRuntime({ rows: ROWS })}
      />,
    );

    fireEvent.doubleClick(screen.getByText('200'));

    expect(screen.queryByDisplayValue('200')).toBeNull();
    expect(executeSimpleWorkbenchAction).not.toHaveBeenCalled();
  });
});
