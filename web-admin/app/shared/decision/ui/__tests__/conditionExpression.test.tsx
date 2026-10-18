import { useState } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ConditionBuilder, type FieldOption } from '../ConditionBuilder';
import { group, cmp, path, lit, not, type GroupNode } from '../../ast/conditionAst';
import { compatibleFields, wrapSelection } from '../conditionTreeEditing';
import { conditionConfigurationValid } from '../ConditionServerTrial';

const fields: FieldOption[] = [
  {
    scope: 'record',
    path: 'data.amount',
    label: '交付金额',
    dataType: 'decimal',
    modelCode: 'acceptance',
    modelName: '验收单',
  },
  {
    scope: 'record',
    path: 'data.budget',
    label: '项目预算',
    dataType: 'decimal',
    modelCode: 'project',
    modelName: '关联项目',
  },
  {
    scope: 'record',
    path: 'data.risk',
    label: '风险',
    dataType: 'enum',
    modelCode: 'acceptance',
    modelName: '验收单',
    options: ['high', 'normal'],
    valueLabels: { high: '高风险', normal: '普通风险' },
  },
  {
    scope: 'record',
    path: 'data.masked',
    label: '敏感字段',
    dataType: 'decimal',
    modelCode: 'project',
    modelName: '关联项目',
    masked: true,
  },
];
const a = () => cmp(path('record', 'data.amount', 'decimal'), 'GTE', lit(100000, 'decimal'));
const b = () => cmp(path('record', 'data.risk', 'enum'), 'EQ', lit('high', 'enum'));
function Harness() {
  const [value, setValue] = useState(group('AND', [a(), b()]));
  return (
    <>
      <ConditionBuilder value={value} fields={fields} onChange={setValue} />
      <pre data-testid="expression-dump">{JSON.stringify(value)}</pre>
    </>
  );
}
const dump = () => JSON.parse(screen.getByTestId('expression-dump').textContent!) as GroupNode;

describe('metadata-backed expression authoring', () => {
  it('wraps consecutive siblings without changing truth and rejects cross-group or sparse selection', () => {
    const initial = group('OR', [a(), b(), a()]);
    const wrapped = wrapSelection(initial, [[0], [1]])!;
    expect(wrapped.children[0]).toEqual(group('OR', [a(), b()]));
    expect(initial.children).toHaveLength(3);
    expect(wrapSelection(initial, [[0], [2]])).toBeNull();
    expect(wrapSelection(group('AND', [a(), group('OR', [b()])]), [[0], [1, 0]])).toBeNull();
  });
  it('chooses models and compatible RHS fields, serializing path operands', () => {
    render(<Harness />);
    fireEvent.change(screen.getByLabelText('value-kind-0'), { target: { value: 'field' } });
    fireEvent.change(screen.getByLabelText('right-model-0'), {
      target: { value: 'record:project' },
    });
    expect(screen.getByLabelText('right-field-0')).toHaveDisplayValue('项目预算');
    expect(screen.getByLabelText('right-field-0')).not.toHaveTextContent('敏感字段');
    const row = dump().children[0];
    expect(row.type === 'compare' && row.right).toEqual(path('record', 'data.budget', 'decimal'));
    fireEvent.change(screen.getByLabelText('model-0'), { target: { value: 'record:project' } });
    expect(screen.getByLabelText('field-0')).toHaveDisplayValue('项目预算');
    expect(screen.getByTestId('expression-dump')).not.toHaveTextContent('data.masked');
  });
  it('wraps, negates, undoes, redoes and confirms ungrouping', () => {
    render(<Harness />);
    fireEvent.click(screen.getByLabelText('select-0'));
    fireEvent.click(screen.getByLabelText('select-1'));
    fireEvent.click(screen.getByTestId('condition-wrap'));
    expect(dump().children).toHaveLength(1);
    fireEvent.click(screen.getByLabelText('group-not-0'));
    expect(dump().children[0].type).toBe('not');
    fireEvent.click(screen.getByRole('button', { name: '撤销', exact: true }));
    expect(dump().children[0].type).toBe('group');
    fireEvent.click(screen.getByRole('button', { name: '重做', exact: true }));
    expect(dump().children[0].type).toBe('not');
    fireEvent.click(screen.getByLabelText('delete-group-0'));
    expect(screen.getByRole('dialog')).toHaveTextContent('去括号可能改变');
    fireEvent.click(screen.getByRole('button', { name: '去括号，保留条件', exact: true }));
    expect(dump().children).toEqual([a(), b()]);
  });
  it('enforces dictionary/type compatibility and rejects invalid ranges and empty groups', () => {
    expect(compatibleFields(fields[0], fields[1])).toBe(true);
    expect(compatibleFields(fields[0], fields[2])).toBe(false);
    expect(compatibleFields(fields[0], fields[3])).toBe(false);
    expect(conditionConfigurationValid(group('AND', []), fields)).toBe(false);
    expect(
      conditionConfigurationValid(
        group('AND', [
          cmp(path('record', 'data.amount', 'decimal'), 'BETWEEN', lit([20, 10], 'decimal')),
        ]),
        fields,
      ),
    ).toBe(false);
    expect(conditionConfigurationValid(group('AND', [not(a())]), fields)).toBe(true);
  });
  it('keeps read-only conditions non-editable and does not offer a trial action', () => {
    render(
      <ConditionBuilder
        value={group('AND', [a()])}
        fields={fields}
        onChange={() => {
          throw Error('read-only mutation');
        }}
        readOnly
      />,
    );
    expect(screen.getByLabelText('value-0')).toBeDisabled();
    expect(screen.queryByTestId('condition-trial-toggle')).not.toBeInTheDocument();
  });
});
