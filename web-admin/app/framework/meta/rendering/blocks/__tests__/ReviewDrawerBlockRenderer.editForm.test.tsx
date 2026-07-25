import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { evaluateCondition as evaluateExpressionCondition } from '~/framework/meta/runtime/expression/evaluator';
import { ReviewDrawerBlockRenderer } from '../ReviewDrawerBlockRenderer';

/**
 * Editing a BOM line from inside the drawer.
 *
 * The waterfall table opens this drawer on a single row click, so double-click-to-edit in the grid
 * fought it — a dblclick is two clicks, each of which opens the drawer first. Editing moved here,
 * beside the line context a reviewer is already looking at. The submit runs a command, and a blank
 * field is left out of the payload so "keep the current value" is expressible.
 */

const executeSimpleWorkbenchAction = vi.hoisted(() => vi.fn().mockResolvedValue(undefined));

vi.mock('../workbenchBlockUtils', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../workbenchBlockUtils')>();
  return { ...actual, executeSimpleWorkbenchAction };
});

function makeRuntime(selectedLine: Record<string, unknown>): SchemaRuntime {
  const context: Record<string, any> = {
    locale: 'zh-CN',
    t: (k: string) => k,
    form: { pid: 'quote-1' },
    global: {},
    state: { selectedLine },
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
      getData: () => [],
      has: () => false,
      register: vi.fn(),
      reload: vi.fn().mockResolvedValue(undefined),
    }),
    getStateManager: () => ({ updateState: vi.fn(), getContext: () => context }),
    getScopeId: () => 'scope-1',
    getSchema: () => ({ id: 'qo_quote_common_detail', modelCode: 'qo_quote_common' }),
  };
  return stub as unknown as SchemaRuntime;
}

function block(): BlockConfig {
  return {
    id: 'qo_quote_review_drawer',
    blockType: 'review-drawer',
    context: '${state.selectedLine}',
    titleTemplate: '${record.material_label}',
    summaryBadges: [],
    editForm: {
      command: 'qo_quote_line_common:edit_and_reprice',
      reload: ['bomPriceWaterfall', 'bomPriceMetrics'],
      openLabel: { 'zh-CN': '编辑此行并重新查价', en: 'Edit this row' },
      submitLabel: { 'zh-CN': '保存并重新查价', en: 'Save and re-price' },
      fields: [
        { field: 'qo_ql_qty_per_set', label: { 'zh-CN': '单套用量' }, type: 'number', valueField: 'qty_per_set' },
        { field: 'qo_ql_mpn', label: { 'zh-CN': 'MPN' }, valueField: 'mpn' },
      ],
    },
  } as unknown as BlockConfig;
}

const LINE = { pid: 'L1', material_label: '1N4148W', qty_per_set: 2, mpn: '1N4148W' };

describe('ReviewDrawerBlockRenderer — inline edit form', () => {
  beforeEach(() => executeSimpleWorkbenchAction.mockClear());

  it('submits changed fields as a command targeting the selected line', async () => {
    render(<ReviewDrawerBlockRenderer block={block()} runtime={makeRuntime(LINE)} />);

    fireEvent.click(screen.getByTestId('review-drawer-edit-open'));
    // seeded from the record
    const perSet = screen.getByTestId('review-drawer-edit-field-qo_ql_qty_per_set').querySelector('input')!;
    expect((perSet as HTMLInputElement).value).toBe('2');

    fireEvent.change(perSet, { target: { value: '5' } });
    fireEvent.click(screen.getByTestId('review-drawer-edit-submit'));

    await waitFor(() => expect(executeSimpleWorkbenchAction).toHaveBeenCalledTimes(1));
    const [, config] = executeSimpleWorkbenchAction.mock.calls[0];
    expect(config.action).toBe('command.execute');
    expect(config.args.command).toBe('qo_quote_line_common:edit_and_reprice');
    expect(config.args.targetRecordPid).toBe('L1');
    expect(config.args.payload).toEqual({ qo_ql_qty_per_set: '5', qo_ql_mpn: '1N4148W' });
    expect(config.args.reload).toEqual(['bomPriceWaterfall', 'bomPriceMetrics']);
  });

  it('omits a field the user cleared, so blank means keep the current value', async () => {
    render(<ReviewDrawerBlockRenderer block={block()} runtime={makeRuntime(LINE)} />);

    fireEvent.click(screen.getByTestId('review-drawer-edit-open'));
    const mpn = screen.getByTestId('review-drawer-edit-field-qo_ql_mpn').querySelector('input')!;
    fireEvent.change(mpn, { target: { value: '' } });
    const perSet = screen.getByTestId('review-drawer-edit-field-qo_ql_qty_per_set').querySelector('input')!;
    fireEvent.change(perSet, { target: { value: '5' } });
    fireEvent.click(screen.getByTestId('review-drawer-edit-submit'));

    await waitFor(() => expect(executeSimpleWorkbenchAction).toHaveBeenCalledTimes(1));
    const [, config] = executeSimpleWorkbenchAction.mock.calls[0];
    expect(config.args.payload).toEqual({ qo_ql_qty_per_set: '5' });
    expect('qo_ql_mpn' in config.args.payload).toBe(false);
  });

  it('refuses to submit when a required field is cleared', async () => {
    // A non-standard BOM's description is Yunhan's search key, so clearing it and submitting would
    // re-price against nothing. Marking the field required blocks that; a blank optional field
    // still means "keep the current value".
    const b = block();
    (b as any).editForm.fields = [
      { field: 'qo_ql_description', label: { 'zh-CN': '规格描述' }, valueField: 'material_label', required: true },
      { field: 'qo_ql_mpn', label: { 'zh-CN': 'MPN' }, valueField: 'mpn' },
    ];
    render(<ReviewDrawerBlockRenderer block={b} runtime={makeRuntime(LINE)} />);

    fireEvent.click(screen.getByTestId('review-drawer-edit-open'));
    const desc = screen.getByTestId('review-drawer-edit-field-qo_ql_description').querySelector('input')!;
    fireEvent.change(desc, { target: { value: '' } });
    fireEvent.click(screen.getByTestId('review-drawer-edit-submit'));

    // no command fires, and the error is shown
    await waitFor(() => expect(screen.getByTestId('review-drawer-edit-error')).toBeInTheDocument());
    expect(executeSimpleWorkbenchAction).not.toHaveBeenCalled();
  });

  it('renders nothing when the block declares no editForm', () => {
    const b = block();
    delete (b as any).editForm;
    render(<ReviewDrawerBlockRenderer block={b} runtime={makeRuntime(LINE)} />);
    // The slot stays in the DOM (a zero-height grid cell) so the drawer's row count is constant,
    // but nothing interactive renders.
    expect(screen.queryByTestId('review-drawer-edit-open')).toBeNull();
    expect(screen.getByTestId('review-drawer-edit-form-empty')).toBeInTheDocument();
  });
});
