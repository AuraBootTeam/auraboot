import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { expect, it, vi, beforeEach } from 'vitest';
import { ConditionServerTrial } from '../ConditionServerTrial';
import { group, cmp, path, lit } from '../../ast/conditionAst';
import type { FieldOption } from '../ConditionBuilder';
const service = vi.hoisted(() => ({ post: vi.fn(), get: vi.fn(), delete: vi.fn() }));
vi.mock('~/shared/services/ApiService', () => ({ getApiService: () => service }));
const fields: FieldOption[] = [
  {
    scope: 'record',
    path: 'data.amount',
    label: '金额',
    dataType: 'decimal',
    modelCode: 'acceptance',
    modelName: '验收单',
  },
];
const condition = group('AND', [
  cmp(path('record', 'data.amount', 'decimal'), 'GTE', lit(100000, 'decimal')),
]);
const response = {
  code: '0',
  data: {
    status: 'MATCHED',
    matched: true,
    conditionTrace: {
      result: 'TRUE',
      steps: [
        { expr: 'record.data.amount GTE 100000', left: 128000, right: 100000, result: 'TRUE' },
      ],
      nodeResults: { root: 'TRUE', 'root.0': 'TRUE' },
      unknownReasons: [],
    },
  },
};
beforeEach(() => vi.clearAllMocks());
it('posts typed inputs and current DSL to the engine and displays returned evidence', async () => {
  service.post.mockResolvedValue(response);
  const trace = vi.fn();
  render(<ConditionServerTrial condition={condition} fields={fields} onTrace={trace} />);
  fireEvent.change(screen.getByLabelText('trial-record:data.amount'), {
    target: { value: '128000' },
  });
  fireEvent.click(screen.getByTestId('condition-server-run'));
  await waitFor(() =>
    expect(screen.getByTestId('condition-server-result')).toHaveTextContent('条件成立'),
  );
  expect(service.post).toHaveBeenCalledWith('/decision/test-run', {
    kind: 'SIMPLE_CONDITION',
    runtimeAdapter: 'AST_EVALUATOR',
    contentJson: condition,
    context: { record: { data: { amount: 128000 } } },
    explain: true,
  });
  expect(screen.getByText('128000 → 100000')).toBeInTheDocument();
  expect(trace).toHaveBeenLastCalledWith(response.data.conditionTrace);
  fireEvent.change(screen.getByLabelText('trial-record:data.amount'), {
    target: { value: '68000' },
  });
  expect(screen.getByTestId('condition-server-result')).toHaveTextContent('旧结果已失效');
});
it('ignores a late response after inputs change', async () => {
  let resolve!: (value: unknown) => void;
  service.post.mockReturnValue(new Promise((done) => (resolve = done)));
  const trace = vi.fn();
  render(<ConditionServerTrial condition={condition} fields={fields} onTrace={trace} />);
  fireEvent.click(screen.getByTestId('condition-server-run'));
  fireEvent.change(screen.getByLabelText('trial-record:data.amount'), {
    target: { value: '68000' },
  });
  await act(async () => resolve(response));
  expect(screen.getByTestId('condition-server-result')).not.toHaveTextContent('条件成立');
  expect(trace).not.toHaveBeenCalledWith(response.data.conditionTrace);
});
it('blocks invalid configuration and missing trace without a client substitute', async () => {
  service.post.mockResolvedValue({ code: '0', data: { status: 'MATCHED', matched: true } });
  const { rerender } = render(
    <ConditionServerTrial condition={group('AND', [])} fields={fields} onTrace={() => undefined} />,
  );
  fireEvent.click(screen.getByTestId('condition-server-run'));
  expect(service.post).not.toHaveBeenCalled();
  expect(screen.getByRole('alert')).toHaveTextContent('请补齐');
  rerender(
    <ConditionServerTrial condition={condition} fields={fields} onTrace={() => undefined} />,
  );
  fireEvent.click(screen.getByTestId('condition-server-run'));
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('后端未返回条件解释'));
});
