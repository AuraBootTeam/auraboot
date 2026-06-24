import { fireEvent, render, screen, waitFor } from '@testing-library/react';
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

  it('edits decision output mappings for downstream action and process targets', () => {
    const onChange = vi.fn();

    render(
      <DecisionRuleBindingBlock
        onChange={onChange}
        block={{
          props: {
            mode: 'decision',
            initialDecisionCode: 'approval_routing',
          },
        }}
      />,
    );

    expect(screen.getByTestId('decision-output-mapping-empty')).toHaveTextContent('暂无输出映射');
    fireEvent.click(screen.getByRole('button', { name: '添加输出' }));
    fireEvent.change(screen.getByLabelText('output-mapping-output-0'), {
      target: { value: 'assigneeUserId' },
    });
    fireEvent.change(screen.getByLabelText('output-mapping-kind-0'), {
      target: { value: 'PROCESS_VARIABLE' },
    });
    fireEvent.change(screen.getByLabelText('output-mapping-path-0'), {
      target: { value: 'assigneeUserId' },
    });

    expect(screen.getByTestId('decision-output-mapping-0')).toBeInTheDocument();
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent(
      '"output": "assigneeUserId"',
    );
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent(
      '"kind": "PROCESS_VARIABLE"',
    );
    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({
        decisionBinding: expect.objectContaining({
          outputMappings: [
            {
              output: 'assigneeUserId',
              target: { kind: 'PROCESS_VARIABLE', path: 'assigneeUserId' },
            },
          ],
        }),
      }),
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

  it('renders a read-only decision summary without editor debug surfaces', () => {
    render(
      <DecisionRuleBindingBlock
        block={{
          props: {
            mode: 'decision',
            readOnly: true,
            variant: 'summary',
            showImpactPreview: true,
            showTestRunner: true,
            decisions: [
              { code: 'complaint_sla_deadline', name: '投诉 SLA 截止时间' },
              { code: 'sla_deadline', name: 'SLA 截止时间' },
            ],
          },
        }}
        value={{
          bindingKind: 'DECISION_REF',
          decisionBinding: {
            decisionCode: 'complaint_sla_deadline',
            versionPolicy: 'LATEST_PUBLISHED',
            inputMappings: [
              {
                input: 'ticketPriority',
                source: { kind: 'FIELD', scope: 'record', path: 'data.priority' },
              },
            ],
            outputMappings: [
              {
                output: 'deadlineMinutes',
                target: { kind: 'SLA_FIELD', path: 'deadlineMinutes' },
              },
            ],
            fallbackPolicy: { mode: 'FAIL_CLOSED' },
            traceMode: 'SAMPLED',
            enabled: true,
          },
          enabled: true,
        }}
      />,
    );

    expect(screen.getByTestId('decision-binding-summary')).toBeInTheDocument();
    expect(screen.getByText('投诉 SLA 截止时间')).toBeInTheDocument();
    expect(screen.getByText('complaint_sla_deadline')).toBeInTheDocument();
    expect(screen.getByText('ticketPriority')).toBeInTheDocument();
    expect(screen.getByText('record.data.priority')).toBeInTheDocument();
    expect(screen.getByText('deadlineMinutes')).toBeInTheDocument();
    expect(screen.getByText('SLA_FIELD.deadlineMinutes')).toBeInTheDocument();

    expect(screen.queryByTestId('decision-binding-editor')).not.toBeInTheDocument();
    expect(screen.queryByTestId('decision-binding-preview')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('decision-code')).not.toBeInTheDocument();
    expect(screen.queryByTestId('decision-test-runner')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'run-decision-test' })).not.toBeInTheDocument();
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
            outputMappings: [
              {
                output: 'deadlineMinutes',
                target: { kind: 'SLA_FIELD', path: 'deadlineMinutes' },
              },
            ],
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
    expect(screen.getByTestId('decision-output-mapping-0')).toBeInTheDocument();
    expect(screen.getByLabelText('output-mapping-output-0')).toHaveValue('deadlineMinutes');
    expect(screen.getByLabelText('output-mapping-kind-0')).toHaveValue('SLA_FIELD');
    expect(screen.getByLabelText('output-mapping-path-0')).toHaveValue('deadlineMinutes');
  });

  it('syncs when a DSL form valueField arrives after the custom block mounts', async () => {
    let currentValue: unknown;
    const runtime = {
      getFieldValue: () => currentValue,
      updateField: vi.fn(),
    };
    const block = {
      props: {
        mode: 'decision' as const,
        valueField: 'rule_binding',
        fields: [
          { scope: 'record' as const, path: 'data.priority', label: '优先级', dataType: 'enum' as const },
        ],
      },
    };

    const { rerender } = render(
      <DecisionRuleBindingBlock runtime={runtime} block={block} />,
    );

    expect(screen.queryByLabelText('mapping-input-0')).not.toBeInTheDocument();

    currentValue = {
      bindingKind: 'DECISION_REF',
      decisionBinding: {
        decisionCode: 'sla_deadline',
        versionPolicy: 'LATEST_PUBLISHED',
        inputMappings: [
          {
            input: 'catalogPriority',
            source: { kind: 'FIELD', scope: 'record', path: 'data.priority' },
          },
        ],
      },
      enabled: true,
    };
    rerender(<DecisionRuleBindingBlock runtime={runtime} block={block} />);

    await waitFor(() =>
      expect(screen.getByLabelText('mapping-input-0')).toHaveValue('catalogPriority'),
    );
    expect(screen.getByLabelText('decision-code')).toHaveValue('sla_deadline');
    expect(screen.getByLabelText('mapping-field-0')).toHaveValue('record:data.priority');
  });

  it('hydrates from the dynamic API JSONB envelope shape', () => {
    render(
      <DecisionRuleBindingBlock
        value={{
          type: 'jsonb',
          value: JSON.stringify({
            bindingKind: 'DECISION_REF',
            decisionBinding: {
              decisionCode: 'sla_deadline',
              versionPolicy: 'LATEST_PUBLISHED',
              inputMappings: [
                {
                  input: 'catalogPriority',
                  source: { kind: 'FIELD', scope: 'record', path: 'data.priority' },
                },
              ],
            },
            enabled: true,
          }),
        }}
        block={{
          props: {
            mode: 'decision',
            fields: [
              { scope: 'record', path: 'data.priority', label: '优先级', dataType: 'enum' },
            ],
          },
        }}
      />,
    );

    expect(screen.getByLabelText('decision-code')).toHaveValue('sla_deadline');
    expect(screen.getByLabelText('mapping-input-0')).toHaveValue('catalogPriority');
    expect(screen.getByLabelText('mapping-field-0')).toHaveValue('record:data.priority');
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
              { scope: 'record', path: 'data.recordPid', label: '记录 ID', dataType: 'string' },
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

  it('merges backend decision model fields into configured mapping fields when enabled', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(),
      getModelFields: vi.fn(async () => [
        {
          entityCode: 'record',
          path: 'data.slaCatalogPriority',
          label: 'SLA Catalog Priority',
          dataType: 'enum' as const,
        },
      ]),
    };

    render(
      <DecisionRuleBindingBlock
        api={api}
        block={{
          props: {
            mode: 'decision',
            fieldCatalogMode: 'merge',
            initialDecisionCode: 'complaint_sla_deadline',
            fields: [
              { scope: 'record', path: 'data.targetKey', label: '目标键', dataType: 'string' },
            ],
          },
        }}
      />,
    );

    await waitFor(() => expect(api.getModelFields).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '添加映射' }));

    const fieldPicker = screen.getByLabelText('mapping-field-0');
    expect(fieldPicker).toHaveTextContent('SLA Catalog Priority');
    expect(fieldPicker).toHaveTextContent('目标键');
  });

  it('writes nested AND/OR/NOT condition specs through the DSL form runtime valueField', () => {
    const updateField = vi.fn();

    render(
      <DecisionRuleBindingBlock
        runtime={{ updateField }}
        block={{
          props: {
            mode: 'condition',
            valueField: 'rule_binding',
            consumerType: 'EVENT_POLICY',
            consumerCode: 'policy-1',
            consumerNodeId: 'rule-1',
            fields: [
              {
                scope: 'record',
                path: 'data.priority',
                label: '优先级',
                dataType: 'enum',
                options: ['HIGH', 'LOW'],
              },
              { scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' },
            ],
          },
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('cb-add'));
    fireEvent.change(screen.getByLabelText('field-0'), {
      target: { value: 'record:data.priority' },
    });
    fireEvent.change(screen.getByLabelText('value-0'), {
      target: { value: 'HIGH' },
    });

    fireEvent.click(screen.getByTestId('cb-add-group'));
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

    fireEvent.click(screen.getByTestId('cb-add-not'));
    fireEvent.change(screen.getByLabelText('field-2-0'), {
      target: { value: 'record:data.priority' },
    });
    fireEvent.change(screen.getByLabelText('value-2-0'), {
      target: { value: 'LOW' },
    });

    const [, value] = updateField.mock.calls.at(-1) ?? [];
    expect(value).toMatchObject({
      consumerType: 'EVENT_POLICY',
      consumerCode: 'policy-1',
      consumerNodeId: 'rule-1',
      bindingKind: 'CONDITION',
      conditionSpec: {
        root: {
          type: 'group',
          op: 'AND',
          children: [
            {
              type: 'compare',
              operator: 'EQ',
              left: { scope: 'record', path: 'data.priority' },
              right: { value: 'HIGH' },
            },
            {
              type: 'group',
              op: 'OR',
              children: [
                {
                  type: 'compare',
                  operator: 'GT',
                  left: { scope: 'record', path: 'data.amount' },
                  right: { value: '5000' },
                },
              ],
            },
            {
              type: 'not',
              child: {
                type: 'compare',
                operator: 'EQ',
                left: { scope: 'record', path: 'data.priority' },
                right: { value: 'LOW' },
              },
            },
          ],
        },
      },
      enabled: true,
    });
  });

  it('writes the initial RuleConsumerBinding value to a DSL form valueField on mount', async () => {
    const updateField = vi.fn();

    render(
      <DecisionRuleBindingBlock
        runtime={{ getFieldValue: () => undefined, updateField }}
        block={{
          props: {
            mode: 'decision',
            valueField: 'rule_binding',
            consumerType: 'SLA',
            initialDecisionCode: 'complaint_sla_deadline',
            initialVersionPolicy: 'LATEST_PUBLISHED',
          },
        }}
      />,
    );

    await waitFor(() => expect(updateField).toHaveBeenCalled());
    expect(updateField).toHaveBeenCalledWith(
      'rule_binding',
      expect.objectContaining({
        consumerType: 'SLA',
        bindingKind: 'DECISION_REF',
        decisionBinding: expect.objectContaining({
          decisionCode: 'complaint_sla_deadline',
          versionPolicy: 'LATEST_PUBLISHED',
        }),
      }),
    );
  });

  it('does not overwrite an existing DSL form valueField value on mount', async () => {
    const updateField = vi.fn();

    render(
      <DecisionRuleBindingBlock
        runtime={{
          getFieldValue: () => ({
            bindingKind: 'DECISION_REF',
            decisionBinding: { decisionCode: 'existing_decision' },
            enabled: true,
          }),
          updateField,
        }}
        block={{
          props: {
            mode: 'decision',
            valueField: 'rule_binding',
            initialDecisionCode: 'complaint_sla_deadline',
          },
        }}
      />,
    );

    await waitFor(() =>
      expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent(
        '"decisionCode": "existing_decision"',
      ),
    );
    expect(updateField).not.toHaveBeenCalled();
  });

  it('loads decision impact preview on demand', async () => {
    const api = {
      getDecisionImpact: vi.fn(async () => ({
        decisionCode: 'approval_routing',
        incoming: [{ sourceType: 'AUTOMATION', sourcePid: 'auto-1', binding: 'RULE_BINDING' }],
        outgoing: [{ targetType: 'FIELD', targetPath: 'record.data.amount' }],
        risk: {
          blocking: true,
          summary: 'Used by 1 automation',
        },
      })),
      evaluate: vi.fn(),
    };

    render(
      <DecisionRuleBindingBlock
        api={api}
        block={{
          props: {
            mode: 'decision',
            initialDecisionCode: 'approval_routing',
          },
        }}
      />,
    );

    expect(screen.getByTestId('decision-impact-empty')).toHaveTextContent('尚未加载影响');
    fireEvent.click(screen.getByLabelText('refresh-impact'));

    await waitFor(() =>
      expect(api.getDecisionImpact).toHaveBeenCalledWith('approval_routing'),
    );
    expect(screen.getByTestId('decision-impact-summary')).toHaveTextContent(
      'Used by 1 automation',
    );
    expect(screen.getByTestId('decision-impact-summary')).toHaveTextContent('2 个引用');
    expect(screen.getByTestId('decision-impact-summary')).toHaveTextContent('需确认');
  });

  it('evaluates the selected published decision with mock context', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(async () => ({
        status: 'MATCHED' as const,
        matched: true,
        traceId: 'trace-1',
        outputs: { route: 'DIRECTOR' },
      })),
    };

    render(
      <DecisionRuleBindingBlock
        api={api}
        block={{
          props: {
            mode: 'decision',
            initialDecisionCode: 'approval_routing',
            initialVersionPolicy: 'ROLLOUT',
            consumerType: 'AUTOMATION',
            consumerCode: 'auto-1',
            initialContextJson: '{"record":{"data":{"amount":20000}}}',
          },
        }}
      />,
    );

    fireEvent.click(screen.getByLabelText('run-decision-test'));

    await waitFor(() =>
      expect(api.evaluate).toHaveBeenCalledWith({
        decisionCode: 'approval_routing',
        binding: 'ROLLOUT',
        callerType: 'AUTOMATION',
        callerRef: 'auto-1',
        context: { record: { data: { amount: 20000 } } },
      }),
    );
    expect(screen.getByTestId('decision-test-result')).toHaveTextContent('"status": "MATCHED"');
    expect(screen.getByTestId('decision-test-result')).toHaveTextContent('"traceId": "trace-1"');
    expect(screen.getByTestId('decision-test-result')).toHaveTextContent('"route": "DIRECTOR"');
  });

  it('shows a test-run error for invalid mock context JSON', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(),
    };

    render(
      <DecisionRuleBindingBlock
        api={api}
        block={{
          props: {
            mode: 'decision',
            initialDecisionCode: 'approval_routing',
          },
        }}
      />,
    );

    fireEvent.change(screen.getByLabelText('test-run-context'), {
      target: { value: '{invalid' },
    });
    fireEvent.click(screen.getByLabelText('run-decision-test'));

    await waitFor(() => expect(screen.getByTestId('decision-test-error')).toBeInTheDocument());
    expect(api.evaluate).not.toHaveBeenCalled();
  });
});
