import React from 'react';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
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

function setup(bind = true) {
  const state = new ScopedStateManager({} as any);
  state.createScope('review');
  let rows = [
    { pid: 'quote-a', supplierName: 'Supplier A', availableQty: '60', proposedQty: '60', score: '100' },
    { pid: 'quote-b', supplierName: 'Supplier B', availableQty: '70', proposedQty: '40', score: '90' },
  ];
  const listeners = new Set<() => void>();
  const data = {
    getData: () => rows, getState: () => ({ data: rows }),
    subscribe: (_key: string, listener: () => void) => { listeners.add(listener); return () => listeners.delete(listener); },
  };
  const runtime = {
    getContext: () => ({ locale: 'en', t: (key: string) => key, ...state.getScope('review') }),
    getStateManager: () => state, getScopeId: () => 'review', getSchema: () => ({ id: 'review' }),
    getDataSourceManager: () => data, triggerFieldLinkage: vi.fn(),
  } as any;
  render(<CardGridBlockRenderer runtime={runtime} block={{ id: 'offers', blockType: 'card-grid',
    dataSource: 'offers', titleField: 'supplierName', ...(bind ? { field: 'choices' } : {}),
    fields: [
      { field: 'availableQty', label: 'Available capacity', component: 'SmartInput', readOnly: true },
      { field: 'proposedQty', label: 'Proposed quantity', component: 'SmartInput' },
      { field: 'score', label: 'Score', component: 'SmartInput', readOnly: true },
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
});
