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
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('灰度发布');
    expect(screen.getByRole('option', { name: '灰度发布' })).toHaveValue('ROLLOUT');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('请假审批分派');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('amount');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('业务记录 · 金额');
    expect(screen.getByTestId('decision-binding-preview')).not.toHaveTextContent(
      'approval_routing',
    );
    expect(screen.getByTestId('decision-binding-preview')).not.toHaveTextContent('data.amount');
  });

  it('focuses compact rule authoring into condition, decision, impact and test workspaces', () => {
    render(
      <DecisionRuleBindingBlock
        block={{
          props: {
            mode: 'combined',
            initialDecisionCode: 'approval_routing',
            fields: [{ scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' }],
            decisions: [{ code: 'approval_routing', name: '审批路由' }],
          },
        }}
      />,
    );

    expect(screen.getByTestId('decision-rule-section-tab-condition')).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByTestId('decision-rule-section-condition')).toHaveAttribute(
      'data-active',
      'true',
    );
    expect(screen.getByTestId('decision-rule-section-decision')).toHaveAttribute(
      'data-active',
      'false',
    );
    expect(screen.getByTestId('decision-rule-section-impact')).toHaveAttribute(
      'data-active',
      'false',
    );
    expect(screen.getByTestId('decision-rule-section-test')).toHaveAttribute(
      'data-active',
      'false',
    );

    fireEvent.click(screen.getByTestId('decision-rule-section-tab-decision'));

    expect(screen.getByTestId('decision-rule-section-tab-decision')).toHaveAttribute(
      'aria-pressed',
      'true',
    );
    expect(screen.getByTestId('decision-rule-section-condition')).toHaveAttribute(
      'data-active',
      'false',
    );
    expect(screen.getByTestId('decision-rule-section-decision')).toHaveAttribute(
      'data-active',
      'true',
    );

    fireEvent.click(screen.getByTestId('decision-rule-section-tab-test'));

    expect(screen.getByTestId('decision-rule-section-test')).toHaveAttribute('data-active', 'true');
    expect(screen.getByTestId('decision-rule-section-decision')).toHaveAttribute(
      'data-active',
      'false',
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
    expect(screen.getByRole('option', { name: '流程变量' })).toHaveValue('PROCESS_VARIABLE');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('assigneeUserId');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent(
      '流程变量 · assigneeUserId',
    );
    expect(screen.getByTestId('decision-binding-preview')).not.toHaveTextContent(
      'PROCESS_VARIABLE',
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

  it('uses the selected decision output schema as the downstream output picker', () => {
    const onChange = vi.fn();

    render(
      <DecisionRuleBindingBlock
        onChange={onChange}
        block={{
          props: {
            mode: 'decision',
            consumerType: 'BPM',
            initialDecisionCode: 'approval_routing',
            decisions: [
              {
                code: 'approval_routing',
                name: '请假审批分派',
                outputSchemaJson: {
                  outputs: [
                    { id: 'candidateGroups', label: '候选组', dataType: 'collection' },
                    { id: 'assigneeUserId', label: '审批人', dataType: 'string' },
                  ],
                },
              },
            ],
          },
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '添加输出' }));

    expect(screen.getByLabelText('output-mapping-output-0')).toHaveValue('candidateGroups');
    expect(screen.getByLabelText('output-mapping-kind-0')).toHaveValue('PROCESS_VARIABLE');
    expect(screen.getByLabelText('output-mapping-path-0')).toHaveValue('candidateGroups');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('候选组');
    expect(screen.getByTestId('output-mapping-output-count-0')).toHaveTextContent('2 / 2');

    fireEvent.change(screen.getByLabelText('output-mapping-output-search-0'), {
      target: { value: '审批' },
    });
    expect(screen.getByTestId('output-mapping-output-count-0')).toHaveTextContent('1 / 2');

    fireEvent.change(screen.getByLabelText('output-mapping-output-picker-0'), {
      target: { value: 'assigneeUserId' },
    });

    expect(screen.getByLabelText('output-mapping-output-0')).toHaveValue('assigneeUserId');
    expect(screen.getByLabelText('output-mapping-path-0')).toHaveValue('assigneeUserId');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('审批人');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent(
      '流程变量 · assigneeUserId',
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

  it('keeps default output schema when a host supplies decision options without outputs', () => {
    render(
      <DecisionRuleBindingBlock
        block={{
          props: {
            mode: 'decision',
            consumerType: 'SLA',
            initialDecisionCode: 'complaint_sla_deadline',
            decisions: [
              { code: 'complaint_sla_deadline', name: '投诉 SLA 截止时间' },
              { code: 'sla_deadline', name: 'SLA 截止时间' },
            ],
          },
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '添加输出' }));

    expect(screen.getByLabelText('output-mapping-output-0')).toHaveValue('deadlineMinutes');
    expect(screen.getByLabelText('output-mapping-kind-0')).toHaveValue('SLA_FIELD');
    expect(screen.getByLabelText('output-mapping-output-picker-0')).toHaveTextContent('截止分钟');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('截止分钟');
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
    expect(screen.getByText('请假审批 SLA 截止时间')).toBeInTheDocument();
    expect(screen.queryByText('投诉 SLA 截止时间')).not.toBeInTheDocument();
    expect(screen.queryByText('complaint_sla_deadline')).not.toBeInTheDocument();
    expect(screen.getByText('ticketPriority')).toBeInTheDocument();
    expect(screen.getByText('业务记录 · 优先级')).toBeInTheDocument();
    expect(screen.getByText('截止分钟')).toBeInTheDocument();
    expect(screen.getByText('SLA 字段 · deadlineMinutes')).toBeInTheDocument();
    expect(screen.getByText('最新已发布')).toBeInTheDocument();
    expect(screen.getByText('异常时阻断')).toBeInTheDocument();
    expect(screen.queryByText('LATEST_PUBLISHED')).not.toBeInTheDocument();
    expect(screen.queryByText('FAIL_CLOSED')).not.toBeInTheDocument();

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
    expect(screen.getByRole('option', { name: '灰度发布' })).toHaveValue('ROLLOUT');
    expect(screen.getByRole('option', { name: '异常时放行' })).toHaveValue('FAIL_OPEN');
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
          {
            scope: 'record' as const,
            path: 'data.priority',
            label: '优先级',
            dataType: 'enum' as const,
          },
        ],
      },
    };

    const { rerender } = render(<DecisionRuleBindingBlock runtime={runtime} block={block} />);

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
            fields: [{ scope: 'record', path: 'data.priority', label: '优先级', dataType: 'enum' }],
          },
        }}
      />,
    );

    expect(screen.getByLabelText('decision-code')).toHaveValue('sla_deadline');
    expect(screen.getByLabelText('mapping-input-0')).toHaveValue('catalogPriority');
    expect(screen.getByLabelText('mapping-field-0')).toHaveValue('record:data.priority');
  });

  it('keeps an existing input mapping selectable when the field catalog has not loaded it yet', () => {
    render(
      <DecisionRuleBindingBlock
        value={{
          bindingKind: 'DECISION_REF',
          decisionBinding: {
            decisionCode: 'leave_request_automation',
            versionPolicy: 'LATEST_PUBLISHED',
            inputMappings: [
              {
                input: 'leaveDays',
                source: { kind: 'FIELD', scope: 'record', path: 'data.wd_req_days' },
              },
            ],
            outputMappings: [],
            fallbackPolicy: { mode: 'FAIL_CLOSED' },
            traceMode: 'ALWAYS',
            enabled: true,
          },
          enabled: true,
        }}
        block={{
          props: {
            mode: 'decision',
            fields: [{ scope: 'record', path: 'data.amount', label: '金额', dataType: 'decimal' }],
            decisions: [{ code: 'leave_request_automation', name: '请假申请自动化策略' }],
          },
        }}
      />,
    );

    expect(screen.getByLabelText('mapping-input-0')).toHaveValue('leaveDays');
    expect(screen.getByLabelText('mapping-field-0')).toHaveValue('record:data.wd_req_days');
    expect(screen.getByRole('option', { name: 'record.data.wd_req_days' })).toHaveValue(
      'record:data.wd_req_days',
    );
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

    const fieldPicker = screen.getByLabelText('mapping-field-0') as HTMLSelectElement;
    expect(fieldPicker).toHaveTextContent('SLA Catalog Priority');
    expect(fieldPicker).toHaveTextContent('目标键');
  });

  it('prefers unified fact catalog fields for rule binding mappings when available', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(),
      getFactCatalog: vi.fn(async () => ({
        entities: [
          {
            entityCode: 'wd_leave_request',
            modelCode: 'wd_leave_request',
            label: '请假申请',
            sourceType: 'sqlView',
            sourceRef: 'select * from mt_wd_leave_request',
            facts: [
              {
                factKey: 'wd_leave_request.wd_leave_type',
                scope: 'record',
                path: 'record.data.wd_leave_type',
                label: '请假类型',
                dataType: 'dict',
                dictCode: 'wd_leave_type',
                operators: ['EQ', 'IN'],
                allowedValues: [
                  { value: 'annual', label: '年假' },
                  { value: 'sick', label: '病假' },
                ],
              },
            ],
          },
        ],
      })),
      getModelFields: vi.fn(async () => [
        {
          entityCode: 'record',
          path: 'data.legacyOnlyField',
          label: '旧字段目录',
          dataType: 'string' as const,
        },
      ]),
    };

    render(
      <DecisionRuleBindingBlock
        api={api}
        block={{
          props: {
            mode: 'combined',
            fieldCatalogMode: 'merge',
            fieldCatalogModelCode: 'wd_leave_request',
            initialDecisionCode: 'leave_request_automation',
          },
        }}
      />,
    );

    await waitFor(() => expect(api.getFactCatalog).toHaveBeenCalledWith('wd_leave_request'));
    expect(api.getModelFields).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('cb-add'));
    fireEvent.change(screen.getByLabelText('field-0'), {
      target: { value: 'record:data.wd_leave_type' },
    });
    expect(screen.getByLabelText('value-0')).toHaveTextContent('年假');
    expect(screen.getByLabelText('value-0')).toHaveTextContent('病假');

    fireEvent.click(screen.getByTestId('decision-rule-section-tab-decision'));
    fireEvent.click(screen.getByRole('button', { name: '添加映射' }));
    const fieldPicker = screen.getByLabelText('mapping-field-0') as HTMLSelectElement;
    expect(fieldPicker).toHaveTextContent('请假类型');
    expect(fieldPicker).not.toHaveTextContent('旧字段目录');
  });

  it('keeps masked fact catalog fields unavailable for decision input mappings', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(),
      getFactCatalog: vi.fn(async () => ({
        entities: [
          {
            entityCode: 'wd_leave_request',
            modelCode: 'wd_leave_request',
            label: '请假申请',
            facts: [
              {
                factKey: 'wd_leave_request.salary',
                scope: 'record',
                path: 'record.data.salary',
                label: '敏感工资',
                dataType: 'decimal',
                masked: true,
              },
              {
                factKey: 'wd_leave_request.wd_req_days',
                scope: 'record',
                path: 'record.data.wd_req_days',
                label: '请假天数',
                dataType: 'decimal',
              },
            ],
          },
        ],
      })),
      getModelFields: vi.fn(),
    };

    render(
      <DecisionRuleBindingBlock
        api={api}
        block={{
          props: {
            mode: 'decision',
            fieldCatalogMode: 'merge',
            fieldCatalogModelCode: 'wd_leave_request',
            initialDecisionCode: 'permission_amount_guard',
            decisions: [{ code: 'permission_amount_guard', name: '权限金额守卫' }],
          },
        }}
      />,
    );

    await waitFor(() => expect(api.getFactCatalog).toHaveBeenCalledWith('wd_leave_request'));
    fireEvent.click(screen.getByRole('button', { name: '添加映射' }));

    const fieldPicker = screen.getByLabelText('mapping-field-0') as HTMLSelectElement;
    expect(fieldPicker).toHaveValue('record:data.wd_req_days');
    const maskedOption = Array.from(fieldPicker.querySelectorAll('option')).find(
      (option) => option.textContent === '敏感工资 · 字段已脱敏',
    );
    expect(maskedOption).toBeDefined();
    expect(maskedOption).toBeDisabled();

    fireEvent.change(fieldPicker, { target: { value: 'record:data.salary' } });

    expect(fieldPicker).toHaveValue('record:data.wd_req_days');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('请假天数');
    expect(screen.getByTestId('decision-binding-preview')).not.toHaveTextContent('敏感工资');
  });

  it('honors low-permission fact catalog projection without reintroducing hidden fields', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(),
      getFactCatalog: vi.fn(async () => ({
        entities: [
          {
            entityCode: 'wd_leave_request',
            modelCode: 'wd_leave_request',
            label: '请假申请',
            facts: [
              {
                factKey: 'wd_leave_request.wd_req_days',
                scope: 'record',
                path: 'record.data.wd_req_days',
                label: '请假天数',
                dataType: 'decimal',
                editable: true,
              },
              {
                factKey: 'wd_leave_request.wd_req_note',
                scope: 'record',
                path: 'record.data.wd_req_note',
                label: '备注',
                dataType: 'text',
                editable: false,
              },
            ],
          },
        ],
      })),
      getModelFields: vi.fn(async () => [
        {
          entityCode: 'record',
          path: 'data.wd_req_salary',
          label: '敏感工资',
          dataType: 'decimal' as const,
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
            fieldCatalogModelCode: 'wd_leave_request',
            initialDecisionCode: 'permission_amount_guard',
            decisions: [{ code: 'permission_amount_guard', name: '权限金额守卫' }],
          },
        }}
      />,
    );

    await waitFor(() => expect(api.getFactCatalog).toHaveBeenCalledWith('wd_leave_request'));
    expect(api.getModelFields).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole('button', { name: '添加映射' }));

    const fieldPicker = screen.getByLabelText('mapping-field-0') as HTMLSelectElement;
    expect(fieldPicker).toHaveTextContent('请假天数');
    expect(fieldPicker).toHaveTextContent('备注 · 只读字段');
    expect(fieldPicker).not.toHaveTextContent('敏感工资');

    const viewOnlyOption = Array.from(fieldPicker.querySelectorAll('option')).find(
      (option) => option.value === 'record:data.wd_req_note',
    );
    expect(viewOnlyOption).toBeDefined();
    expect(viewOnlyOption).not.toBeDisabled();

    fireEvent.change(screen.getByLabelText('mapping-input-0'), { target: { value: 'note' } });
    fireEvent.change(fieldPicker, { target: { value: 'record:data.wd_req_note' } });

    expect(fieldPicker).toHaveValue('record:data.wd_req_note');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('备注');
    expect(screen.getByTestId('decision-binding-preview')).not.toHaveTextContent('敏感工资');
  });

  it('resolves fact catalog model code from the host DSL form runtime', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(),
      getFactCatalog: vi.fn(async () => ({
        entities: [
          {
            entityCode: 'wd_leave_request',
            modelCode: 'wd_leave_request',
            label: '请假申请',
            facts: [
              {
                factKey: 'wd_leave_request.wd_leave_type',
                scope: 'record',
                path: 'record.data.wd_leave_type',
                label: '请假类型',
                dataType: 'dict',
                dictCode: 'wd_leave_type',
                operators: ['EQ', 'IN'],
                allowedValues: [
                  { value: 'annual', label: '年假' },
                  { value: 'sick', label: '病假' },
                ],
              },
            ],
          },
        ],
      })),
      getModelFields: vi.fn(),
    };

    render(
      <DecisionRuleBindingBlock
        api={api}
        runtime={{
          getFieldValue: (fieldCode) =>
            fieldCode === 'model_code' ? 'wd_leave_request' : undefined,
        }}
        block={{
          props: {
            mode: 'combined',
            fieldCatalogMode: 'merge',
            fieldCatalogModelCodeField: 'model_code',
            fields: [
              { scope: 'record', path: 'data.targetKey', label: '目标键', dataType: 'string' },
            ],
            initialDecisionCode: 'leave_request_automation',
          },
        }}
      />,
    );

    await waitFor(() => expect(api.getFactCatalog).toHaveBeenCalledWith('wd_leave_request'));
    expect(api.getModelFields).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId('cb-add'));
    fireEvent.change(screen.getByLabelText('field-0'), {
      target: { value: 'record:data.wd_leave_type' },
    });
    expect(screen.getByLabelText('value-0')).toHaveTextContent('年假');
    expect(screen.getByLabelText('value-0')).toHaveTextContent('病假');

    fireEvent.click(screen.getByTestId('decision-rule-section-tab-decision'));
    fireEvent.click(screen.getByRole('button', { name: '添加映射' }));
    expect(screen.getByLabelText('mapping-field-0')).toHaveTextContent('请假类型');
    expect(screen.getByLabelText('mapping-field-0')).toHaveTextContent('目标键');
  });

  it('filters and groups input mapping source fields without losing the selected field', () => {
    render(
      <DecisionRuleBindingBlock
        block={{
          props: {
            mode: 'decision',
            initialDecisionCode: 'leave_request_automation',
            fields: [
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
            ],
          },
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '添加映射' }));
    const fieldPicker = screen.getByLabelText('mapping-field-0');
    expect(fieldPicker.querySelector('optgroup[label="请假申请"]')).not.toBeNull();
    expect(fieldPicker.querySelector('optgroup[label="操作者"]')).not.toBeNull();

    fireEvent.change(screen.getByLabelText('mapping-field-search-0'), {
      target: { value: '部门' },
    });

    expect(screen.getByTestId('mapping-field-count-0')).toHaveTextContent('1 / 3');
    expect(fieldPicker).toHaveTextContent('请假申请 / 请假天数');
    expect(fieldPicker).toHaveTextContent('用户部门');
    expect(fieldPicker).not.toHaveTextContent('Agent 记忆 / 访问次数');

    fireEvent.change(fieldPicker, {
      target: { value: 'actor:departmentId' },
    });

    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('用户部门');
    expect(screen.getByTestId('decision-binding-preview')).not.toHaveTextContent('departmentId');
  });

  it('uses field suggestions to fill an output target path while preserving free-form editing', () => {
    render(
      <DecisionRuleBindingBlock
        block={{
          props: {
            mode: 'decision',
            initialDecisionCode: 'approval_routing',
            fields: [
              { scope: 'record', path: 'data.approverUserId', label: '审批人', dataType: 'user' },
              { scope: 'record', path: 'data.priority', label: '优先级', dataType: 'enum' },
              {
                scope: 'process',
                path: 'variables.escalationLevel',
                label: '升级等级',
                dataType: 'integer',
              },
            ],
          },
        }}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: '添加输出' }));
    fireEvent.change(screen.getByLabelText('output-mapping-path-0'), {
      target: { value: 'manual.path' },
    });
    expect(screen.getByLabelText('output-mapping-path-0')).toHaveValue('manual.path');

    fireEvent.change(screen.getByLabelText('output-mapping-target-field-search-0'), {
      target: { value: '审批' },
    });

    expect(screen.getByTestId('output-mapping-target-field-count-0')).toHaveTextContent('1 / 3');
    const targetPicker = screen.getByLabelText('output-mapping-target-field-0');
    expect(targetPicker).toHaveTextContent('审批人');
    expect(targetPicker).not.toHaveTextContent('优先级');

    fireEvent.change(targetPicker, {
      target: { value: 'record:data.approverUserId' },
    });

    expect(screen.getByLabelText('output-mapping-path-0')).toHaveValue('data.approverUserId');
    expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent(
      '动作参数 · data.approverUserId',
    );
  });

  it('filters backend model fields to the host model while keeping shared runtime context', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(),
      getModelFields: vi.fn(async () => [
        {
          entityCode: 'record',
          path: 'data.wd_req_days',
          label: '请假申请 / 请假天数',
          dataType: 'integer' as const,
          modelCode: 'wd_leave_request',
          modelName: '请假申请',
        },
        {
          entityCode: 'record',
          path: 'data.access_count',
          label: 'Agent 记忆 / 访问次数',
          dataType: 'integer' as const,
          modelCode: 'agent_memory',
          modelName: 'Agent 记忆',
        },
        {
          entityCode: 'actor',
          path: 'departmentId',
          label: '用户部门',
          dataType: 'department' as const,
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
            fieldCatalogModelCode: 'wd_leave_request',
            initialDecisionCode: 'leave_request_automation',
          },
        }}
      />,
    );

    await waitFor(() => expect(api.getModelFields).toHaveBeenCalled());
    fireEvent.click(screen.getByRole('button', { name: '添加映射' }));

    const fieldPicker = screen.getByLabelText('mapping-field-0');
    expect(fieldPicker).toHaveTextContent('请假申请 / 请假天数');
    expect(fieldPicker).toHaveTextContent('用户部门');
    expect(fieldPicker).not.toHaveTextContent('Agent 记忆 / 访问次数');
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
      expect(screen.getByTestId('decision-binding-preview')).toHaveTextContent('existing_decision'),
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

    await waitFor(() => expect(api.getDecisionImpact).toHaveBeenCalledWith('approval_routing'));
    expect(screen.getByTestId('decision-impact-summary')).toHaveTextContent('Used by 1 automation');
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
    expect(screen.getByTestId('decision-test-result')).toHaveTextContent('已命中');
    expect(screen.getByTestId('decision-test-result')).toHaveTextContent('trace-1');
    expect(screen.getByTestId('decision-test-open-trace')).toHaveAttribute(
      'href',
      '/p/decisionops_execution_logs?traceId=trace-1&decisionCode=approval_routing&callerType=AUTOMATION&callerRef=auto-1',
    );
    expect(screen.getByTestId('decision-test-result')).toHaveTextContent('route');
    expect(screen.getByTestId('decision-test-result')).toHaveTextContent('DIRECTOR');
    expect(screen.getByTestId('decision-test-result')).not.toHaveTextContent('"status": "MATCHED"');
  });

  it('resolves callerRef from the current record when consumerCodeField is configured', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(async () => ({
        status: 'MATCHED' as const,
        matched: true,
        traceId: 'trace-sla-1',
        outputs: { deadlineMinutes: 45 },
      })),
    };

    render(
      <DecisionRuleBindingBlock
        api={api}
        runtime={{
          getFieldValue: (fieldCode) => (fieldCode === 'pid' ? '01SLA_CONFIG' : undefined),
          getContext: () => ({
            record: {
              pid: '01SLA_CONFIG',
              model_code: 'wd_leave_request',
            },
          }),
        }}
        block={{
          props: {
            mode: 'decision',
            initialDecisionCode: 'complaint_sla_deadline',
            consumerType: 'SLA',
            consumerCodeField: 'pid',
            initialContextJson: '{"record":{"data":{"wd_req_type":"annual"}}}',
          },
        }}
      />,
    );

    fireEvent.click(screen.getByLabelText('run-decision-test'));

    await waitFor(() =>
      expect(api.evaluate).toHaveBeenCalledWith(
        expect.objectContaining({
          decisionCode: 'complaint_sla_deadline',
          callerType: 'SLA',
          callerRef: '01SLA_CONFIG',
        }),
      ),
    );
    expect(screen.getByTestId('decision-test-open-trace')).toHaveAttribute(
      'href',
      '/p/decisionops_execution_logs?traceId=trace-sla-1&decisionCode=complaint_sla_deadline&callerType=SLA&callerRef=01SLA_CONFIG',
    );
  });

  it('applies input mappings before running the decision test', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(async () => ({
        status: 'MATCHED' as const,
        matched: true,
        traceId: 'trace-leave',
        outputs: { actionType: 'send_notification' },
      })),
    };

    render(
      <DecisionRuleBindingBlock
        api={api}
        value={{
          bindingKind: 'DECISION_REF',
          decisionBinding: {
            decisionCode: 'leave_request_automation',
            versionPolicy: 'LATEST_PUBLISHED',
            inputMappings: [
              {
                input: 'leaveDays',
                source: { kind: 'FIELD', scope: 'record', path: 'data.wd_req_days' },
              },
            ],
            outputMappings: [],
            fallbackPolicy: { mode: 'FAIL_CLOSED' },
            traceMode: 'ALWAYS',
            enabled: true,
          },
          enabled: true,
        }}
        block={{
          props: {
            mode: 'decision',
            consumerType: 'AUTOMATION',
            consumerCode: 'wd_leave_high_value_notify',
            initialContextJson: '{"record":{"data":{"wd_req_days":4}}}',
            decisions: [{ code: 'leave_request_automation', name: '请假申请自动化策略' }],
          },
        }}
      />,
    );

    fireEvent.click(screen.getByLabelText('run-decision-test'));

    await waitFor(() =>
      expect(api.evaluate).toHaveBeenCalledWith(
        expect.objectContaining({
          decisionCode: 'leave_request_automation',
          callerType: 'AUTOMATION',
          callerRef: 'wd_leave_high_value_notify',
          context: {
            record: {
              data: {
                wd_req_days: 4,
                leaveDays: 4,
              },
            },
          },
        }),
      ),
    );
    expect(screen.getByTestId('decision-test-result')).toHaveTextContent('send_notification');
  });

  it('edits test context through structured field rows before running a decision test', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(async () => ({
        status: 'MATCHED' as const,
        matched: true,
        traceId: 'trace-context',
        outputs: { route: 'HR' },
      })),
    };

    render(
      <DecisionRuleBindingBlock
        api={api}
        block={{
          props: {
            mode: 'decision',
            initialDecisionCode: 'approval_routing',
            initialContextJson:
              '{"record":{"data":{"wd_req_days":4}},"process":{"nodeId":"gw_manager"}}',
            fields: [
              {
                scope: 'record',
                path: 'data.wd_req_days',
                label: '请假申请 / 请假天数',
                dataType: 'integer',
                modelCode: 'wd_leave_request',
                modelName: '请假申请',
              },
              {
                scope: 'process',
                path: 'nodeId',
                label: '流程节点',
                dataType: 'string',
              },
            ],
          },
        }}
      />,
    );

    expect(screen.getByTestId('decision-test-context-summary')).toHaveTextContent('2 个字段');
    expect(screen.queryByTestId('decision-test-context-drawer')).not.toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('open-test-context-drawer'));
    fireEvent.change(screen.getByLabelText('test-context-field-record-data-wd_req_days'), {
      target: { value: '7' },
    });
    fireEvent.change(screen.getByLabelText('test-context-field-process-nodeId'), {
      target: { value: 'gw_hr' },
    });
    fireEvent.change(screen.getByLabelText('test-context-field-search'), {
      target: { value: '流程' },
    });

    expect(screen.getByTestId('test-context-field-count')).toHaveTextContent('1 / 2');
    expect(screen.getByTestId('decision-test-context-drawer')).toHaveTextContent('流程节点');

    fireEvent.click(screen.getByText('高级 JSON'));
    expect(
      JSON.parse((screen.getByLabelText('test-run-context') as HTMLTextAreaElement).value),
    ).toMatchObject({
      record: { data: { wd_req_days: 7 } },
      process: { nodeId: 'gw_hr' },
    });

    fireEvent.click(screen.getByLabelText('run-decision-test'));

    await waitFor(() =>
      expect(api.evaluate).toHaveBeenCalledWith(
        expect.objectContaining({
          context: {
            record: {
              data: {
                wd_req_days: 7,
              },
            },
            process: {
              nodeId: 'gw_hr',
            },
          },
        }),
      ),
    );
  });

  it('shows virtual model source metadata and sends injected source context to runtime', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(async () => ({
        status: 'MATCHED' as const,
        matched: true,
        traceId: 'trace-virtual',
        outputs: { route: 'EXPEDITE' },
      })),
    };

    render(
      <DecisionRuleBindingBlock
        api={api}
        block={{
          props: {
            mode: 'decision',
            initialDecisionCode: 'approval_routing',
            initialContextJson: '{"record":{"data":{"slaRiskScore":82}}}',
            fields: [
              {
                scope: 'record',
                path: 'data.slaRiskScore',
                label: '请假 SLA 汇总 / 风险分',
                dataType: 'integer',
                modelCode: 'virtual_leave_request_summary',
                modelName: '请假 SLA 汇总',
                sourceType: 'sqlView',
                sourceRef: 'virtual.leave_request_summary.v1',
              },
            ],
          },
        }}
      />,
    );

    fireEvent.click(screen.getByLabelText('open-test-context-drawer'));

    expect(screen.getByTestId('decision-test-context-drawer')).toHaveTextContent(
      '请假 SLA 汇总 / 风险分',
    );
    expect(
      screen.getByTestId('test-context-field-source-record-data-slaRiskScore'),
    ).toHaveTextContent('sqlView · virtual.leave_request_summary.v1');

    fireEvent.change(screen.getByLabelText('test-context-field-record-data-slaRiskScore'), {
      target: { value: '91' },
    });
    fireEvent.click(screen.getByText('高级 JSON'));
    expect(
      JSON.parse((screen.getByLabelText('test-run-context') as HTMLTextAreaElement).value),
    ).toMatchObject({
      record: {
        data: {
          slaRiskScore: 91,
        },
      },
    });

    fireEvent.click(screen.getByLabelText('run-decision-test'));

    await waitFor(() =>
      expect(api.evaluate).toHaveBeenCalledWith(
        expect.objectContaining({
          context: {
            record: {
              data: {
                slaRiskScore: 91,
              },
            },
          },
        }),
      ),
    );
    expect(screen.getByTestId('decision-test-result')).toHaveTextContent('trace-virtual');
  });

  it('surfaces backend unknown reasons when virtual source context is missing', async () => {
    const api = {
      getDecisionImpact: vi.fn(),
      evaluate: vi.fn(async () => ({
        status: 'UNKNOWN' as const,
        matched: false,
        traceId: 'trace-missing-virtual',
        outputs: { matched: false, truth: 'UNKNOWN' },
        unknownReasons: ['path not present for operator GT in [record.data.slaRiskScore GT 80]'],
      })),
    };

    render(
      <DecisionRuleBindingBlock
        api={api}
        block={{
          props: {
            mode: 'decision',
            initialDecisionCode: 'approval_routing',
            initialContextJson: '{"record":{"data":{}}}',
            fields: [
              {
                scope: 'record',
                path: 'data.slaRiskScore',
                label: '请假 SLA 汇总 / 风险分',
                dataType: 'integer',
                modelCode: 'virtual_leave_request_summary',
                modelName: '请假 SLA 汇总',
                sourceType: 'sqlView',
                sourceRef: 'virtual.leave_request_summary.v1',
              },
            ],
          },
        }}
      />,
    );

    fireEvent.click(screen.getByLabelText('run-decision-test'));

    await waitFor(() =>
      expect(screen.getByTestId('decision-test-result')).toHaveTextContent('未知'),
    );
    expect(screen.getByTestId('decision-test-result')).toHaveTextContent('trace-missing-virtual');
    expect(screen.getByTestId('decision-test-unknown-reasons')).toHaveTextContent(
      'record.data.slaRiskScore',
    );
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
