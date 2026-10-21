import React from 'react';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ExpressionEvaluator } from '../../../runtime/expression/evaluator';
import { businessFunctions } from '../../../runtime/expression/business-functions';
import { ScopedStateManager } from '../../../runtime/state/scoped-state';
import { CardGridBlockRenderer } from '../CardGridBlockRenderer';

const { handleAction } = vi.hoisted(() => ({ handleAction: vi.fn() }));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({ useActionHandler: () => ({ handleAction }) }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({ token: 'local' }), usePermission: () => true }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({
  showSuccessToast: vi.fn(), showErrorToast: vi.fn(), showWarningToast: vi.fn(), showInfoToast: vi.fn(),
}) }));
vi.mock('~/framework/meta/rendering/pages/hooks/useDictCache', () => ({
  useDictCache: () => ({ loaded: true, getDictLabel: () => undefined }),
}));
// Keep actual field/card renderers and form state; substitute only the leaf widget.
vi.mock('../../components/ComponentLoader', () => ({ ComponentLoader: ({ props }: any) => (
  <label>{props.label}<input aria-label={props.label} value={props.value ?? ''}
    readOnly={props.readOnly} onChange={(event) => props.onChange(event.target.value)} /></label>
) }));

function setup(bind = true, grouped = false, computed = false, grid = false) {
  const state = new ScopedStateManager({} as any);
  state.createScope('review');
  let rows = [
    { pid: 'quote-a', supplierName: 'Supplier A', availableQty: '60', proposedQty: '60', score: '100', sourcePid: 'source-a', sourceLabel: 'Same material' },
    { pid: 'quote-b', supplierName: 'Supplier B', availableQty: '70', proposedQty: '40', score: '90', sourcePid: 'source-b', sourceLabel: 'Same material' },
  ];
  if (grouped) rows.push({ pid: 'quote-c', supplierName: 'Supplier C', availableQty: '50',
    proposedQty: '0', score: '80', sourcePid: 'source-a', sourceLabel: 'Same material' });
  const listeners = new Set<() => void>();
  const data = {
    getData: () => rows, getState: () => ({ data: rows }),
    subscribe: (_key: string, listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); },
  };
  const runtime = {
    getContext: () => ({ fn: businessFunctions, locale: 'en', t: (key: string) => key, ...state.getScope('review') }),
    getEvaluator: () => new ExpressionEvaluator(), getStateManager: () => state, getScopeId: () => 'review', getSchema: () => ({ id: 'review' }),
    getDataSourceManager: () => data, triggerFieldLinkage: vi.fn(),
  } as any;
  render(<CardGridBlockRenderer runtime={runtime} block={{ id: 'offers', blockType: 'card-grid',
    dataSource: 'offers', titleField: 'supplierName', ...(bind ? { field: 'choices' } : {}),
    ...(grid ? { fieldsLayout: { type: 'grid', cols: 12, rowGap: 12, colGap: 12 } } : {}),
    ...(grouped ? { groupByField: 'sourcePid', groupTitleField: 'sourceLabel' } : {}),
    fields: [
      { field: 'availableQty', label: 'Available capacity', component: 'SmartInput', readOnly: true },
      { field: 'proposedQty', label: 'Proposed quantity', component: 'SmartInput' },
      { field: 'score', label: 'Score', component: 'SmartInput', readOnly: true, ...(grid ? { layout: { colSpan: 12 } } : {}) },
      ...(computed ? [{ field: 'groupTotal', label: 'Group total', component: 'SmartInput', valueWhen: '${fn.DECIMAL_SUM_BY(form.choices, "proposedQty", "sourcePid", row.sourcePid)}' }] : []),
    ],
    cardActions: [{ code: 'preview', label: 'Preview', action: { type: 'command', command: 'test:preview' } }],
  } as any} />);
  return { state, refresh: () => {
    rows = rows.map(row => ({ ...row, proposedQty: '999' }));
    listeners.forEach(listener => listener());
  } };
}

describe('Card-grid draft form fields', () => {
  beforeEach(() => handleAction.mockClear());

  it('uses opt-in field grid without changing read-only capacity or independent draft edits', () => {
    const { state } = setup(true, false, false, true);
    const cards = screen.getAllByTestId('card-grid-card') as HTMLElement[];
    const first = within(cards[0]);
    const fieldColumn = (name: string) => cards[0].querySelector<HTMLElement>(`[data-testid="grid-item-${name}"]`)?.style.gridColumn;
    expect(fieldColumn('availableQty')).toBe('1 / span 6');
    expect(fieldColumn('proposedQty')).toBe('7 / span 6');
    expect(fieldColumn('score')).toBe('1 / span 12');
    expect(first.getByRole('textbox', { name: 'Available capacity' })).toHaveProperty('readOnly', true);
    fireEvent.change(first.getByRole('textbox', { name: 'Proposed quantity' }), { target: { value: '40.000001' } });
    expect(state.getFieldValue('review', 'choices')[0].proposedQty).toBe('40.000001');
    expect(within(cards[1]).getByRole('textbox', { name: 'Proposed quantity' })).toHaveValue('40');
  });

  it('keeps existing cards stacked when no inner grid is declared', () => {
    setup();
    expect(screen.queryByTestId('grid-layout')).not.toBeInTheDocument();
  });


  it('updates computed source totals without persisting derived values', () => {
    const { state } = setup(true, true, true);
    const card = within(screen.getByRole('group', { name: 'Supplier C' }));
    expect(card.getByRole('textbox', { name: 'Group total' })).toHaveValue('60');
    expect(card.getByRole('textbox', { name: 'Group total' })).toHaveAttribute('readonly');
    fireEvent.change(card.getByRole('textbox', { name: 'Proposed quantity' }), { target: { value: '0.000001' } });
    expect(card.getByRole('textbox', { name: 'Group total' })).toHaveValue('60.000001');
    expect(within(screen.getByRole('group', { name: 'Supplier B' })).getByRole('textbox', { name: 'Group total' })).toHaveValue('40');
    expect(state.getFieldValue('review', 'choices')[2]).not.toHaveProperty('groupTotal');
  });

  it('keeps offered capacity read-only and submits edited draft rows only on an explicit action', () => {
    const { state } = setup();
    expect(state.getFieldValue('review', 'choices')).toHaveLength(2);
    expect(state.getFieldValue('review', 'choices')[1].proposedQty).toBe('40');
    const card = within(screen.getByRole('group', { name: 'Supplier B' }));
    expect(card.getByRole('textbox', { name: 'Available capacity' })).toHaveAttribute('readonly');
    expect(card.getByRole('textbox', { name: 'Score' })).toHaveAttribute('readonly');
    fireEvent.change(card.getByRole('textbox', { name: 'Proposed quantity' }), { target: { value: '40.000001' } });
    expect(state.getFieldValue('review', 'choices')[1]).toMatchObject({
      pid: 'quote-b', availableQty: '70', proposedQty: '40.000001', score: '90',
    });
    expect(handleAction).not.toHaveBeenCalled();
    fireEvent.click(within(screen.getAllByTestId('card-grid-card')[1]).getByRole('button', { name: 'Preview' }));
    expect(handleAction.mock.calls[0][1]).toMatchObject({ proposedQty: '40.000001', availableQty: '70' });
  });

  it('preserves manual quantities on data refresh and accepts an explicit form replacement', () => {
    const { state, refresh } = setup();
    fireEvent.change(within(screen.getByRole('group', { name: 'Supplier A' })).getByRole('textbox', { name: 'Proposed quantity' }),
      { target: { value: '59' } });
    act(refresh);
    expect(within(screen.getByRole('group', { name: 'Supplier A' })).getByRole('textbox', { name: 'Proposed quantity' })).toHaveValue('59');
    act(() => state.updateField('review', 'choices', [{ pid: 'quote-c', supplierName: 'Supplier C',
      availableQty: '50', proposedQty: '0', score: '80' }]));
    expect(screen.queryByRole('group', { name: 'Supplier A' })).toBeNull();
    expect(within(screen.getByRole('group', { name: 'Supplier C' })).getByRole('textbox', { name: 'Proposed quantity' })).toHaveValue('0');
  });

  it('renders all declared fields as read-only without a parent form binding', () => {
    const { state } = setup(false);
    expect(screen.getAllByRole('textbox', { name: 'Proposed quantity' }).every((input: HTMLElement) => input.hasAttribute('readonly'))).toBe(true);
    expect(state.getFieldValue('review', 'choices')).toBeUndefined();
  });

  it('groups by source identity and edits the original row after display reordering', () => {
    const { state, refresh } = setup(true, true);
    const groups: HTMLElement[] = screen.getAllByRole('region', { name: 'Same material' });
    expect(groups).toHaveLength(2);
    expect(groups[0].querySelectorAll('[data-testid="card-grid-card"]')).toHaveLength(2);
    expect(groups[1].querySelectorAll('[data-testid="card-grid-card"]')).toHaveLength(1);
    const card = within(within(groups[0]).getByRole('group', { name: 'Supplier C' }));
    fireEvent.change(card.getByRole('textbox', { name: 'Proposed quantity' }),
      { target: { value: '0.000001' } });
    expect(state.getFieldValue('review', 'choices').map((row: any) => row.proposedQty))
      .toEqual(['60', '40', '0.000001']);
    act(refresh);
    expect(card.getByRole('textbox', { name: 'Proposed quantity' })).toHaveValue('0.000001');
    fireEvent.click(groups[0].querySelectorAll('[data-testid="card-grid-action-preview"]')[1]);
    expect(handleAction.mock.calls[0][1]).toMatchObject({ pid: 'quote-c', sourcePid: 'source-a', proposedQty: '0.000001' });
  });

  it('keeps missing identities separate and reacts to explicit draft replacement', () => {
    const { state } = setup(true, true);
    act(() => state.updateField('review', 'choices', [
      { pid: 'unknown-a', supplierName: 'Unknown A', sourceLabel: 'Unknown source', proposedQty: '1' },
      { pid: 'unknown-b', supplierName: 'Unknown B', sourceLabel: 'Unknown source', proposedQty: '2' },
    ]));
    const groups: HTMLElement[] = screen.getAllByRole('region', { name: 'Unknown source' });
    expect(groups).toHaveLength(2);
    expect(groups.every(group => group.querySelectorAll('[data-testid="card-grid-card"]').length === 1)).toBe(true);
    expect(screen.queryByRole('group', { name: 'Supplier C' })).toBeNull();
  });
});
