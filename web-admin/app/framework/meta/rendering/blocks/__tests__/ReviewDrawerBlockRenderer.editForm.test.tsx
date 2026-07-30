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
        {
          field: 'qo_ql_qty_per_set',
          label: { 'zh-CN': '单套用量' },
          type: 'number',
          valueField: 'qty_per_set',
        },
        { field: 'qo_ql_mpn', label: { 'zh-CN': 'MPN' }, valueField: 'mpn' },
      ],
    },
  } as unknown as BlockConfig;
}

const LINE = { pid: 'L1', material_label: '1N4148W', qty_per_set: 2, mpn: '1N4148W' };

function twoPhaseBlock(): BlockConfig {
  const value = block();
  (value as any).editForm = {
    previewCommand: 'qo_quote_line_common:preview_reprice',
    confirmCommand: 'qo_quote_line_common:confirm_reprice_preview',
    reload: ['lines', 'evidence'],
    modeField: 'searchMode',
    searchField: 'searchText',
    modeValueFields: { exact: 'mpn', spec: 'description' },
    fields: [
      {
        field: 'searchMode',
        label: { 'zh-CN': '查价方式' },
        type: 'radio',
        defaultValue: 'exact',
        required: true,
        options: [
          { value: 'exact', label: { 'zh-CN': '精确型号' } },
          { value: 'spec', label: { 'zh-CN': '规格描述' } },
        ],
      },
      {
        field: 'searchText',
        label: { 'zh-CN': '查价内容' },
        required: true,
      },
    ],
    preview: {
      title: { 'zh-CN': '云汉查价预览' },
      notice: { 'zh-CN': '确认并采用后才会更新报价' },
      confirmableField: 'confirmable',
      previewIdField: 'previewId',
      fields: [
        { field: 'productModel', label: { 'zh-CN': '返回型号' } },
        {
          field: 'unitPrice',
          label: { 'zh-CN': '价格' },
          format: 'price-comparison',
          factoredField: 'factoredUnitPrice',
          factorField: 'priceFactor',
        },
        {
          field: 'priceLadderRows',
          label: { 'zh-CN': '阶梯价' },
          format: 'ladder',
          factorField: 'priceFactor',
        },
        {
          field: 'detailUrl',
          label: { 'zh-CN': '供应商页面' },
          format: 'link',
        },
      ],
    },
  };
  return value;
}

describe('ReviewDrawerBlockRenderer — inline edit form', () => {
  beforeEach(() => executeSimpleWorkbenchAction.mockReset().mockResolvedValue(undefined));

  it('submits changed fields as a command targeting the selected line', async () => {
    render(<ReviewDrawerBlockRenderer block={block()} runtime={makeRuntime(LINE)} />);

    fireEvent.click(screen.getByTestId('review-drawer-edit-open'));
    // seeded from the record
    const perSet = screen
      .getByTestId('review-drawer-edit-field-qo_ql_qty_per_set')
      .querySelector('input')!;
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
    const perSet = screen
      .getByTestId('review-drawer-edit-field-qo_ql_qty_per_set')
      .querySelector('input')!;
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
      {
        field: 'qo_ql_description',
        label: { 'zh-CN': '规格描述' },
        valueField: 'material_label',
        required: true,
      },
      { field: 'qo_ql_mpn', label: { 'zh-CN': 'MPN' }, valueField: 'mpn' },
    ];
    render(<ReviewDrawerBlockRenderer block={b} runtime={makeRuntime(LINE)} />);

    fireEvent.click(screen.getByTestId('review-drawer-edit-open'));
    const desc = screen
      .getByTestId('review-drawer-edit-field-qo_ql_description')
      .querySelector('input')!;
    fireEvent.change(desc, { target: { value: '' } });
    fireEvent.click(screen.getByTestId('review-drawer-edit-submit'));

    // no command fires, and the error is shown
    await waitFor(() => expect(screen.getByTestId('review-drawer-edit-error')).toBeInTheDocument());
    expect(executeSimpleWorkbenchAction).not.toHaveBeenCalled();

    fireEvent.change(desc, { target: { value: 'Switching diode 1N4148W' } });
    expect(screen.queryByTestId('review-drawer-edit-error')).toBeNull();
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

  it('previews in the same drawer and cancel performs no confirm command', async () => {
    executeSimpleWorkbenchAction.mockResolvedValueOnce({
      previewId: 'RP1',
      status: 'ready',
      confirmable: true,
      productModel: 'NEW-MPN',
      unitPrice: 0.08,
      factoredUnitPrice: 0.16,
      priceFactor: 200,
      priceLadderRows: [
        { qty: 50, price: 0.1, current: false },
        { qty: 100, price: 0.08, current: true },
      ],
      detailUrl: 'https://www.ickey.cn/detail/new',
    });
    render(
      <ReviewDrawerBlockRenderer
        block={twoPhaseBlock()}
        runtime={makeRuntime({ ...LINE, description: '100nF 50V 0603' })}
      />,
    );

    fireEvent.click(screen.getByTestId('review-drawer-edit-open'));
    const exact = screen.getByRole('radio', { name: '精确型号' }) as HTMLInputElement;
    expect(exact.checked).toBe(true);
    const search = screen
      .getByTestId('review-drawer-edit-field-searchText')
      .querySelector('input') as HTMLInputElement;
    expect(search.value).toBe('1N4148W');

    fireEvent.click(screen.getByTestId('review-drawer-edit-submit'));
    await waitFor(() =>
      expect(screen.getByTestId('review-drawer-edit-preview')).toBeInTheDocument(),
    );
    expect(screen.getByTestId('review-drawer-edit-preview-notice')).toHaveTextContent(
      '确认并采用后才会更新报价',
    );
    expect(screen.getByText('NEW-MPN')).toBeInTheDocument();
    expect(screen.getByTestId('review-drawer-price-comparison')).toBeInTheDocument();
    expect(screen.getByTestId('review-drawer-ladder-current-priceLadderRows')).toBeInTheDocument();
    expect(screen.getByTestId('review-drawer-field-link-detailUrl')).toHaveAttribute(
      'href',
      'https://www.ickey.cn/detail/new',
    );

    fireEvent.click(screen.getByTestId('review-drawer-edit-cancel'));
    expect(screen.queryByTestId('review-drawer-edit-preview')).toBeNull();
    expect(executeSimpleWorkbenchAction).toHaveBeenCalledTimes(1);
    expect(executeSimpleWorkbenchAction.mock.calls[0][1].args.command).toBe(
      'qo_quote_line_common:preview_reprice',
    );
  });

  it('switches the one search field by mode and confirms using only the server preview id', async () => {
    executeSimpleWorkbenchAction
      .mockResolvedValueOnce({
        previewId: 'RP2',
        status: 'ready',
        confirmable: true,
        productModel: 'RETURNED-MPN',
      })
      .mockResolvedValueOnce({
        confirmed: true,
        lineId: 'L1',
        evidenceId: 'E-NEW',
      });
    render(
      <ReviewDrawerBlockRenderer
        block={twoPhaseBlock()}
        runtime={makeRuntime({ ...LINE, description: '100nF 50V 0603' })}
      />,
    );

    fireEvent.click(screen.getByTestId('review-drawer-edit-open'));
    fireEvent.click(screen.getByRole('radio', { name: '规格描述' }));
    const search = screen
      .getByTestId('review-drawer-edit-field-searchText')
      .querySelector('input') as HTMLInputElement;
    expect(search.value).toBe('100nF 50V 0603');
    fireEvent.change(search, { target: { value: '100nF/50V-0603-10%' } });
    fireEvent.click(screen.getByTestId('review-drawer-edit-submit'));

    await waitFor(() =>
      expect(screen.getByTestId('review-drawer-edit-confirm')).toBeInTheDocument(),
    );
    const previewCall = executeSimpleWorkbenchAction.mock.calls[0][1];
    expect(previewCall.args.payload).toEqual({
      searchMode: 'spec',
      searchText: '100nF/50V-0603-10%',
    });
    expect(previewCall.args.reload).toEqual([]);

    fireEvent.click(screen.getByTestId('review-drawer-edit-confirm'));
    await waitFor(() => expect(executeSimpleWorkbenchAction).toHaveBeenCalledTimes(2));
    const confirmCall = executeSimpleWorkbenchAction.mock.calls[1][1];
    expect(confirmCall.args.command).toBe('qo_quote_line_common:confirm_reprice_preview');
    expect(confirmCall.args.payload).toEqual({ previewId: 'RP2' });
    expect(confirmCall.args.payload).not.toHaveProperty('unitPrice');
    expect(confirmCall.args.reload).toEqual(['lines', 'evidence']);
  });

  it('shows misses without a confirm action and lets the user return to edit', async () => {
    executeSimpleWorkbenchAction.mockResolvedValueOnce({
      previewId: 'RP3',
      status: 'not_found',
      confirmable: false,
      message: '云汉已查询，未找到匹配价格。',
    });
    render(<ReviewDrawerBlockRenderer block={twoPhaseBlock()} runtime={makeRuntime(LINE)} />);

    fireEvent.click(screen.getByTestId('review-drawer-edit-open'));
    fireEvent.click(screen.getByTestId('review-drawer-edit-submit'));
    await waitFor(() =>
      expect(screen.getByTestId('review-drawer-edit-preview-message')).toHaveTextContent(
        '云汉已查询，未找到匹配价格。',
      ),
    );
    expect(screen.queryByTestId('review-drawer-edit-confirm')).toBeNull();
    fireEvent.click(screen.getByTestId('review-drawer-edit-back'));
    expect(screen.getByTestId('review-drawer-edit-field-searchText')).toBeInTheDocument();
  });
});
