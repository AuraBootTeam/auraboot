import { useState } from 'react';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ConditionBuilder, type FieldOption } from '../ConditionBuilder';
import { group, cmp, path, lit, type GroupNode } from '../../ast/conditionAst';

const FIELDS: FieldOption[] = [
  { scope: 'record', path: 'data.priority', label: '优先级', dataType: 'enum', options: ['HIGH', 'LOW'] },
  { scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' },
];

function Harness({ initial }: { initial: GroupNode }) {
  const [v, setV] = useState<GroupNode>(initial);
  return <ConditionBuilder value={v} fields={FIELDS} onChange={setV} />;
}

const oneHighPriority = (): GroupNode =>
  group('AND', [cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum'))]);

describe('ConditionBuilder', () => {
  it('renders rows + natural-language preview from value', () => {
    render(<Harness initial={oneHighPriority()} />);
    expect(screen.getByTestId('cb-row-0')).toBeInTheDocument();
    const preview = screen.getByTestId('cb-preview');
    expect(preview).toHaveTextContent('优先级');
    expect(preview).toHaveTextContent('等于');
    expect(preview).toHaveTextContent('HIGH');
  });

  it('adds and deletes condition rows', () => {
    render(<Harness initial={oneHighPriority()} />);
    fireEvent.click(screen.getByTestId('cb-add'));
    expect(screen.getByTestId('cb-row-1')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('delete-1'));
    expect(screen.queryByTestId('cb-row-1')).not.toBeInTheDocument();
    expect(screen.getByTestId('cb-row-0')).toBeInTheDocument();
  });

  it('changing field updates operator options to that data type', () => {
    render(<Harness initial={oneHighPriority()} />);
    // enum operators do NOT include GT
    expect(within(screen.getByLabelText('operator-0') as HTMLElement).queryByText('GT')).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('field-0'), { target: { value: 'record:data.amount' } });
    // decimal operators include GT now
    expect(within(screen.getByLabelText('operator-0') as HTMLElement).getByText('GT')).toBeInTheDocument();
  });

  it('unary operator hides the value input', () => {
    render(<Harness initial={group('AND', [cmp(path('record', 'data.amount', 'decimal'), 'GT', lit('100', 'decimal'))])} />);
    expect(screen.getByLabelText('value-0')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('operator-0'), { target: { value: 'IS_NULL' } });
    expect(screen.queryByLabelText('value-0')).not.toBeInTheDocument();
  });

  it('editing the value updates the preview', () => {
    render(<Harness initial={group('AND', [cmp(path('record', 'data.amount', 'decimal'), 'GT', lit('100', 'decimal'))])} />);
    fireEvent.change(screen.getByLabelText('value-0'), { target: { value: '5000' } });
    expect(screen.getByTestId('cb-preview')).toHaveTextContent('5000');
  });

  it('toggling AND/OR changes the preview connector', () => {
    render(<Harness initial={group('AND', [
      cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum')),
      cmp(path('record', 'data.amount', 'decimal'), 'GT', lit('100', 'decimal')),
    ])} />);
    expect(screen.getByTestId('cb-preview')).toHaveTextContent('并且');
    fireEvent.click(screen.getByTestId('op-or'));
    expect(screen.getByTestId('cb-preview')).toHaveTextContent('或');
  });

  it('empty group shows placeholder', () => {
    render(<Harness initial={group('AND', [])} />);
    expect(screen.getByTestId('cb-empty')).toBeInTheDocument();
    expect(screen.getByTestId('cb-preview')).toHaveTextContent('—');
  });
});
