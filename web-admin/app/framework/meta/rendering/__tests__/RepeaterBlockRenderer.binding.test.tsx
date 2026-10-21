import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ScopedStateManager } from '../../runtime/state/scoped-state';
import { RepeaterBlockRenderer } from '../blocks/RepeaterBlockRenderer';

// Substitute the widget only; exercise both shared renderers and the real form store.
vi.mock('../components/ComponentLoader', () => ({
  ComponentLoader: ({ props }: any) => (
    <input aria-label={props.name} value={props.value ?? ''}
      readOnly={props.readOnly} onChange={(event) => props.onChange(event.target.value)} />
  ),
}));
vi.mock('~/contexts/AuthContext', () => ({ usePermission: () => true }));
vi.mock('~/framework/meta/hooks/useActionHandler', () => ({ useActionHandler: () => vi.fn() }));

function setup(rows: Record<string, unknown>[]) {
  const state = new ScopedStateManager({} as any);
  state.createScope('allocation', { form: { choices: rows } });
  const linkagePayloads: unknown[] = [];
  const runtime = {
    getContext: () => ({ locale: 'en', t: (key: string) => key, ...state.getScope('allocation') }),
    getStateManager: () => state,
    getScopeId: () => 'allocation',
    triggerFieldLinkage: () => linkagePayloads.push(structuredClone(state.getFieldValue('allocation', 'choices'))),
  } as any;
  render(<RepeaterBlockRenderer runtime={runtime} block={{
    id: 'choices', type: 'repeater', field: 'choices', fields: [
      { field: 'quantity', component: 'SmartInput' },
    ],
  } as any} />);
  return { state, linkagePayloads };
}

describe('Repeater form array binding', () => {
  it('accepts an explicit parent-array replacement without resetting edits on unrelated state changes', () => {
    const { state } = setup([]);
    act(() => state.updateField('allocation', 'choices', [{ supplier: 'first', quantity: '60' }]));
    expect(screen.getByRole('textbox')).toHaveValue('60');
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '40' } });
    act(() => state.updateState('allocation', 'unrelatedFilter', 'active'));
    expect(screen.getByRole('textbox')).toHaveValue('40');
    act(() => state.updateField('allocation', 'choices', [{ supplier: 'second', quantity: '70' }]));
    expect(screen.getByRole('textbox')).toHaveValue('70');
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '50' } });
    expect(state.getFieldValue('allocation', 'choices')).toEqual([{ supplier: 'second', quantity: '50' }]);
  });
  it('shows initial row values and commits exact decimal strings before linkage', () => {
    const { state, linkagePayloads } = setup([
      { supplier: 'first', quantity: '60' }, { supplier: 'second', quantity: '70' },
    ]);
    const inputs = screen.getAllByRole('textbox');
    expect(inputs[0]).toHaveValue('60');
    expect(inputs[1]).toHaveValue('70');
    fireEvent.change(inputs[1], { target: { value: '40.000001' } });
    const expected = [{ supplier: 'first', quantity: '60' }, { supplier: 'second', quantity: '40.000001' }];
    expect(JSON.parse(JSON.stringify(state.getScope('allocation')?.form?.choices))).toEqual(expected);
    expect(linkagePayloads).toEqual([expected]);
    expect(inputs[1]).toHaveValue('40.000001');
  });

  it('uses the remaining row value after removal and preserves blank edits', () => {
    const { state } = setup([{ supplier: 'first', quantity: '60' }, { supplier: 'second', quantity: '40' }]);
    fireEvent.change(screen.getAllByRole('textbox')[0], { target: { value: '59' } });
    fireEvent.click(screen.getByRole('button', { name: 'Remove row 1' }));
    expect(screen.getByRole('textbox')).toHaveValue('40');
    fireEvent.change(screen.getByRole('textbox'), { target: { value: '' } });
    expect(state.getFieldValue('allocation', 'choices')).toEqual([{ supplier: 'second', quantity: '' }]);
  });
});
