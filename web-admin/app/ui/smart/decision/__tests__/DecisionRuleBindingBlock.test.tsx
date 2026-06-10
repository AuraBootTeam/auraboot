import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DecisionRuleBindingBlock } from '../DecisionRuleBindingBlock';

describe('DecisionRuleBindingBlock', () => {
  it('hosts condition and decision binding editors in one DSL custom block', () => {
    render(
      <DecisionRuleBindingBlock
        block={{
          props: {
            mode: 'combined',
            initialDecisionCode: 'approval_routing',
            fields: [
              { scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' },
              { scope: 'actor', path: 'departmentId', label: '用户部门', dataType: 'department' },
            ],
            decisions: [
              { code: 'approval_routing', name: '审批路由' },
              { code: 'sla_deadline', name: 'SLA 截止时间' },
            ],
          },
        }}
      />,
    );

    expect(screen.getByTestId('decision-rule-binding-block')).toBeInTheDocument();
    expect(screen.getByTestId('condition-builder')).toBeInTheDocument();
    expect(screen.getByTestId('decision-binding-editor')).toBeInTheDocument();
    expect(screen.getByLabelText('decision-code')).toHaveValue('approval_routing');

    fireEvent.change(screen.getByLabelText('version-policy'), {
      target: { value: 'ROLLOUT' },
    });
    fireEvent.click(screen.getByRole('button', { name: '添加映射' }));
    fireEvent.change(screen.getByLabelText('mapping-input-0'), {
      target: { value: 'amount' },
    });

    expect(screen.getByTestId('decision-binding-mapping-0')).toBeInTheDocument();
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent(
      '"versionPolicy": "ROLLOUT"',
    );
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent(
      '"decisionCode": "approval_routing"',
    );
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('"input": "amount"');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent(
      '"path": "data.amount"',
    );
  });

  it('can render decision-only mode for compact host surfaces', () => {
    render(
      <DecisionRuleBindingBlock
        block={{
          props: {
            mode: 'decision',
            initialDecisionCode: 'sla_deadline',
            initialVersionPolicy: 'LATEST_PUBLISHED',
          },
        }}
      />,
    );

    expect(screen.queryByTestId('condition-builder')).not.toBeInTheDocument();
    expect(screen.getByTestId('decision-binding-editor')).toBeInTheDocument();
    expect(screen.getByLabelText('decision-code')).toHaveValue('sla_deadline');
  });

  it('hydrates from an existing RuleConsumerBinding value', () => {
    render(
      <DecisionRuleBindingBlock
        value={{
          consumerType: 'AUTOMATION',
          consumerCode: 'auto-1',
          consumerNodeId: 'trigger',
          bindingKind: 'DECISION_REF',
          decisionBinding: {
            decisionCode: 'sla_deadline',
            versionPolicy: 'ROLLOUT',
            inputMappings: [
              {
                input: 'amount',
                source: { kind: 'FIELD', scope: 'record', path: 'data.amount' },
              },
            ],
            outputMappings: [],
            fallbackPolicy: { mode: 'FAIL_OPEN' },
            traceMode: 'SAMPLED',
            enabled: true,
          },
          enabled: true,
        }}
      />,
    );

    expect(screen.getByLabelText('decision-code')).toHaveValue('sla_deadline');
    expect(screen.getByLabelText('version-policy')).toHaveValue('ROLLOUT');
    expect(screen.getByLabelText('fallback-mode')).toHaveValue('FAIL_OPEN');
    expect(screen.getByTestId('decision-binding-mapping-0')).toBeInTheDocument();
    expect(screen.getByLabelText('mapping-input-0')).toHaveValue('amount');
  });

  it('writes RuleConsumerBinding JSON back through the DSL form runtime valueField', () => {
    const updateField = vi.fn();
    const onChange = vi.fn();

    render(
      <DecisionRuleBindingBlock
        runtime={{ updateField }}
        onChange={onChange}
        block={{
          props: {
            mode: 'decision',
            valueField: 'triggerConfig.ruleBinding',
            consumerType: 'AUTOMATION',
            consumerCode: 'auto-1',
            consumerNodeId: 'trigger',
            initialDecisionCode: 'approval_routing',
            fields: [
              { scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' },
              { scope: 'record', path: 'data.recordId', label: '记录 ID', dataType: 'string' },
            ],
          },
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '添加映射' }));
    fireEvent.change(screen.getByLabelText('mapping-input-0'), {
      target: { value: 'amount' },
    });
    fireEvent.change(screen.getByLabelText('version-policy'), {
      target: { value: 'ROLLOUT' },
    });

    const [fieldCode, value] = updateField.mock.calls.at(-1) ?? [];
    expect(fieldCode).toBe('triggerConfig.ruleBinding');
    expect(value).toMatchObject({
      consumerType: 'AUTOMATION',
      consumerCode: 'auto-1',
      consumerNodeId: 'trigger',
      bindingKind: 'DECISION_REF',
      decisionBinding: {
        decisionCode: 'approval_routing',
        versionPolicy: 'ROLLOUT',
        inputMappings: [
          {
            input: 'amount',
            source: { kind: 'FIELD', scope: 'record', path: 'data.amount' },
          },
        ],
      },
    });
    expect(onChange).toHaveBeenCalledWith(value);
  });
});
