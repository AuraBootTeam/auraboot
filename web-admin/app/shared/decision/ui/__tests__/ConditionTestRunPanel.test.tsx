import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ConditionTestRunPanel, type TestSample } from '../ConditionTestRunPanel';
import { group, cmp, path, lit, type ConditionNode } from '../../ast/conditionAst';

// priority == HIGH AND amount > 10000
const condition: ConditionNode = group('AND', [
  cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum')),
  cmp(path('record', 'data.amount', 'decimal'), 'GT', lit(10000, 'decimal')),
]);

const samples: TestSample[] = [
  { label: '高优大额', context: { record: { data: { priority: 'HIGH', amount: 20000 } } } },
  { label: '普通小额', context: { record: { data: { priority: 'NORMAL', amount: 100 } } } },
  { label: '缺金额', context: { record: { data: { priority: 'HIGH' } } } },
];

describe('ConditionTestRunPanel', () => {
  it('shows TRUE (命中) for a matching sample', () => {
    render(<ConditionTestRunPanel condition={condition} samples={samples} />);
    const result = screen.getByTestId('trp-result');
    expect(result).toHaveAttribute('data-truth', 'TRUE');
    expect(result).toHaveTextContent('命中');
  });

  it('switching to a non-matching sample shows FALSE (未命中)', () => {
    render(<ConditionTestRunPanel condition={condition} samples={samples} />);
    fireEvent.click(screen.getByTestId('sample-1'));
    const result = screen.getByTestId('trp-result');
    expect(result).toHaveAttribute('data-truth', 'FALSE');
    expect(result).toHaveTextContent('未命中');
  });

  it('missing-field sample shows UNKNOWN (未知), not a false match', () => {
    render(<ConditionTestRunPanel condition={condition} samples={samples} />);
    fireEvent.click(screen.getByTestId('sample-2'));
    const result = screen.getByTestId('trp-result');
    expect(result).toHaveAttribute('data-truth', 'UNKNOWN');
    expect(result).toHaveTextContent('未知');
  });

  it('renders natural-language with a label resolver + the "preview only" note', () => {
    render(<ConditionTestRunPanel condition={condition} samples={samples}
      labelOf={(o) => (o.path === 'data.priority' ? '优先级' : '金额')} />);
    expect(screen.getByTestId('trp-nl')).toHaveTextContent('优先级');
    expect(screen.getByTestId('trp-note')).toHaveTextContent('以后端 test-run 为准');
  });
});
