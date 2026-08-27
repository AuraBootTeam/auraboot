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

function makeRuntime(
  selectedLine: Record<string, unknown>,
  dataBySource: Record<string, any[]> = {},
): SchemaRuntime {
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
      getData: (dataSourceId: string) => dataBySource[dataSourceId] || [],
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
      candidatesField: 'candidates',
      recommendedIdField: 'recommendedPreviewId',
      candidateTitleField: 'productModel',
      candidateSubtitleField: 'description',
      candidateMatchScoreField: 'matchScore',
      candidateProcurementScoreField: 'procurementScore',
      candidateReasonsField: 'matchReasons',
      candidateFields: [
        { field: 'manufacturer', label: { 'zh-CN': '制造商' } },
        { field: 'unitPrice', label: { 'zh-CN': '单价' } },
      ],
      layout: 'compact-grid',
      fields: [
        { field: 'productModel', label: { 'zh-CN': '返回型号' }, gridSpan: 2 },
        {
          field: 'unitPrice',
          label: { 'zh-CN': '价格' },
          format: 'price-comparison',
          factoredField: 'factoredUnitPrice',
          factorField: 'priceFactor',
          gridSpan: 4,
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

function comparisonTable() {
  return {
    keyField: 'previewId',
    minWidth: 1180,
    columns: [
      {
        key: 'selection',
        kind: 'selection',
        label: { 'zh-CN': '选择' },
        width: 100,
        badges: [
          {
            field: 'status',
            label: { 'zh-CN': '状态' },
            hideWhenEmpty: true,
          },
        ],
      },
      {
        key: 'material',
        label: { 'zh-CN': '型号 / 品牌 / 参数' },
        width: 230,
        fields: [
          { field: 'productModel', showLabel: false, variant: 'primary' },
          { field: 'manufacturer', showLabel: false },
          { field: 'description', showLabel: false },
        ],
      },
      {
        key: 'match',
        label: { 'zh-CN': '匹配度' },
        width: 170,
        fields: [
          { field: 'matchScore', label: { 'zh-CN': '匹配' }, format: 'score' },
          { field: 'matchReasons', label: { 'zh-CN': '依据' } },
        ],
      },
      {
        key: 'channel',
        label: { 'zh-CN': '渠道 / 链接' },
        width: 150,
        fields: [
          { field: 'channel', showLabel: false },
          { field: 'detailUrl', showLabel: false, format: 'link' },
        ],
      },
      {
        key: 'ladder',
        label: { 'zh-CN': '阶梯价' },
        width: 210,
        fields: [
          {
            field: 'priceLadderRows',
            showLabel: false,
            format: 'ladder',
            factorField: 'priceFactor',
          },
        ],
      },
      {
        key: 'quantity',
        label: { 'zh-CN': '采购数量' },
        width: 140,
        fields: [
          {
            key: 'purchaseQuantity',
            format: 'purchase-quantity',
            requestedQtyField: 'requestedQty',
            moqField: 'moq',
          },
        ],
      },
      {
        key: 'price',
        label: { 'zh-CN': '报价 / 小计' },
        width: 150,
        fields: [
          {
            key: 'quoteTotal',
            field: 'unitPrice',
            format: 'quote-total',
            factoredField: 'factoredUnitPrice',
            factorField: 'priceFactor',
            ladderField: 'priceLadderRows',
            requestedQtyField: 'requestedQty',
            moqField: 'moq',
          },
        ],
      },
      {
        key: 'action',
        kind: 'action',
        label: { 'zh-CN': '操作' },
        width: 90,
      },
    ],
    selectionSummary: {
      titleField: 'productModel',
      fields: [
        { field: 'factoredUnitPrice', label: { 'zh-CN': '采用单价' } },
        { field: 'manufacturer', label: { 'zh-CN': '品牌' } },
      ],
    },
  };
}

function comparisonTwoPhaseBlock(): BlockConfig {
  const value = twoPhaseBlock();
  (value as any).editForm.preview.candidateLayout = 'comparisonTable';
  (value as any).editForm.preview.candidateTable = comparisonTable();
  return value;
}

function evidenceComparisonBlock(): BlockConfig {
  const value = block();
  delete (value as any).editForm;
  (value as any).contextSummary = {
    valueField: 'material_label',
    quantityField: 'bom_qty',
  };
  (value as any).candidates = {
    dataSource: 'evidence',
    layout: 'comparisonTable',
    showDecisionStatus: false,
    showSelectedDetail: false,
    title: { 'zh-CN': '查价候选' },
    table: { ...comparisonTable(), keyField: 'pid' },
    selection: { bind: 'selectedEvidence' },
    actions: [{ code: 'confirm', label: { 'zh-CN': '确认此报价' } }],
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

  it('places the configured edit trigger beside the candidate heading', () => {
    const value = block();
    (value as any).candidates = {
      dataSource: 'evidence',
      title: { 'zh-CN': '查价候选(多源对比)' },
      item: { titleField: 'partNo' },
    };
    (value as any).editForm.openPlacement = 'candidates-header';

    render(
      <ReviewDrawerBlockRenderer
        block={value}
        runtime={makeRuntime(LINE, { evidence: [{ pid: 'EV-1', partNo: '1N4148W' }] })}
      />,
    );

    const header = screen.getByTestId('review-drawer-candidates-header');
    const host = screen.getByTestId('review-drawer-edit-open-host');
    const trigger = screen.getByTestId('review-drawer-edit-open');
    expect(header).toContainElement(trigger);
    expect(host).toContainElement(trigger);
    expect(trigger).toHaveClass('border-accent', 'text-accent');
    expect(screen.getByTestId('review-drawer-candidate-EV-1')).toHaveClass('select-text');

    fireEvent.click(trigger);
    expect(screen.getByTestId('review-drawer-content-grid')).toHaveClass('hidden');
    expect(screen.getByTestId('review-drawer-edit-form')).toBeInTheDocument();
  });

  it('shows dense one-row evidence candidates with an inline ladder and supplier URL', () => {
    const candidates = [
      {
        pid: 'EV-1',
        status: 'current',
        productModel: 'GRM188R71H104KA93D',
        manufacturer: 'Murata',
        description: '100nF 50V X7R 0603',
        matchScore: 96,
        matchReasons: ['容量匹配', '封装匹配'],
        channel: '云汉',
        detailUrl: 'https://www.ickey.cn/detail/grm188',
        priceLadderRows: [
          { qty: 100, price: 0.0051, current: true },
          { qty: 5000, price: 0.0039, current: false },
        ],
        requestedQty: 2,
        moq: 5000,
        unitPrice: 0.0051,
        factoredUnitPrice: 0.0051,
        priceFactor: 100,
      },
      {
        pid: 'EV-2',
        status: 'candidate',
        productModel: 'CL10B104KB8NNNC',
        manufacturer: 'Samsung',
        description: '100nF 50V X7R 0603',
        matchScore: 94,
        matchReasons: ['规格匹配', '价格更优'],
        channel: '云汉',
        detailUrl: 'https://www.ickey.cn/detail/cl10b',
        priceLadderRows: [],
        requestedQty: 120,
        moq: 100,
        unitPrice: 0.021,
        factoredUnitPrice: 0.0221,
        priceFactor: 105,
      },
    ];
    const { container } = render(
      <ReviewDrawerBlockRenderer
        block={evidenceComparisonBlock()}
        runtime={makeRuntime({ ...LINE, bom_qty: 120 }, { evidence: candidates })}
      />,
    );

    expect(screen.getByRole('columnheader', { name: '型号 / 品牌 / 参数' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: '阶梯价' })).toBeInTheDocument();
    expect(screen.getByText('GRM188R71H104KA93D')).toBeInTheDocument();
    expect(
      screen.getByTestId('review-drawer-candidate-EV-1-compact-ladder-current'),
    ).toHaveTextContent('0.0051');
    expect(
      screen.getByTestId('review-drawer-candidate-EV-1-table-field-purchaseQuantity'),
    ).toHaveTextContent('补齐 MOQ');
    expect(
      screen.getByTestId('review-drawer-candidate-EV-1-table-field-quoteTotal'),
    ).toHaveTextContent('小计 19.5');
    expect(screen.getByTestId('review-drawer-candidate-EV-1-link-detailUrl')).toHaveAttribute(
      'href',
      'https://www.ickey.cn/detail/grm188',
    );
    expect(
      screen.getByTestId('review-drawer-candidate-EV-2-table-field-quoteTotal'),
    ).toHaveTextContent('0.02205');
    expect(container.querySelector('[title*="price"]')).toBeNull();

    const firstRow = screen.getByTestId('review-drawer-candidate-EV-1');
    expect(firstRow).toHaveAttribute('data-selected', 'false');
    fireEvent.click(screen.getByTestId('review-drawer-candidate-EV-1-link-detailUrl'));
    expect(firstRow).toHaveAttribute('data-selected', 'false');

    const selection = vi
      .spyOn(window, 'getSelection')
      .mockReturnValue({ toString: () => 'GRM188R71H104KA93D' } as Selection);
    fireEvent.click(firstRow);
    expect(firstRow).toHaveAttribute('data-selected', 'false');
    selection.mockRestore();

    fireEvent.click(screen.getByTestId('review-drawer-candidate-EV-2'));
    expect(screen.getByTestId('review-drawer-candidate-EV-2')).toHaveAttribute(
      'data-selected',
      'true',
    );
    expect(screen.getByTestId('review-drawer-selection-summary')).toHaveTextContent(
      'CL10B104KB8NNNC',
    );
    expect(screen.getByTestId('review-drawer-candidate-action-confirm')).toBeEnabled();
    expect(screen.queryByTestId('review-drawer-selected-panel')).toBeNull();
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
    expect(screen.getByTestId('review-drawer-content-grid').classList.contains('hidden')).toBe(
      true,
    );
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
    expect(screen.queryByTestId('review-drawer-price-comparison')).toBeNull();
    expect(screen.getByTestId('review-drawer-ladder-current-priceLadderRows')).toBeInTheDocument();
    expect(
      screen.getByTestId('review-drawer-candidate-priceLadderRows-ladder-summary'),
    ).toHaveTextContent('100+');
    expect(
      screen.getByTestId('review-drawer-candidate-priceLadderRows-ladder-summary'),
    ).toHaveTextContent('0.08 × 200% → 0.16');
    expect(screen.getByTestId('review-drawer-field-link-detailUrl')).toHaveAttribute(
      'href',
      'https://www.ickey.cn/detail/new',
    );
    const scrollRegion = screen.getByTestId('review-drawer-edit-scroll');
    const actionBar = screen.getByTestId('review-drawer-edit-actions');
    const confirm = screen.getByTestId('review-drawer-edit-confirm');
    expect(scrollRegion).not.toContainElement(actionBar);
    expect(actionBar).toContainElement(confirm);
    expect(actionBar.className).toContain('shrink-0');
    expect(screen.getByTestId('review-drawer-preview-field-grid').className).toContain(
      'xl:grid-cols-4',
    );
    expect(screen.getByTestId('review-drawer-preview-field-productModel').className).toContain(
      'xl:col-span-2',
    );

    fireEvent.click(screen.getByTestId('review-drawer-edit-cancel'));
    expect(screen.queryByTestId('review-drawer-edit-preview')).toBeNull();
    expect(screen.getByTestId('review-drawer-content-grid').classList.contains('hidden')).toBe(
      false,
    );
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

  it('selects one of multiple independent product candidates before confirming', async () => {
    executeSimpleWorkbenchAction
      .mockResolvedValueOnce({
        previewId: 'RP-1',
        recommendedPreviewId: 'RP-1',
        status: 'ready',
        confirmable: true,
        candidates: [
          {
            previewId: 'RP-1',
            confirmable: true,
            productModel: 'GRM188R71H104KA93D',
            description: '100nF 50V X7R 0603',
            manufacturer: 'Murata',
            unitPrice: 0.08,
            matchScore: 96,
            procurementScore: 88,
            matchReasons: ['容量、电压、封装匹配'],
          },
          {
            previewId: 'RP-2',
            confirmable: true,
            productModel: 'CL10B104KB8NNNC',
            description: '100nF 50V X7R 0603',
            manufacturer: 'Samsung',
            unitPrice: 0.06,
            matchScore: 94,
            procurementScore: 92,
            matchReasons: ['规格匹配', '库存与价格更优'],
          },
        ],
      })
      .mockResolvedValueOnce({ confirmed: true, lineId: 'L1', evidenceId: 'E-NEW' });
    render(
      <ReviewDrawerBlockRenderer block={comparisonTwoPhaseBlock()} runtime={makeRuntime(LINE)} />,
    );

    fireEvent.click(screen.getByTestId('review-drawer-edit-open'));
    fireEvent.click(screen.getByTestId('review-drawer-edit-submit'));
    await waitFor(() =>
      expect(screen.getByTestId('review-drawer-edit-preview-candidates')).toBeInTheDocument(),
    );

    expect(screen.getByText('GRM188R71H104KA93D')).toBeInTheDocument();
    expect(screen.getByText('CL10B104KB8NNNC')).toBeInTheDocument();
    expect(
      screen.getByTestId('review-drawer-edit-preview-candidate-RP-1-table-field-matchReasons'),
    ).toHaveTextContent('容量、电压、封装匹配');
    expect(screen.getByTestId('review-drawer-edit-preview-candidate-RP-1')).toHaveAttribute(
      'data-selected',
      'true',
    );
    expect(screen.getByText('推荐')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('review-drawer-edit-preview-candidate-RP-2'));
    fireEvent.click(screen.getByTestId('review-drawer-edit-confirm'));
    await waitFor(() => expect(executeSimpleWorkbenchAction).toHaveBeenCalledTimes(2));

    expect(executeSimpleWorkbenchAction.mock.calls[1][1].args.payload).toEqual({
      previewId: 'RP-2',
    });
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

  it('keeps the raw-to-factored card for a flat supplier price with six-decimal precision', async () => {
    executeSimpleWorkbenchAction.mockResolvedValueOnce({
      previewId: 'RP-FLAT',
      status: 'ready',
      confirmable: true,
      productModel: 'FLAT-MPN',
      unitPrice: 0.013123,
      factoredUnitPrice: 0.014435,
      priceFactor: 110,
    });
    render(<ReviewDrawerBlockRenderer block={twoPhaseBlock()} runtime={makeRuntime(LINE)} />);

    fireEvent.click(screen.getByTestId('review-drawer-edit-open'));
    fireEvent.click(screen.getByTestId('review-drawer-edit-submit'));
    await waitFor(() =>
      expect(screen.getByTestId('review-drawer-price-comparison')).toBeInTheDocument(),
    );

    expect(screen.getByTestId('review-drawer-price-comparison')).toHaveTextContent('0.013123');
    expect(screen.getByTestId('review-drawer-price-comparison')).toHaveTextContent('0.014435');
    expect(screen.queryByTestId('review-drawer-candidate-priceLadderRows-ladder')).toBeNull();
  });

  it('does not reopen a blank drawer after the outer close button interrupts a preview', async () => {
    executeSimpleWorkbenchAction.mockResolvedValueOnce({
      previewId: 'RP-CLOSE',
      status: 'ready',
      confirmable: true,
      productModel: 'NEW-MPN',
    });
    const value = twoPhaseBlock();
    (value as any).closeClearsContext = false;
    render(<ReviewDrawerBlockRenderer block={value} runtime={makeRuntime(LINE)} />);

    fireEvent.click(screen.getByTestId('review-drawer-edit-open'));
    fireEvent.click(screen.getByTestId('review-drawer-edit-submit'));
    await waitFor(() =>
      expect(screen.getByTestId('review-drawer-edit-preview')).toBeInTheDocument(),
    );

    fireEvent.click(screen.getByRole('button', { name: '关闭复核浮层' }));
    expect(screen.queryByTestId('review-drawer')).toBeNull();
    fireEvent.click(screen.getByTestId('review-drawer-minimized'));

    expect(screen.getByTestId('review-drawer-content-grid')).not.toHaveClass('hidden');
    expect(screen.getByTestId('review-drawer-edit-open')).toBeInTheDocument();
  });
});
