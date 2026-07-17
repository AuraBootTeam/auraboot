import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DecisionActionPlanBlock } from '../DecisionActionPlanBlock';

describe('DecisionActionPlanBlock', () => {
  it('renders an SLA timeout action chain from the DSL value field and updates it from the action catalog', async () => {
    const runtime = {
      getFieldValue: vi.fn(() => ({
        trigger: 'SLA_TIMEOUT',
        failureStrategy: 'FAIL_FAST',
        actions: [
          {
            type: 'SEND_IM',
            target: 'ROLE:wd_manager',
            order: 10,
            payload: {
              title: 'SLA 超时提醒',
              content: '主管审批已超时',
            },
          },
        ],
        executionEffect: {
          lastStatus: 'SUCCESS',
          traceId: 'trace-sla-1',
        },
      })),
      updateField: vi.fn(),
    };
    const api = {
      getActionCatalog: vi.fn(async () => ({
        actions: [
          { actionType: 'NOTIFY', label: '站内通知', category: 'messaging' },
          { actionType: 'SEND_SMS', label: '发送短信', category: 'messaging' },
          { actionType: 'SEND_IM', label: '发送 IM', category: 'messaging' },
          { actionType: 'CREATE_TASK', label: '创建任务', category: 'collaboration' },
          { actionType: 'CC_TASK', label: '抄送任务', category: 'collaboration' },
        ],
      })),
    };

    render(
      <DecisionActionPlanBlock
        runtime={runtime}
        api={api}
        block={{
          props: {
            valueField: 'action_policy',
            title: '超时后动作',
            triggerLabel: 'SLA 超时',
            logsUrl:
              '/p/decisionops_execution_logs?callerType=SLA&callerRef=wd_manager_approve_sla',
          },
        }}
      />,
    );

    expect(screen.getByTestId('decision-action-plan-block')).toBeInTheDocument();
    expect(screen.getByTestId('decision-action-plan-block')).toHaveTextContent('动作目录');
    expect(screen.getByTestId('decision-action-plan-block')).not.toHaveTextContent('Action Catalog');
    expect(screen.getByText('超时后动作')).toBeInTheDocument();
    expect(screen.getByText('SLA 超时')).toBeInTheDocument();
    expect(screen.getByTestId('dap-failure-strategy')).toHaveTextContent('失败即停止');
    fireEvent.change(screen.getByLabelText('action-failure-strategy'), {
      target: { value: 'CONTINUE_ON_ERROR' },
    });
    expect(runtime.updateField).toHaveBeenLastCalledWith(
      'action_policy',
      expect.objectContaining({
        failureStrategy: 'CONTINUE_ON_ERROR',
      }),
    );
    const firstAction = screen.getByTestId('dap-action-0');
    expect(firstAction).toHaveTextContent('发送 IM');
    expect(firstAction.querySelector('input[aria-label="action-target-0"]')).toHaveValue(
      'ROLE:wd_manager',
    );
    expect(firstAction).toHaveTextContent('SLA 超时提醒');
    expect(screen.getByText('最近运行 成功')).toBeInTheDocument();
    expect(screen.queryByText('最近运行 SUCCESS')).not.toBeInTheDocument();
    expect(screen.getByText('trace-sla-1')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '查看日志' })).toHaveAttribute(
      'href',
      '/p/decisionops_execution_logs?callerType=SLA&callerRef=wd_manager_approve_sla',
    );

    await waitFor(() => expect(api.getActionCatalog).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByTestId('dap-add-action'));
    expect(screen.getByLabelText('action-type-1')).toHaveValue('NOTIFY');
    fireEvent.change(screen.getByLabelText('action-type-1'), {
      target: { value: 'CREATE_TASK' },
    });
    fireEvent.change(screen.getByLabelText('action-target-1'), {
      target: { value: 'USER:${assigneeUserId}' },
    });

    expect(runtime.updateField).toHaveBeenLastCalledWith(
      'action_policy',
      expect.objectContaining({
        trigger: 'SLA_TIMEOUT',
        failureStrategy: 'CONTINUE_ON_ERROR',
        actions: expect.arrayContaining([
          expect.objectContaining({ type: 'SEND_IM', target: 'ROLE:wd_manager' }),
          expect.objectContaining({ type: 'CREATE_TASK', target: 'USER:${assigneeUserId}' }),
        ]),
      }),
    );
  });

  it('renders detail pages as a read-only action summary and resolves log-link field templates', async () => {
    const runtime = {
      getFieldValue: vi.fn((fieldCode: string) => {
        if (fieldCode === 'action_policy') {
          return {
            trigger: 'SLA_TIMEOUT',
            failureStrategy: 'FAIL_FAST',
            actions: [
              {
                type: 'NOTIFY',
                target: 'ROLE:wd_manager',
                order: 10,
                payload: {
                  title: '主管审批 SLA 超时',
                  content: '主管审批节点已超时，请立即处理。',
                },
                idempotencyKeyTemplate: '${sla.recordPid}:manager_timeout:NOTIFY',
              },
            ],
            executionEffect: {
              lastStatus: 'SUCCESS',
              traceId: 'seed-sla-manager-timeout',
            },
          };
        }
        return undefined;
      }),
      updateField: vi.fn(),
    };

    render(
      <DecisionActionPlanBlock
        runtime={runtime}
        api={{
          getActionCatalog: vi.fn(async () => ({
            actions: [{ actionType: 'NOTIFY', label: '发送通知', category: 'messaging' }],
          })),
        }}
        block={{
          props: {
            readOnly: true,
            valueField: 'action_policy',
            title: '超时后动作',
            triggerLabel: 'SLA 超时',
            logsUrl: '/p/decisionops_execution_logs?callerType=SLA&callerRef={pid}',
            record: {
              name: { value: 'Manager Approval SLA' },
              pid: { value: '01SLA' },
            },
          },
        }}
      />,
    );

    expect(screen.getByTestId('decision-action-plan-block')).toHaveTextContent('超时后动作');
    expect(screen.getByTestId('decision-action-plan-block')).toHaveTextContent('失败即停止');
    expect(screen.getByTestId('decision-action-plan-block')).toHaveTextContent('ROLE:wd_manager');
    expect(screen.getByTestId('decision-action-plan-block')).toHaveTextContent(
      '主管审批节点已超时',
    );
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('action-target-0')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('action-payload-0')).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: '查看日志' })).toHaveAttribute(
      'href',
      '/p/decisionops_execution_logs?callerType=SLA&callerRef=01SLA',
    );
    expect(runtime.updateField).not.toHaveBeenCalled();
  });

  it('renders action catalog schema fields and writes structured payload changes', async () => {
    const runtime = {
      getFieldValue: vi.fn(() => ({
        trigger: 'SLA_TIMEOUT',
        actions: [
          {
            type: 'SEND_SMS',
            target: 'USER:${record.owner_phone}',
            order: 10,
            payload: {
              template: 'leave_timeout',
              content: '主管审批已超时',
            },
          },
        ],
      })),
      updateField: vi.fn(),
    };

    render(
      <DecisionActionPlanBlock
        runtime={runtime}
        api={{
          getActionCatalog: vi.fn(async () => ({
            actions: [
              {
                actionType: 'SEND_SMS',
                label: '发送短信',
                category: 'messaging',
                consumerTypes: ['SLA', 'EVENT_POLICY', 'AUTOMATION'],
                consumerAvailability: [
                  {
                    consumerType: 'SLA',
                    handlerAvailable: false,
                    availabilityStatus: 'UNAVAILABLE',
                    availabilityReason: '当前环境未配置真实短信 provider',
                    providerDependencies: [
                      {
                        providerType: 'SMS',
                        label: '真实短信 provider',
                        required: true,
                        available: false,
                        availabilityStatus: 'UNAVAILABLE',
                        availabilityReason: '当前环境未配置真实短信 provider',
                      },
                    ],
                  },
                  {
                    consumerType: 'EVENT_POLICY',
                    handlerAvailable: true,
                    availabilityStatus: 'AVAILABLE',
                  },
                ],
                inputSchema: {
                  required: ['target', 'payload.content'],
                  fields: {
                    target: { dataType: 'string', label: '手机号或接收人表达式', required: true },
                    'payload.template': { dataType: 'string', label: '短信模板编码' },
                    'payload.content': { dataType: 'text', label: '短信内容', required: true },
                  },
                },
              },
            ],
          })),
        }}
        block={{
          props: {
            valueField: 'action_policy',
            title: '超时后动作',
            consumerType: 'SLA',
          },
        }}
      />,
    );

    await waitFor(() =>
      expect(screen.getByLabelText('action-field-0-payload.content')).toHaveValue('主管审批已超时'),
    );
    expect(screen.getByTestId('dap-action-availability-0')).toHaveTextContent(
      '当前环境未配置真实短信 provider',
    );
    expect(screen.getByTestId('dap-action-provider-0')).toHaveTextContent(
      '依赖：真实短信 provider · 未配置',
    );
    const typeSelect = screen.getByLabelText('action-type-0') as HTMLSelectElement;
    expect(Array.from(typeSelect.options).map((option) => option.textContent)).toContain(
      '发送短信（不可用）',
    );
    expect(screen.getByLabelText('action-target-0')).toHaveValue('USER:${record.owner_phone}');
    expect(screen.getAllByText('必填').length).toBeGreaterThan(0);

    fireEvent.change(screen.getByLabelText('action-field-0-payload.content'), {
      target: { value: '长假申请超过 SLA，请立即处理' },
    });

    expect(runtime.updateField).toHaveBeenLastCalledWith(
      'action_policy',
      expect.objectContaining({
        actions: [
          expect.objectContaining({
            type: 'SEND_SMS',
            target: 'USER:${record.owner_phone}',
            payload: expect.objectContaining({
              template: 'leave_timeout',
              content: '长假申请超过 SLA，请立即处理',
            }),
          }),
        ],
      }),
    );
  });

  it('inserts SLA context fields and decision outputs into structured action fields', async () => {
    const runtime = {
      getFieldValue: vi.fn(() => ({
        trigger: 'SLA_TIMEOUT',
        actions: [
          {
            type: 'NOTIFY',
            target: 'ROLE:wd_manager',
            order: 10,
            payload: {
              title: '提醒 ',
              content: '请处理 ',
            },
          },
        ],
      })),
      updateField: vi.fn(),
    };

    render(
      <DecisionActionPlanBlock
        runtime={runtime}
        api={{
          getActionCatalog: vi.fn(async () => ({
            actions: [
              {
                actionType: 'NOTIFY',
                label: '发送通知',
                category: 'messaging',
                inputSchema: {
                  required: ['target', 'payload.title', 'payload.content'],
                  fields: {
                    target: { dataType: 'string', label: '接收人', required: true },
                    'payload.title': { dataType: 'string', label: '通知标题', required: true },
                    'payload.content': { dataType: 'text', label: '通知内容', required: true },
                  },
                },
              },
            ],
          })),
        }}
        block={{
          props: {
            valueField: 'action_policy',
            title: '超时后动作',
            fields: [
              { scope: 'record', path: 'data.targetKey', label: 'SLA 节点', dataType: 'string' },
              { scope: 'sla', path: 'deadlineMinutes', label: '截止分钟', dataType: 'integer' },
            ],
            decisionOutputs: ['deadlineMinutes', 'warningBeforeMinutes'],
          },
        }}
      />,
    );

    const titleField = await screen.findByTestId('dap-action-field-0-payload.title');
    fireEvent.click(pickerButtons(titleField, /^插入字段$/)[0]);

    const titlePicker = screen.getByTestId('formula-field-picker');
    expect(titlePicker).toHaveTextContent('SLA 节点');
    expect(titlePicker).toHaveTextContent('record.data.targetKey');
    expect(titlePicker).toHaveTextContent('规则输出');
    expect(titlePicker).toHaveTextContent('deadlineMinutes');
    fireEvent.click(pickerButtons(titlePicker, /SLA 节点.*record\.data\.targetKey/)[0]);

    expect(runtime.updateField).toHaveBeenLastCalledWith(
      'action_policy',
      expect.objectContaining({
        actions: [
          expect.objectContaining({
            payload: expect.objectContaining({
              title: '提醒 ${record.data.targetKey}',
            }),
          }),
        ],
      }),
    );

    const contentField = screen.getByTestId('dap-action-field-0-payload.content');
    fireEvent.click(pickerButtons(contentField, /^插入字段$/)[0]);
    const contentPicker = screen.getByTestId('formula-field-picker');
    fireEvent.click(pickerButtons(contentPicker, /deadlineMinutes.*decision\.outputs\.deadlineMinutes/)[0]);

    expect(runtime.updateField).toHaveBeenLastCalledWith(
      'action_policy',
      expect.objectContaining({
        actions: [
          expect.objectContaining({
            payload: expect.objectContaining({
              title: '提醒 ${record.data.targetKey}',
              content: '请处理 ${decision.outputs.deadlineMinutes}',
            }),
          }),
        ],
      }),
    );
  });

  it('builds formula insertion fields from structured decision output schema', async () => {
    const runtime = {
      getFieldValue: vi.fn(() => ({
        trigger: 'SLA_TIMEOUT',
        actions: [
          {
            type: 'NOTIFY',
            target: 'ROLE:wd_manager',
            order: 10,
            payload: {
              title: '提醒 ',
              content: '等级 ',
            },
          },
        ],
      })),
      updateField: vi.fn(),
    };

    render(
      <DecisionActionPlanBlock
        runtime={runtime}
        api={{
          getActionCatalog: vi.fn(async () => ({
            actions: [
              {
                actionType: 'NOTIFY',
                label: '发送通知',
                category: 'messaging',
                inputSchema: {
                  fields: {
                    target: { dataType: 'string', label: '接收人' },
                    'payload.title': { dataType: 'string', label: '标题' },
                    'payload.content': { dataType: 'text', label: '内容' },
                  },
                },
              },
            ],
          })),
        }}
        block={{
          props: {
            valueField: 'action_policy',
            title: '超时后动作',
            decisionOutputSchema: {
              outputs: [
                { id: 'escalationLevel', label: '升级等级', dataType: 'string' },
                { id: 'deadlineMinutes', label: '截止分钟', dataType: 'integer' },
              ],
            },
          },
        }}
      />,
    );

    const contentField = await screen.findByTestId('dap-action-field-0-payload.content');
    fireEvent.click(pickerButtons(contentField, /^插入字段$/)[0]);

    const picker = screen.getByTestId('formula-field-picker');
    expect(picker).toHaveTextContent('规则输出');
    expect(pickerButtons(picker, /升级等级.*decision\.outputs\.escalationLevel/).length).toBeGreaterThan(0);

    fireEvent.click(pickerButtons(picker, /升级等级.*decision\.outputs\.escalationLevel/)[0]);

    expect(runtime.updateField).toHaveBeenLastCalledWith(
      'action_policy',
      expect.objectContaining({
        actions: [
          expect.objectContaining({
            payload: expect.objectContaining({
              content: '等级 ${decision.outputs.escalationLevel}',
            }),
          }),
        ],
      }),
    );
  });

  it('loads SLA action insertion fields from the unified fact catalog before static DSL fields', async () => {
    const runtime = {
      getFieldValue: vi.fn((fieldCode: string) => {
        if (fieldCode === 'model_code') return 'wd_leave_request';
        if (fieldCode === 'action_policy') {
          return {
            trigger: 'SLA_TIMEOUT',
            actions: [
              {
                type: 'NOTIFY',
                target: 'ROLE:wd_manager',
                order: 10,
                payload: {
                  title: '提醒 ',
                  content: '请处理 ',
                },
              },
            ],
          };
        }
        return undefined;
      }),
      updateField: vi.fn(),
    };
    const api = {
      getActionCatalog: vi.fn(async () => ({
        actions: [
          {
            actionType: 'NOTIFY',
            label: '发送通知',
            category: 'messaging',
            inputSchema: {
              required: ['target', 'payload.title', 'payload.content'],
              fields: {
                target: { dataType: 'string', label: '接收人', required: true },
                'payload.title': { dataType: 'string', label: '通知标题', required: true },
                'payload.content': { dataType: 'text', label: '通知内容', required: true },
              },
            },
          },
        ],
      })),
      getFactCatalog: vi.fn(async (modelCode?: string) => {
        expect(modelCode).toBe('wd_leave_request');
        return {
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
                  allowedValues: [
                    { value: 'annual', label: '年假' },
                    { value: 'sick', label: '病假' },
                  ],
                },
                {
                  factKey: 'record.data.wd_req_days',
                  scope: 'record',
                  path: 'data.wd_req_days',
                  label: '请假天数',
                  dataType: 'decimal',
                },
              ],
            },
          ],
          facts: [
            {
              factKey: 'sla.deadlineMinutes',
              scope: 'sla',
              path: 'deadlineMinutes',
              label: '截止分钟',
              dataType: 'integer',
            },
          ],
        };
      }),
    };

    render(
      <DecisionActionPlanBlock
        runtime={runtime}
        api={api}
        block={{
          props: {
            valueField: 'action_policy',
            title: '超时后动作',
            fieldCatalogModelCodeField: 'model_code',
            decisionOutputs: ['deadlineMinutes'],
          },
        }}
      />,
    );

    await waitFor(() => expect(api.getFactCatalog).toHaveBeenCalledWith('wd_leave_request'));

    const titleField = await screen.findByTestId('dap-action-field-0-payload.title');
    fireEvent.click(pickerButtons(titleField, /^插入字段$/)[0]);

    const picker = screen.getByTestId('formula-field-picker');
    expect(picker).toHaveTextContent('请假申请');
    expect(pickerButtons(picker, /请假类型.*record\.data\.wd_leave_type/).length).toBeGreaterThan(0);
    expect(pickerButtons(picker, /截止分钟.*sla\.deadlineMinutes/).length).toBeGreaterThan(0);
    expect(pickerButtons(picker, /deadlineMinutes.*decision\.outputs\.deadlineMinutes/).length).toBeGreaterThan(0);

    fireEvent.click(pickerButtons(picker, /请假类型.*record\.data\.wd_leave_type/)[0]);

    expect(runtime.updateField).toHaveBeenLastCalledWith(
      'action_policy',
      expect.objectContaining({
        actions: [
          expect.objectContaining({
            payload: expect.objectContaining({
              title: '提醒 ${record.data.wd_leave_type}',
            }),
          }),
        ],
      }),
    );
  });
});

function pickerButtons(container: HTMLElement, name: RegExp): HTMLButtonElement[] {
  return Array.from(container.querySelectorAll('button')).filter((button) =>
    name.test(button.textContent?.replace(/\s+/g, ' ') ?? ''),
  );
}
