import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ExclusiveGatewayEditor } from '../ExclusiveGatewayEditor';
import { RuleCenterBindingSection } from '../RuleCenterBindingSection';

const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}));

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => ({
    get: apiMocks.get,
    post: apiMocks.post,
    delete: apiMocks.delete,
  }),
}));

describe('RuleCenterBindingSection', () => {
  beforeEach(() => {
    apiMocks.get.mockReset();
    apiMocks.post.mockReset();
    apiMocks.delete.mockReset();
    apiMocks.get.mockImplementation((endpoint: string, params?: Record<string, unknown>) => {
      if (endpoint === '/decision/facts/catalog') {
        expect(params).toEqual({ modelCode: 'wd_leave_request' });
        return Promise.resolve({
          data: {
            entities: [
              {
                entityCode: 'wd_leave_request',
                modelCode: 'wd_leave_request',
                modelName: '请假申请',
                facts: [
                  {
                    factKey: 'record.data.wd_leave_type',
                    scope: 'record',
                    path: 'data.wd_leave_type',
                    label: '请假类型',
                    dataType: 'dict',
                    dictCode: 'wd_leave_type',
                    allowedValues: [
                      { value: 'annual', label: '年假' },
                      { value: 'sick', label: '病假' },
                    ],
                  },
                ],
              },
            ],
          },
        });
      }
      if (endpoint === '/decision/model/fields') {
        return Promise.resolve({
          data: [
            {
              entityCode: 'record',
              modelCode: 'wd_leave_request',
              modelName: '请假申请',
              path: 'record.data.legacyOnly',
              label: '旧字段目录',
              dataType: 'string',
            },
          ],
        });
      }
      return Promise.resolve({ data: [] });
    });
  });

  it('creates a BPM-scoped default binding when enabled', () => {
    const onToggle = vi.fn();
    const onChange = vi.fn();

    render(
      <RuleCenterBindingSection
        title="规则中心路由"
        enabledLabel="Use Rule Center"
        enabled={false}
        mode="combined"
        consumerCode="approval_process"
        consumerNodeId="gateway_route"
        testId="rule-center-section"
        onToggle={onToggle}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByTestId('rule-center-section-toggle'));

    expect(onToggle).toHaveBeenCalledWith(
      true,
      expect.objectContaining({
        consumerType: 'BPM',
        consumerCode: 'approval_process',
        consumerNodeId: 'gateway_route',
        bindingKind: 'DECISION_REF',
        enabled: true,
        decisionBinding: expect.objectContaining({
          decisionCode: 'approval_routing',
          versionPolicy: 'LATEST_PUBLISHED',
        }),
        conditionSpec: expect.objectContaining({
          root: expect.objectContaining({ type: 'group', op: 'AND' }),
        }),
      }),
    );
  });

  it('keeps BPM consumer metadata when the embedded block changes', () => {
    const onChange = vi.fn();

    render(
      <RuleCenterBindingSection
        title="规则中心分派"
        enabledLabel="Use Rule Center"
        enabled
        mode="decision"
        value={{
          consumerType: 'BPM',
          consumerCode: 'approval_process',
          consumerNodeId: 'task_assign',
          bindingKind: 'DECISION_REF',
          decisionBinding: {
            decisionCode: 'task_assignee',
            versionPolicy: 'LATEST_PUBLISHED',
            inputMappings: [],
            outputMappings: [],
            fallbackPolicy: { mode: 'FAIL_CLOSED' },
            traceMode: 'SAMPLED',
            enabled: true,
          },
          enabled: true,
        }}
        consumerCode="approval_process"
        consumerNodeId="task_assign"
        testId="rule-center-section"
        onToggle={vi.fn()}
        onChange={onChange}
      />,
    );

    fireEvent.change(screen.getByLabelText('version-policy'), {
      target: { value: 'ROLLOUT' },
    });

    expect(onChange).toHaveBeenLastCalledWith(
      expect.objectContaining({
        consumerType: 'BPM',
        consumerCode: 'approval_process',
        consumerNodeId: 'task_assign',
        decisionBinding: expect.objectContaining({ versionPolicy: 'ROLLOUT' }),
      }),
    );
  });

  it('can render a default-enabled BPM binding with a usable test context', () => {
    render(
      <RuleCenterBindingSection
        title="规则中心分派"
        enabledLabel="Use Rule Center"
        enabled={false}
        defaultEnabled
        mode="decision"
        consumerCode="wd_leave_approval"
        consumerNodeId="task_manager_approve"
        testId="rule-center-section"
        onToggle={vi.fn()}
        onChange={vi.fn()}
      />,
    );

    expect(screen.getByTestId('rule-center-section-toggle')).toBeChecked();
    expect(screen.getByTestId('rule-center-section-editor')).toHaveTextContent('任务分派');
    expect(screen.getByTestId('decision-test-runner')).toHaveTextContent('REQ-LONG-LEAVE-SAMPLE');
  });

  it('merges BPM rule fields from the unified fact catalog into the embedded rule binding editor', async () => {
    render(
      <RuleCenterBindingSection
        title="规则中心路由"
        enabledLabel="Use Rule Center"
        enabled
        mode="combined"
        consumerCode="wd_leave_approval"
        consumerNodeId="gateway_route"
        testId="rule-center-section"
        onToggle={vi.fn()}
        onChange={vi.fn()}
      />,
    );

    await waitFor(() =>
      expect(apiMocks.get).toHaveBeenCalledWith('/decision/facts/catalog', { modelCode: 'wd_leave_request' }),
    );
    expect(apiMocks.get).not.toHaveBeenCalledWith('/decision/model/fields', undefined);

    fireEvent.click(screen.getByTestId('cb-add'));
    const fieldPicker = screen.getByLabelText('field-0');
    expect(fieldPicker).toHaveTextContent('请假类型');
    expect(fieldPicker).not.toHaveTextContent('旧字段目录');

    fireEvent.change(fieldPicker, { target: { value: 'record:data.wd_leave_type' } });
    const valuePicker = screen.getByLabelText('value-0');
    expect(valuePicker).toHaveTextContent('年假');
    expect(valuePicker).toHaveTextContent('病假');
  });
});

describe('BPM gateway property editor rule-center integration', () => {
  it('shows gateway rule binding by default on the existing gateway config', () => {
    const onChange = vi.fn();

    render(
      <ExclusiveGatewayEditor
        config={{ name: 'Route' }}
        outgoingEdges={[]}
        processKey="approval_process"
        nodeId="gateway_route"
        onChange={onChange}
      />,
    );

    expect(screen.getByTestId('exclusivegateway-rule-binding-toggle')).toBeChecked();
    expect(screen.getByTestId('exclusivegateway-rule-binding-editor')).toHaveTextContent('请假审批分派');
    expect(screen.getByTestId('decision-test-runner')).toHaveTextContent('REQ-LONG-LEAVE-SAMPLE');
    expect(onChange).not.toHaveBeenCalled();
  });
});
