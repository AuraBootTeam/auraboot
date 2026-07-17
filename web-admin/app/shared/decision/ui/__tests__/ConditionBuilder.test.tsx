import { useState } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ConditionBuilder, type FieldOption } from '../ConditionBuilder';
import { group, cmp, path, lit, type GroupNode } from '../../ast/conditionAst';

const FIELDS: FieldOption[] = [
  { scope: 'record', path: 'data.priority', label: '优先级', dataType: 'enum', options: ['HIGH', 'LOW'] },
  { scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' },
];

function Harness({ initial }: { initial: GroupNode }) {
  const [v, setV] = useState<GroupNode>(initial);
  return (
    <>
      <ConditionBuilder value={v} fields={FIELDS} onChange={setV} />
      <pre data-testid="dump">{JSON.stringify(v)}</pre>
    </>
  );
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

  it('renders literal option labels in selectors and the natural-language preview', () => {
    render(
      <ConditionBuilder
        value={group('AND', [
          cmp(path('record', 'data.targetKey', 'string'), 'EQ', lit('task_manager_approve', 'string')),
        ])}
        fields={[
          {
            scope: 'record',
            path: 'data.targetKey',
            label: 'SLA 节点',
            dataType: 'string',
            options: ['task_manager_approve', 'task_hr_approve'],
            valueLabels: {
              task_manager_approve: '主管审批节点',
              task_hr_approve: 'HR 审批节点',
            },
          },
        ]}
        onChange={() => undefined}
      />,
    );

    expect(screen.getByLabelText('value-0')).toHaveTextContent('主管审批节点');
    expect(screen.getByTestId('cb-preview')).toHaveTextContent('主管审批节点');
    expect(screen.getByTestId('cb-preview')).not.toHaveTextContent('task_manager_approve');
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
    expect(screen.getByLabelText('operator-0')).not.toHaveTextContent('大于');
    fireEvent.change(screen.getByLabelText('field-0'), { target: { value: 'record:data.amount' } });
    // decimal operators include GT now
    expect(screen.getByLabelText('operator-0')).toHaveTextContent('大于');
    expect(screen.getByLabelText('operator-0')).not.toHaveTextContent('GT');
  });

  it('uses fact catalog operator constraints before local data-type defaults', () => {
    render(
      <ConditionBuilder
        value={group('AND', [cmp(path('record', 'data.amount', 'decimal'), 'EQ', lit('100', 'decimal'))])}
        fields={[
          {
            scope: 'record',
            path: 'data.amount',
            label: '申请金额',
            dataType: 'decimal',
            operators: ['EQ'],
          },
        ]}
        onChange={() => undefined}
      />,
    );

    const operator = screen.getByLabelText('operator-0');
    expect(operator).toHaveTextContent('等于');
    expect(operator).not.toHaveTextContent('大于');
  });

  it('shows product labels for operator choices while preserving enum values', () => {
    render(<Harness initial={oneHighPriority()} />);
    const operator = screen.getByLabelText('operator-0') as HTMLSelectElement;
    expect(operator).toHaveTextContent('等于');
    expect(operator).toHaveTextContent('不等于');
    expect(operator).not.toHaveTextContent('EQ');
    expect(operator.value).toBe('EQ');
  });

  it('filters and groups field candidates by model or runtime scope', () => {
    const fields: FieldOption[] = [
      {
        scope: 'record',
        path: 'data.wd_req_days',
        label: '请假申请 / 请假天数',
        dataType: 'integer',
        modelCode: 'wd_leave_request',
        modelName: '请假申请',
      },
      {
        scope: 'record',
        path: 'data.access_count',
        label: 'Agent 记忆 / 访问次数',
        dataType: 'integer',
        modelCode: 'agent_memory',
        modelName: 'Agent 记忆',
      },
      {
        scope: 'actor',
        path: 'departmentId',
        label: '用户部门',
        dataType: 'department',
      },
    ];

    render(
      <ConditionBuilder
        value={group('AND', [cmp(path('record', 'data.wd_req_days', 'integer'), 'GT', lit('1', 'integer'))])}
        fields={fields}
        onChange={() => undefined}
      />,
    );

    const fieldPicker = screen.getByLabelText('field-0');
    expect(fieldPicker.querySelector('optgroup[label="请假申请"]')).not.toBeNull();
    expect(fieldPicker.querySelector('optgroup[label="操作者"]')).not.toBeNull();
    expect(fieldPicker).toHaveTextContent('Agent 记忆 / 访问次数');

    fireEvent.change(screen.getByLabelText('condition-field-search'), {
      target: { value: '请假' },
    });

    expect(screen.getByTestId('cb-field-result-count')).toHaveTextContent('1 / 3');
    expect(fieldPicker).toHaveTextContent('请假申请 / 请假天数');
    expect(fieldPicker).not.toHaveTextContent('Agent 记忆 / 访问次数');
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
    expect(screen.getByTestId('op-and')).toHaveTextContent('全部满足');
    expect(screen.getByTestId('op-or')).toHaveTextContent('任一满足');
    expect(screen.getByTestId('op-and')).not.toHaveTextContent('AND');
    expect(screen.getByTestId('op-or')).not.toHaveTextContent('OR');
    expect(screen.getByTestId('cb-preview')).toHaveTextContent('并且');
    fireEvent.click(screen.getByTestId('op-or'));
    expect(screen.getByTestId('cb-preview')).toHaveTextContent('或');
  });

  it('empty group shows placeholder', () => {
    render(<Harness initial={group('AND', [])} />);
    expect(screen.getByTestId('cb-empty')).toBeInTheDocument();
    expect(screen.getByTestId('cb-preview')).toHaveTextContent('—');
  });

  it('authors nested OR groups and NOT wrappers', () => {
    render(<Harness initial={oneHighPriority()} />);

    fireEvent.click(screen.getByTestId('cb-add-group'));
    expect(screen.getByTestId('cb-group-1')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('op-or-1'));
    fireEvent.change(screen.getByLabelText('field-1-0'), {
      target: { value: 'record:data.amount' },
    });
    fireEvent.change(screen.getByLabelText('operator-1-0'), {
      target: { value: 'GT' },
    });
    fireEvent.change(screen.getByLabelText('value-1-0'), {
      target: { value: '5000' },
    });
    fireEvent.click(screen.getByTestId('cb-add-1'));
    fireEvent.change(screen.getByLabelText('field-1-1'), {
      target: { value: 'record:data.priority' },
    });
    fireEvent.change(screen.getByLabelText('value-1-1'), {
      target: { value: 'HIGH' },
    });

    fireEvent.click(screen.getByTestId('cb-add-not'));
    expect(screen.getByTestId('cb-not-2')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('field-2-0'), {
      target: { value: 'record:data.priority' },
    });
    fireEvent.change(screen.getByLabelText('value-2-0'), {
      target: { value: 'LOW' },
    });

    expect(screen.getByTestId('cb-preview')).toHaveTextContent('或');
    expect(screen.getByTestId('cb-preview')).toHaveTextContent('非');

    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as GroupNode;
    expect(dump.children[1]).toMatchObject({
      type: 'group',
      op: 'OR',
      children: [
        {
          type: 'compare',
          operator: 'GT',
          left: { scope: 'record', path: 'data.amount' },
          right: { value: '5000' },
        },
        {
          type: 'compare',
          operator: 'EQ',
          left: { scope: 'record', path: 'data.priority' },
          right: { value: 'HIGH' },
        },
      ],
    });
    expect(dump.children[2]).toMatchObject({
      type: 'not',
      child: {
        type: 'compare',
        operator: 'EQ',
        left: { scope: 'record', path: 'data.priority' },
        right: { value: 'LOW' },
      },
    });
  });
});
