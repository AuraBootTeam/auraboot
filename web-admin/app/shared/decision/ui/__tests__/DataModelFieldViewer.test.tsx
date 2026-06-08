import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { DataModelFieldViewer, type ModelField } from '../DataModelFieldViewer';

const fields: ModelField[] = [
  { entityCode: 'complaint', path: 'priority', label: '优先级', dataType: 'enum', refs: 18, masked: false, permission: '业务可见' },
  { entityCode: 'complaint', path: 'amount', label: '影响金额', dataType: 'decimal', refs: 12, masked: true, permission: '经理可见' },
  { entityCode: 'incident', path: 'severity', label: '故障等级', dataType: 'enum', refs: 15, masked: false, permission: 'ITSM' },
];

describe('DataModelFieldViewer', () => {
  it('renders all fields with refs/mask and a count', () => {
    render(<DataModelFieldViewer fields={fields} />);
    expect(screen.getByTestId('dmv-count')).toHaveTextContent('3');
    const row = screen.getByTestId('dmv-row-complaint.amount');
    expect(row).toHaveTextContent('影响金额');
    expect(row).toHaveTextContent('是'); // masked
    expect(screen.getByTestId('dmv-refs-amount')).toHaveTextContent('12');
  });

  it('filters by entity', () => {
    render(<DataModelFieldViewer fields={fields} />);
    fireEvent.change(screen.getByLabelText('entity-filter'), { target: { value: 'incident' } });
    expect(screen.getByTestId('dmv-count')).toHaveTextContent('1');
    expect(screen.getByTestId('dmv-row-incident.severity')).toBeInTheDocument();
    expect(screen.queryByTestId('dmv-row-complaint.amount')).not.toBeInTheDocument();
  });

  it('searches by path / label', () => {
    render(<DataModelFieldViewer fields={fields} />);
    fireEvent.change(screen.getByLabelText('field-search'), { target: { value: 'priority' } });
    expect(screen.getByTestId('dmv-count')).toHaveTextContent('1');
    expect(screen.getByTestId('dmv-row-complaint.priority')).toBeInTheDocument();
  });

  it('shows empty state when nothing matches', () => {
    render(<DataModelFieldViewer fields={fields} />);
    fireEvent.change(screen.getByLabelText('field-search'), { target: { value: 'zzz' } });
    expect(screen.getByTestId('dmv-empty')).toBeInTheDocument();
  });
});
