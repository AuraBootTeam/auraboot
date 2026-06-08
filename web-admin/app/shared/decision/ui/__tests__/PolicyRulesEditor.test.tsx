import { useState } from 'react';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { PolicyRulesEditor, type PolicyRulesValue } from '../PolicyRulesEditor';
import { type FieldOption } from '../ConditionBuilder';
import { group, cmp, path, lit } from '../../ast/conditionAst';

const FIELDS: FieldOption[] = [
  { scope: 'record', path: 'data.priority', label: '优先级', dataType: 'enum', options: ['HIGH', 'LOW'] },
  { scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' },
];

const initial = (): PolicyRulesValue => ({
  matchMode: 'COLLECT_ALL',
  rules: [{
    ruleCode: 'R-1', ruleName: '高优通知', priority: 100, enabled: true,
    condition: group('AND', [cmp(path('record', 'data.priority', 'enum'), 'EQ', lit('HIGH', 'enum'))]),
  }],
});

function Harness() {
  const [v, setV] = useState<PolicyRulesValue>(initial());
  return (
    <>
      <PolicyRulesEditor value={v} fields={FIELDS} onChange={setV} />
      <div data-testid="dump">{JSON.stringify(v)}</div>
    </>
  );
}

describe('PolicyRulesEditor', () => {
  it('renders matchMode + a rule with its embedded ConditionBuilder', () => {
    render(<Harness />);
    expect(screen.getByLabelText('match-mode')).toHaveValue('COLLECT_ALL');
    const rule = screen.getByTestId('pre-rule-0');
    expect(within(rule).getByTestId('condition-builder')).toBeInTheDocument();
    expect(within(rule).getByLabelText('rule-name-0')).toHaveValue('高优通知');
  });

  it('adds and deletes rules', () => {
    render(<Harness />);
    fireEvent.click(screen.getByTestId('pre-add-rule'));
    expect(screen.getByTestId('pre-rule-1')).toBeInTheDocument();
    fireEvent.click(screen.getByLabelText('rule-delete-1'));
    expect(screen.queryByTestId('pre-rule-1')).not.toBeInTheDocument();
  });

  it('edits rule name + toggles enabled, reflected in emitted value', () => {
    render(<Harness />);
    fireEvent.change(screen.getByLabelText('rule-name-0'), { target: { value: '改名规则' } });
    fireEvent.click(screen.getByLabelText('rule-enabled-0'));
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as PolicyRulesValue;
    expect(dump.rules[0].ruleName).toBe('改名规则');
    expect(dump.rules[0].enabled).toBe(false);
  });

  it('changing matchMode is reflected', () => {
    render(<Harness />);
    fireEvent.change(screen.getByLabelText('match-mode'), { target: { value: 'FIRST_MATCH' } });
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as PolicyRulesValue;
    expect(dump.matchMode).toBe('FIRST_MATCH');
  });

  it('editing a rule condition via the embedded builder updates that rule', () => {
    render(<Harness />);
    const rule = screen.getByTestId('pre-rule-0');
    fireEvent.click(within(rule).getByTestId('cb-add')); // add a condition row to rule 0
    const dump = JSON.parse(screen.getByTestId('dump').textContent || '{}') as PolicyRulesValue;
    expect(dump.rules[0].condition.children.length).toBe(2);
  });
});
