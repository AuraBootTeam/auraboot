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

  it('renders fact catalog value labels in preview and sample context while preserving raw context values', () => {
    const leaveCondition: ConditionNode = group('AND', [
      cmp(path('record', 'data.wd_req_type', 'dict'), 'EQ', lit('annual', 'dict')),
    ]);
    render(
      <ConditionTestRunPanel
        condition={leaveCondition}
        samples={[
          {
            label: '年假样例',
            context: { record: { data: { wd_req_type: 'annual' } } },
          },
        ]}
        fields={[
          {
            scope: 'record',
            path: 'data.wd_req_type',
            label: '请假类型',
            dataType: 'dict',
            options: ['annual', 'sick'],
            valueLabels: {
              annual: '年假',
              sick: '病假',
            },
          },
        ]}
      />,
    );

    expect(screen.getByTestId('trp-nl')).toHaveTextContent('请假类型');
    expect(screen.getByTestId('trp-nl')).toHaveTextContent('年假');
    expect(screen.getByTestId('trp-nl')).not.toHaveTextContent('annual');
    expect(screen.getByTestId('trp-context')).toHaveTextContent('请假类型');
    expect(screen.getByTestId('trp-context')).toHaveTextContent('年假');
    expect(screen.getByTestId('trp-context')).not.toHaveTextContent('annual');
    expect(screen.getByTestId('trp-result')).toHaveAttribute('data-truth', 'TRUE');
  });

  it('can hide empty group preview when a host relies on backend test-run results', () => {
    render(
      <ConditionTestRunPanel
        condition={group('AND', [])}
        samples={samples}
        emptyPreviewLabel="当前版本以已发布策略条件为准"
      />,
    );

    expect(screen.getByTestId('trp-nl')).toHaveTextContent('当前版本以已发布策略条件为准');
    expect(screen.queryByTestId('trp-result')).not.toBeInTheDocument();
  });
});
