import { useState } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { DecisionTableEditor } from '../DecisionTableEditor';
import { type DecisionTable } from '../../table/decisionTable';

const base = (): DecisionTable => ({
  hitPolicy: 'FIRST',
  inputs: [
    { id: 'amount', label: '金额', scope: 'record', path: 'data.amount', dataType: 'decimal' },
    { id: 'priority', label: '优先级', scope: 'record', path: 'data.priority', dataType: 'enum' },
  ],
  outputs: [{ id: 'route', label: '路由', dataType: 'string' }],
  rules: [],
});

function Harness() {
  const [v, setV] = useState<DecisionTable>(base());
  return (
    <>
      <DecisionTableEditor value={v} onChange={setV} />
      <div data-testid="dump">{JSON.stringify(v)}</div>
    </>
  );
}

describe('DecisionTableEditor', () => {
  it('renders input/output headers and empty placeholder', () => {
    render(<Harness />);
    expect(screen.getByTestId('dt-in-amount')).toHaveTextContent('金额');
    expect(screen.getByTestId('dt-out-route')).toHaveTextContent('路由');
    expect(screen.getByTestId('dt-empty')).toBeInTheDocument();
  });

  it('adds and deletes rule rows', () => {
    render(<Harness />);
    fireEvent.click(screen.getByTestId('dt-add-rule'));
    expect(screen.getByTestId('dt-row-0')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('delete-row-0'));
    expect(screen.queryByTestId('dt-row-0')).not.toBeInTheDocument();
  });

  it('edits a cell operator + value and an output, reflected in emitted table', () => {
    render(<Harness />);
    fireEvent.click(screen.getByTestId('dt-add-rule'));
    fireEvent.change(screen.getByLabelText('op-0-amount'), { target: { value: 'GT' } });
    fireEvent.change(screen.getByLabelText('val-0-amount'), { target: { value: '10000' } });
    fireEvent.change(screen.getByLabelText('out-0-route'), { target: { value: 'director' } });
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as DecisionTable;
    expect(dump.rules[0].when.amount).toMatchObject({ operator: 'GT', value: '10000' });
    expect(dump.rules[0].then.route).toBe('director');
  });

  it('changes the hit policy', () => {
    render(<Harness />);
    fireEvent.change(screen.getByLabelText('hit-policy'), { target: { value: 'UNIQUE' } });
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as DecisionTable;
    expect(dump.hitPolicy).toBe('UNIQUE');
  });
});
