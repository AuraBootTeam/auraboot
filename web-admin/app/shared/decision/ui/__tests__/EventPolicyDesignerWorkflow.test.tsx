import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { EventPolicyDesignerWorkflow } from '../EventPolicyDesignerWorkflow';
import type { DecisionApi, EventPolicySummary } from '../../api/decisionApi';
import type { FieldOption } from '../ConditionBuilder';

const FIELDS: FieldOption[] = [
  {
    scope: 'record',
    path: 'data.priority',
    label: '优先级',
    dataType: 'enum',
    options: ['HIGH', 'LOW'],
  },
];

const POLICY: EventPolicySummary = {
  policyCode: 'complaint_form_submit_policy',
  policyName: '投诉表单提交策略',
  eventType: 'FORM_SUBMITTED',
  targetType: 'FORM',
  targetKey: 'complaint_form',
  phase: 'AFTER_COMMIT',
  matchMode: 'COLLECT_ALL',
  status: 'DRAFT',
  version: 1,
  latestVersionPid: 'policy-version-pid-1',
  enabled: true,
};

function api(): DecisionApi {
  return {
    listPolicyVersions: vi.fn(async () => []),
    createPolicyDraftVersion: vi.fn(async () => ({
      pid: 'draft-pid-1',
      status: 'DRAFT',
      version: 2,
    })),
    validatePolicyVersion: vi.fn(async () => ({
      pid: 'draft-pid-1',
      status: 'VALIDATED',
      version: 2,
    })),
    publishPolicyVersion: vi.fn(async () => ({
      pid: 'draft-pid-1',
      status: 'PUBLISHED',
      version: 2,
    })),
    runPolicy: vi.fn(async () => ({ status: 'MATCHED', matchedRuleCodes: ['R-1'] })),
    runAndExecutePolicy: vi.fn(async () => ({
      policy: { status: 'MATCHED', matchedRuleCodes: ['R-1'] },
      execution: {
        policyCode: 'complaint_form_submit_policy',
        overallStatus: 'ALL_SUCCESS',
        actions: [
          {
            ruleCode: 'R-1',
            type: 'NOTIFY',
            idempotencyKey: 'complaint:001:R-1:NOTIFY',
            status: 'SUCCESS',
            resultPayload: {
              channel: 'in_app',
              recipientType: 'ROLE',
              recipientId: 'support_manager',
              sentCount: 1,
              recipientCount: 1,
              targetUserIds: [1001],
            },
          },
        ],
      },
    })),
    getActionCatalog: vi.fn(async () => ({
      actions: [
        {
          actionType: 'NOTIFY',
          label: 'Send notification',
          handlerAvailable: true,
          inputSchema: {},
        },
        {
          actionType: 'START_PROCESS',
          label: 'Start BPM process',
          handlerAvailable: true,
          inputSchema: {},
        },
        {
          actionType: 'ADD_COMMENT',
          label: 'Add comment',
          handlerAvailable: true,
          inputSchema: {},
        },
        {
          actionType: 'UPDATE_RECORD',
          label: 'Update record',
          handlerAvailable: true,
          inputSchema: {},
        },
        {
          actionType: 'PATCH_RECORD',
          label: 'Patch record',
          handlerAvailable: true,
          inputSchema: {},
        },
        { actionType: 'WEBHOOK', label: 'Webhook', handlerAvailable: true, inputSchema: {} },
        {
          actionType: 'WRITE_AUDIT',
          label: 'Write audit',
          handlerAvailable: true,
          inputSchema: {},
        },
      ],
    })),
  } as unknown as DecisionApi;
}

async function findActionExecutionEvidence() {
  const executionResults = await screen.findByTestId('epd-action-execution-results');
  const payload = await screen.findByTestId('epd-action-result-payload-0');
  return { executionResults, payload };
}

function apiWithActionCatalog(): DecisionApi {
  return {
    ...api(),
    getActionCatalog: vi.fn(async () => ({
      actions: [
        {
          actionType: 'NOTIFY',
          label: 'Send notification',
          handlerAvailable: true,
          inputSchema: {},
        },
        {
          actionType: 'SEND_SMS',
          label: 'Send SMS',
          consumerTypes: ['SLA', 'EVENT_POLICY', 'AUTOMATION'],
          consumerAvailability: [
            {
              consumerType: 'SLA',
              handlerAvailable: true,
              availabilityStatus: 'AVAILABLE',
            },
            {
              consumerType: 'EVENT_POLICY',
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
              consumerType: 'AUTOMATION',
              handlerAvailable: true,
              availabilityStatus: 'AVAILABLE',
            },
          ],
          inputSchema: {},
        },
        { actionType: 'SEND_IM', label: 'Send IM', handlerAvailable: false, inputSchema: {} },
        {
          actionType: 'PATCH_RECORD',
          label: 'Patch record',
          handlerAvailable: true,
          inputSchema: {},
        },
        {
          actionType: 'WRITE_AUDIT',
          label: 'Write audit',
          handlerAvailable: true,
          inputSchema: {},
        },
        {
          actionType: 'CREATE_TASK',
          label: 'Create task',
          handlerAvailable: false,
          inputSchema: {},
        },
        { actionType: 'CC_TASK', label: 'CC task', handlerAvailable: false, inputSchema: {} },
      ],
    })),
  } as unknown as DecisionApi;
}

describe('EventPolicyDesignerWorkflow', () => {
  it('renders the six-step workflow with selected policy trigger context', () => {
    render(<EventPolicyDesignerWorkflow api={api()} fields={FIELDS} selectedPolicy={POLICY} />);

    expect(screen.getByTestId('epd-workflow')).toBeInTheDocument();
    expect(screen.getByTestId('epd-step-trigger')).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByTestId('epd-trigger-context')).toHaveTextContent('投诉表单提交策略');
    expect(screen.getByTestId('epd-trigger-context')).toHaveTextContent('FORM_SUBMITTED');
    expect(screen.getByTestId('epd-trigger-context')).toHaveTextContent('草稿');
    expect(screen.getByTestId('epd-trigger-context')).not.toHaveTextContent('DRAFT');
    expect(screen.getByTestId('epd-step-rules')).toBeInTheDocument();
    expect(screen.getByTestId('epd-step-actions')).toBeInTheDocument();
    expect(screen.getByTestId('epd-step-test')).toBeInTheDocument();
    expect(screen.getByTestId('epd-step-publish')).toBeInTheDocument();
    expect(screen.getByTestId('epd-step-history')).toBeInTheDocument();
  });

  it('edits rules and actions as one policy draft model', () => {
    render(<EventPolicyDesignerWorkflow api={api()} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-rules'));
    expect(screen.getByTestId('policy-rules-editor')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('rule-name-0'), { target: { value: '高优先级通知' } });

    fireEvent.click(screen.getByTestId('epd-step-actions'));
    fireEvent.click(screen.getByTestId('epd-add-action'));
    fireEvent.change(screen.getByLabelText('action-type-0'), { target: { value: 'NOTIFY' } });
    fireEvent.change(screen.getByLabelText('action-target-0'), {
      target: { value: 'ROLE:support_manager' },
    });

    const draft = JSON.parse(screen.getByTestId('epd-draft-json').textContent || '{}');
    expect(draft.rules[0].ruleName).toBe('高优先级通知');
    expect(draft.rules[0].actions[0]).toMatchObject({
      type: 'NOTIFY',
      target: 'ROLE:support_manager',
    });
  });

  it('loads runtime action catalog and exposes productized side-effect action types', async () => {
    const fakeApi = apiWithActionCatalog();
    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-actions'));
    fireEvent.click(screen.getByTestId('epd-add-action'));

    await waitFor(() => expect(fakeApi.getActionCatalog).toHaveBeenCalledOnce());
    const select = screen.getByLabelText('action-type-0') as HTMLSelectElement;
    const options = Array.from(select.options).map((option) => option.value);
    const labels = Array.from(select.options).map((option) => option.textContent);
    expect(options).toContain('PATCH_RECORD');
    expect(options).toContain('WRITE_AUDIT');
    expect(options).toContain('SEND_SMS');
    expect(options).toContain('SEND_IM');
    expect(options).toContain('CREATE_TASK');
    expect(options).toContain('CC_TASK');
    expect(labels).toContain('发送站内通知');
    expect(labels).toContain('发送短信（不可用）');
    expect(labels).toContain('发送 IM 消息（不可用）');
    expect(labels).toContain('创建任务（不可用）');
    expect(labels).toContain('抄送任务（不可用）');
    expect(labels).toContain('写入审计');
    expect(labels).not.toContain('NOTIFY');

    fireEvent.change(select, { target: { value: 'SEND_SMS' } });
    expect(screen.getByTestId('epd-action-availability-0')).toHaveTextContent(
      '当前环境未配置真实短信 provider',
    );
    expect(screen.getByTestId('epd-action-provider-0')).toHaveTextContent(
      '依赖：真实短信 provider · 未配置',
    );
  });

  it('uses action catalog schemas to edit action payload fields before saving a draft', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.getActionCatalog).mockResolvedValue({
      actions: [
        {
          actionType: 'NOTIFY',
          label: '发送站内通知',
          handlerAvailable: true,
          inputSchema: {
            required: ['target', 'payload.title', 'payload.content'],
            fields: {
              target: { dataType: 'string', label: '通知接收人', required: true },
              'payload.title': { dataType: 'string', label: '通知标题', required: true },
              'payload.content': { dataType: 'text', label: '通知内容', required: true },
            },
          },
        },
      ],
    });

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-actions'));
    await waitFor(() => expect(fakeApi.getActionCatalog).toHaveBeenCalledOnce());
    fireEvent.click(screen.getByTestId('epd-add-action'));

    await waitFor(() => expect(screen.getByLabelText('action-target-0')).toBeInTheDocument());
    fireEvent.change(screen.getByLabelText('action-target-0'), {
      target: { value: 'ROLE:support_manager' },
    });
    fireEvent.change(screen.getByLabelText('action-field-0-payload.title'), {
      target: { value: '高优先级投诉提醒' },
    });
    fireEvent.change(screen.getByLabelText('action-field-0-payload.content'), {
      target: { value: '请主管在 30 分钟内处理' },
    });

    fireEvent.click(screen.getByTestId('epd-step-publish'));
    fireEvent.click(screen.getByTestId('epd-save-draft'));
    await waitFor(() => expect(fakeApi.createPolicyDraftVersion).toHaveBeenCalled());

    expect(fakeApi.createPolicyDraftVersion).toHaveBeenCalledWith(
      'complaint_form_submit_policy',
      expect.objectContaining({
        rulesJson: [
          expect.objectContaining({
            actions: [
              expect.objectContaining({
                type: 'NOTIFY',
                target: 'ROLE:support_manager',
                payload: {
                  title: '高优先级投诉提醒',
                  content: '请主管在 30 分钟内处理',
                },
              }),
            ],
          }),
        ],
      }),
    );
  });

  it('hydrates rule decision binding from policy versions and preserves it when saving drafts', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.listPolicyVersions).mockResolvedValue([
      {
        pid: 'policy-version-pid-1',
        policyCode: 'complaint_form_submit_policy',
        version: 1,
        status: 'PUBLISHED',
        phase: 'AFTER_COMMIT',
        matchMode: 'COLLECT_ALL',
        rulesJson: [
          {
            ruleCode: 'notify_long_leave',
            ruleName: '长假通知',
            priority: 10,
            enabled: true,
            decisionBinding: {
              decisionCode: 'leave_request_automation',
              versionPolicy: 'LATEST_PUBLISHED',
              inputMappings: [
                {
                  input: 'leaveDays',
                  source: { kind: 'FIELD', scope: 'record', path: 'data.wd_req_days' },
                },
              ],
              fallbackPolicy: { mode: 'FAIL_CLOSED' },
              traceMode: 'ALWAYS',
              enabled: true,
            },
            actions: [
              {
                type: 'NOTIFY',
                target: 'ROLE:wd_manager',
                order: 10,
                payload: { title: '长假申请提醒' },
              },
            ],
          },
        ],
      },
    ]);

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    await waitFor(() =>
      expect(fakeApi.listPolicyVersions).toHaveBeenCalledWith('complaint_form_submit_policy'),
    );
    fireEvent.click(screen.getByTestId('epd-step-rules'));
    expect(screen.getByTestId('epd-rule-binding-0')).toHaveTextContent('leave_request_automation');
    expect(screen.getByTestId('epd-rule-binding-0')).toHaveTextContent('leaveDays');

    fireEvent.click(screen.getByTestId('epd-step-publish'));
    fireEvent.click(screen.getByTestId('epd-save-draft'));
    await waitFor(() => expect(fakeApi.createPolicyDraftVersion).toHaveBeenCalled());

    expect(fakeApi.createPolicyDraftVersion).toHaveBeenCalledWith(
      'complaint_form_submit_policy',
      expect.objectContaining({
        rulesJson: [
          expect.objectContaining({
            ruleCode: 'notify_long_leave',
            decisionBinding: expect.objectContaining({
              decisionCode: 'leave_request_automation',
              versionPolicy: 'LATEST_PUBLISHED',
              inputMappings: [
                expect.objectContaining({
                  input: 'leaveDays',
                  source: expect.objectContaining({ path: 'data.wd_req_days' }),
                }),
              ],
            }),
          }),
        ],
      }),
    );
  });

  it('creates, validates, and publishes an EventPolicy version from the publish step', async () => {
    const fakeApi = api();
    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-actions'));
    fireEvent.click(screen.getByTestId('epd-add-action'));
    fireEvent.click(screen.getByTestId('epd-step-publish'));
    fireEvent.click(screen.getByTestId('epd-save-draft'));
    await waitFor(() => expect(fakeApi.createPolicyDraftVersion).toHaveBeenCalled());
    fireEvent.click(screen.getByTestId('epd-validate-version'));
    await waitFor(() => expect(fakeApi.validatePolicyVersion).toHaveBeenCalledWith('draft-pid-1'));
    fireEvent.click(screen.getByTestId('epd-publish-version'));
    await waitFor(() => expect(fakeApi.publishPolicyVersion).toHaveBeenCalledWith('draft-pid-1'));

    expect(fakeApi.createPolicyDraftVersion).toHaveBeenCalledWith(
      'complaint_form_submit_policy',
      expect.objectContaining({
        phase: 'AFTER_COMMIT',
        matchMode: 'COLLECT_ALL',
        rulesJson: expect.arrayContaining([
          expect.objectContaining({
            ruleCode: 'R-1',
            actions: expect.arrayContaining([expect.objectContaining({ type: 'NOTIFY' })]),
          }),
        ]),
      }),
    );
    expect(screen.getByTestId('epd-publish-status')).toHaveTextContent('已发布');
    expect(screen.getByTestId('epd-publish-status')).not.toHaveTextContent('PUBLISHED');
  });

  it('runs the published policy through action execution and shows per-action evidence', async () => {
    const fakeApi = api();
    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-test'));
    fireEvent.click(screen.getByTestId('epd-run-published'));

    await waitFor(() =>
      expect(fakeApi.runAndExecutePolicy).toHaveBeenCalledWith({
        eventType: 'FORM_SUBMITTED',
        targetType: 'FORM',
        targetKey: 'complaint_form',
        context: { record: { data: {} } },
      }),
    );
    expect(fakeApi.runPolicy).not.toHaveBeenCalled();
    expect(screen.getByTestId('epd-run-result')).toHaveTextContent('已命中');
    const { executionResults, payload } = await findActionExecutionEvidence();
    expect(executionResults).toHaveTextContent('全部成功');
    expect(executionResults).toHaveTextContent('发送站内通知');
    expect(executionResults).toHaveTextContent('成功');
    expect(executionResults).toHaveTextContent('幂等键 已记录');
    expect(executionResults).not.toHaveTextContent('complaint:001:R-1:NOTIFY');
    const actionRow = await screen.findByTestId('epd-action-execution-0');
    expect(actionRow.querySelector('[title]')).toHaveAttribute(
      'title',
      'complaint:001:R-1:NOTIFY',
    );
    expect(payload).toHaveTextContent('通道');
    expect(payload).toHaveTextContent('in_app');
    expect(payload).toHaveTextContent('接收对象');
    expect(payload).toHaveTextContent('support_manager');
    expect(payload).toHaveTextContent('发送数');
    expect(payload).toHaveTextContent('1');
    expect(payload).toHaveTextContent('接收人数');
    expect(payload).toHaveTextContent('接收用户');
    expect(payload).toHaveTextContent('1001');
    expect(payload).not.toHaveTextContent('targetUserIds');
  });

  it('shows webhook delivery trace evidence without raw payload field names', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.runAndExecutePolicy).mockResolvedValue({
      policy: { status: 'MATCHED', matchedRuleCodes: ['R-WEBHOOK'] },
      execution: {
        policyCode: 'complaint_form_submit_policy',
        overallStatus: 'ALL_SUCCESS',
        actions: [
          {
            ruleCode: 'R-WEBHOOK',
            type: 'WEBHOOK',
            idempotencyKey: 'complaint:001:R-WEBHOOK:WEBHOOK',
            status: 'SUCCESS',
            resultPayload: {
              eventType: 'complaint.escalated',
              dispatchAccepted: true,
              deliveryEventId: 'ep-webhook-evt-1',
              deliveryTraceStatus: 'tracked_delivery_logs',
              deliveryLogPids: ['delivery-log-1'],
              deliveryReceipts: [
                {
                  subscriptionPid: 'sub-1',
                  deliveryLogPid: 'delivery-log-1',
                  eventId: 'ep-webhook-evt-1',
                  deliveryStatus: 'failed',
                },
              ],
              payloadKeys: ['caseId'],
            },
          },
        ],
      },
    });

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-test'));
    fireEvent.click(screen.getByTestId('epd-run-published'));

    await waitFor(() => expect(fakeApi.runAndExecutePolicy).toHaveBeenCalledOnce());
    const { executionResults, payload } = await findActionExecutionEvidence();
    expect(executionResults).toHaveTextContent('调用 Webhook');
    expect(payload).toHaveTextContent('投递追踪');
    expect(payload).toHaveTextContent('ep-webhook-evt-1');
    expect(payload).toHaveTextContent('投递状态');
    expect(payload).toHaveTextContent('已记录投递日志');
    expect(payload).toHaveTextContent('投递日志');
    expect(payload).toHaveTextContent('delivery-log-1');
    expect(payload).toHaveTextContent('投递回执');
    expect(payload).toHaveTextContent('sub-1 / delivery-log-1 / failed');
    expect(payload).not.toHaveTextContent('deliveryEventId');
    expect(payload).not.toHaveTextContent('deliveryLogPids');
    expect(payload).not.toHaveTextContent('tracked_delivery_logs');
  });

  it('shows webhook dispatch failure payload without raw payload field names', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.runAndExecutePolicy).mockResolvedValue({
      policy: { status: 'MATCHED', matchedRuleCodes: ['R-WEBHOOK'] },
      execution: {
        policyCode: 'complaint_form_submit_policy',
        overallStatus: 'FAILED',
        actions: [
          {
            ruleCode: 'R-WEBHOOK',
            type: 'WEBHOOK',
            idempotencyKey: 'complaint:001:R-WEBHOOK:WEBHOOK',
            status: 'FAILED',
            error: 'WEBHOOK dispatch failed: dispatcher down',
            resultPayload: {
              eventType: 'complaint.escalated',
              dispatchAccepted: false,
              deliveryEventId: 'ep-webhook-evt-1',
              deliveryTraceStatus: 'dispatch_failed',
              failureReason: 'webhook_dispatch_failed',
              errorMessage: 'dispatcher down',
              recordPid: 'CMP-1',
              payloadKeys: ['recordPid', 'caseId'],
            },
          },
        ],
      },
    });

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-test'));
    fireEvent.click(screen.getByTestId('epd-run-published'));

    await waitFor(() => expect(fakeApi.runAndExecutePolicy).toHaveBeenCalledOnce());
    const { executionResults, payload } = await findActionExecutionEvidence();
    expect(executionResults).toHaveTextContent('调用 Webhook');
    expect(executionResults).toHaveTextContent('失败');
    expect(payload).toHaveTextContent('投递追踪');
    expect(payload).toHaveTextContent('ep-webhook-evt-1');
    expect(payload).toHaveTextContent('投递状态');
    expect(payload).toHaveTextContent('投递失败');
    expect(payload).toHaveTextContent('失败原因');
    expect(payload).toHaveTextContent('Webhook 投递失败');
    expect(payload).toHaveTextContent('错误信息');
    expect(payload).toHaveTextContent('dispatcher down');
    expect(payload).toHaveTextContent('业务记录');
    expect(payload).toHaveTextContent('CMP-1');
    expect(payload).not.toHaveTextContent('deliveryTraceStatus');
    expect(payload).not.toHaveTextContent('dispatch_failed');
    expect(payload).not.toHaveTextContent('failureReason');
    expect(payload).not.toHaveTextContent('webhook_dispatch_failed');
    expect(payload).not.toHaveTextContent('errorMessage');
    expect(payload).not.toHaveTextContent('payloadKeys');
  });

  it('shows update-record context failure payload without raw payload field names', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.runAndExecutePolicy).mockResolvedValue({
      policy: { status: 'MATCHED', matchedRuleCodes: ['update_status'] },
      execution: {
        policyCode: 'complaint_form_submit_policy',
        overallStatus: 'FAILED',
        actions: [
          {
            ruleCode: 'update_status',
            type: 'UPDATE_RECORD',
            idempotencyKey: 'complaint:001:update_status:UPDATE_RECORD',
            status: 'FAILED',
            error: 'UPDATE_RECORD requires record.entityCode + record.recordPid in the context',
            resultPayload: {
              failureReason: 'record_context_missing',
              requiredContext: ['record.entityCode', 'record.recordPid'],
              actionType: 'UPDATE_RECORD',
            },
          },
        ],
      },
    });

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-test'));
    fireEvent.click(screen.getByTestId('epd-run-published'));

    await waitFor(() => expect(fakeApi.runAndExecutePolicy).toHaveBeenCalledOnce());
    const { executionResults, payload } = await findActionExecutionEvidence();
    expect(executionResults).toHaveTextContent('更新记录');
    expect(executionResults).toHaveTextContent('失败');
    expect(payload).toHaveTextContent('失败原因');
    expect(payload).toHaveTextContent('缺少业务记录上下文');
    expect(payload).toHaveTextContent('必需上下文');
    expect(payload).toHaveTextContent('记录模型, 业务记录');
    expect(payload).toHaveTextContent('动作类型');
    expect(payload).toHaveTextContent('更新记录');
    expect(payload).not.toHaveTextContent('failureReason');
    expect(payload).not.toHaveTextContent('record_context_missing');
    expect(payload).not.toHaveTextContent('requiredContext');
    expect(payload).not.toHaveTextContent('record.entityCode');
    expect(payload).not.toHaveTextContent('record.recordPid');
    expect(payload).not.toHaveTextContent('UPDATE_RECORD');
  });

  it('shows add-comment content failure payload without raw payload field names', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.runAndExecutePolicy).mockResolvedValue({
      policy: { status: 'MATCHED', matchedRuleCodes: ['add_missing_comment'] },
      execution: {
        policyCode: 'complaint_form_submit_policy',
        overallStatus: 'FAILED',
        actions: [
          {
            ruleCode: 'add_missing_comment',
            type: 'ADD_COMMENT',
            idempotencyKey: 'complaint:001:add_missing_comment:ADD_COMMENT',
            status: 'FAILED',
            error: 'ADD_COMMENT requires a non-empty payload.content',
            resultPayload: {
              failureReason: 'comment_content_missing',
              modelCode: 'complaint',
              recordPid: 'CMP-1',
              field: 'payload.content',
              actionType: 'ADD_COMMENT',
            },
          },
        ],
      },
    });

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-test'));
    fireEvent.click(screen.getByTestId('epd-run-published'));

    await waitFor(() => expect(fakeApi.runAndExecutePolicy).toHaveBeenCalledOnce());
    const { executionResults, payload } = await findActionExecutionEvidence();
    expect(executionResults).toHaveTextContent('添加评论');
    expect(executionResults).toHaveTextContent('失败');
    expect(payload).toHaveTextContent('失败原因');
    expect(payload).toHaveTextContent('缺少评论内容');
    expect(payload).toHaveTextContent('模型');
    expect(payload).toHaveTextContent('complaint');
    expect(payload).toHaveTextContent('业务记录');
    expect(payload).toHaveTextContent('CMP-1');
    expect(payload).toHaveTextContent('字段');
    expect(payload).toHaveTextContent('评论内容');
    expect(payload).toHaveTextContent('动作类型');
    expect(payload).toHaveTextContent('添加评论');
    expect(payload).not.toHaveTextContent('failureReason');
    expect(payload).not.toHaveTextContent('comment_content_missing');
    expect(payload).not.toHaveTextContent('payload.content');
    expect(payload).not.toHaveTextContent('actionType');
    expect(payload).not.toHaveTextContent('ADD_COMMENT');
  });

  it('shows write-audit tenant failure payload without raw payload field names', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.runAndExecutePolicy).mockResolvedValue({
      policy: { status: 'MATCHED', matchedRuleCodes: ['audit_missing_tenant'] },
      execution: {
        policyCode: 'complaint_form_submit_policy',
        overallStatus: 'FAILED',
        actions: [
          {
            ruleCode: 'audit_missing_tenant',
            type: 'WRITE_AUDIT',
            idempotencyKey: 'complaint:001:audit_missing_tenant:WRITE_AUDIT',
            status: 'FAILED',
            error: 'Tenant context required for WRITE_AUDIT action',
            resultPayload: {
              failureReason: 'audit_tenant_missing',
              requiredContext: ['tenantId'],
              ruleCode: 'audit_missing_tenant',
              actionType: 'WRITE_AUDIT',
            },
          },
        ],
      },
    });

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-test'));
    fireEvent.click(screen.getByTestId('epd-run-published'));

    await waitFor(() => expect(fakeApi.runAndExecutePolicy).toHaveBeenCalledOnce());
    const { executionResults, payload } = await findActionExecutionEvidence();
    expect(executionResults).toHaveTextContent('写入审计');
    expect(executionResults).toHaveTextContent('失败');
    expect(payload).toHaveTextContent('失败原因');
    expect(payload).toHaveTextContent('缺少租户上下文');
    expect(payload).toHaveTextContent('必需上下文');
    expect(payload).toHaveTextContent('租户');
    expect(payload).toHaveTextContent('规则');
    expect(payload).toHaveTextContent('audit_missing_tenant');
    expect(payload).toHaveTextContent('动作类型');
    expect(payload).toHaveTextContent('写入审计');
    expect(payload).not.toHaveTextContent('failureReason');
    expect(payload).not.toHaveTextContent('audit_tenant_missing');
    expect(payload).not.toHaveTextContent('requiredContext');
    expect(payload).not.toHaveTextContent('tenantId');
    expect(payload).not.toHaveTextContent('WRITE_AUDIT');
  });

  it('shows target-resolution failure payload without raw payload field names', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.runAndExecutePolicy).mockResolvedValue({
      policy: { status: 'MATCHED', matchedRuleCodes: ['cc_empty_role'] },
      execution: {
        policyCode: 'complaint_form_submit_policy',
        overallStatus: 'FAILED',
        actions: [
          {
            ruleCode: 'cc_empty_role',
            type: 'CC_TASK',
            idempotencyKey: 'complaint:001:cc_empty_role:CC_TASK',
            status: 'FAILED',
            error: 'CC_TASK target resolved no users: ROLE:empty_role',
            resultPayload: {
              itemType: 'mention',
              delivery: 'inbox',
              failureReason: 'target_resolved_no_users',
              targetType: 'ROLE',
              target: 'ROLE:empty_role',
              resolvedCount: 0,
            },
          },
        ],
      },
    });

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-test'));
    fireEvent.click(screen.getByTestId('epd-run-published'));

    await waitFor(() => expect(fakeApi.runAndExecutePolicy).toHaveBeenCalledOnce());
    const { executionResults, payload } = await findActionExecutionEvidence();
    expect(executionResults).toHaveTextContent('失败');
    expect(executionResults).toHaveTextContent('CC_TASK target resolved no users');
    expect(payload).toHaveTextContent('待办类型');
    expect(payload).toHaveTextContent('抄送任务');
    expect(payload).toHaveTextContent('投递方式');
    expect(payload).toHaveTextContent('待办');
    expect(payload).toHaveTextContent('失败原因');
    expect(payload).toHaveTextContent('目标未匹配到用户');
    expect(payload).toHaveTextContent('接收类型');
    expect(payload).toHaveTextContent('角色');
    expect(payload).toHaveTextContent('接收对象');
    expect(payload).toHaveTextContent('ROLE:empty_role');
    expect(payload).toHaveTextContent('解析人数');
    expect(payload).toHaveTextContent('0');
    expect(payload).not.toHaveTextContent('failureReason');
    expect(payload).not.toHaveTextContent('target_resolved_no_users');
    expect(payload).not.toHaveTextContent('resolvedCount');
  });

  it('shows action configuration failure payload with productized labels', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.runAndExecutePolicy).mockResolvedValue({
      policy: { status: 'MATCHED', matchedRuleCodes: ['sms_missing_content'] },
      execution: {
        policyCode: 'complaint_form_submit_policy',
        overallStatus: 'FAILED',
        actions: [
          {
            ruleCode: 'sms_missing_content',
            type: 'SEND_SMS',
            idempotencyKey: 'complaint:001:sms_missing_content:SEND_SMS',
            status: 'FAILED',
            error: 'SEND_SMS requires payload.content',
            resultPayload: {
              channel: 'sms',
              failureReason: 'payload_content_missing',
              field: 'payload.content',
              target: 'PHONE:+8613800138000',
              actionType: 'SEND_SMS',
            },
          },
        ],
      },
    });

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-test'));
    fireEvent.click(screen.getByTestId('epd-run-published'));

    await waitFor(() => expect(fakeApi.runAndExecutePolicy).toHaveBeenCalledOnce());
    const { executionResults, payload } = await findActionExecutionEvidence();
    expect(executionResults).toHaveTextContent('发送短信');
    expect(payload).toHaveTextContent('失败原因');
    expect(payload).toHaveTextContent('缺少消息内容');
    expect(payload).toHaveTextContent('字段');
    expect(payload).toHaveTextContent('评论内容');
    expect(payload).toHaveTextContent('动作类型');
    expect(payload).toHaveTextContent('发送短信');
    expect(payload).not.toHaveTextContent('payload_content_missing');
    expect(payload).not.toHaveTextContent('failureReason');
    expect(payload).not.toHaveTextContent('SEND_SMS');
  });

  it('shows START_PROCESS result payload with productized workflow labels', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.runAndExecutePolicy).mockResolvedValue({
      policy: { status: 'MATCHED', matchedRuleCodes: ['R-SP'] },
      execution: {
        policyCode: 'complaint_form_submit_policy',
        overallStatus: 'ALL_SUCCESS',
        actions: [
          {
            ruleCode: 'R-SP',
            type: 'START_PROCESS',
            idempotencyKey: 'complaint:001:R-SP:START_PROCESS',
            status: 'SUCCESS',
            resultPayload: {
              processDefinitionId: 'approval_flow',
              processInstanceId: '1784160001001',
              businessKey: 'TEST-complaint_form_submit_policy',
              recordPid: 'TEST-complaint_form_submit_policy',
            },
          },
        ],
      },
    });

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-test'));
    fireEvent.click(screen.getByTestId('epd-run-published'));

    await waitFor(() => expect(fakeApi.runAndExecutePolicy).toHaveBeenCalledOnce());
    const { executionResults, payload } = await findActionExecutionEvidence();
    expect(executionResults).toHaveTextContent('启动流程');
    expect(payload).toHaveTextContent('流程标识');
    expect(payload).toHaveTextContent('approval_flow');
    expect(payload).toHaveTextContent('流程实例');
    expect(payload).toHaveTextContent('1784160001001');
    expect(payload).toHaveTextContent('业务主键');
    expect(payload).toHaveTextContent('TEST-complaint_form_submit_policy');
    expect(payload).toHaveTextContent('业务记录');
    expect(payload).not.toHaveTextContent('processDefinitionId');
    expect(payload).not.toHaveTextContent('processInstanceId');
    expect(payload).not.toHaveTextContent('businessKey');
  });

  it('shows START_PROCESS failure payload with productized workflow labels', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.runAndExecutePolicy).mockResolvedValue({
      policy: { status: 'MATCHED', matchedRuleCodes: ['R-SP'] },
      execution: {
        policyCode: 'complaint_form_submit_policy',
        overallStatus: 'FAILED',
        actions: [
          {
            ruleCode: 'R-SP',
            type: 'START_PROCESS',
            idempotencyKey: 'complaint:001:R-SP:START_PROCESS',
            status: 'FAILED',
            error: '流程启动失败：流程未部署或流程标识不存在',
            resultPayload: {
              failureReason: 'process_start_failed',
              processDefinitionId: 'missing_approval_flow',
              businessKey: 'TEST-complaint_form_submit_policy',
              recordPid: 'TEST-complaint_form_submit_policy',
            },
          },
        ],
      },
    });

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-test'));
    fireEvent.click(screen.getByTestId('epd-run-published'));

    await waitFor(() => expect(fakeApi.runAndExecutePolicy).toHaveBeenCalledOnce());
    const actionRow = await screen.findByTestId('epd-action-execution-0');
    const { payload } = await findActionExecutionEvidence();
    expect(actionRow).toHaveTextContent('启动流程');
    expect(actionRow).toHaveTextContent('失败');
    expect(actionRow).toHaveTextContent('流程启动失败：流程未部署或流程标识不存在');
    expect(payload).toHaveTextContent('失败原因');
    expect(payload).toHaveTextContent('流程启动失败');
    expect(payload).toHaveTextContent('流程标识');
    expect(payload).toHaveTextContent('missing_approval_flow');
    expect(payload).toHaveTextContent('业务主键');
    expect(payload).toHaveTextContent('TEST-complaint_form_submit_policy');
    expect(payload).toHaveTextContent('业务记录');
    expect(payload).not.toHaveTextContent('failureReason');
    expect(payload).not.toHaveTextContent('process_start_failed');
    expect(payload).not.toHaveTextContent('processDefinitionId');
    expect(payload).not.toHaveTextContent('businessKey');
    expect(payload).not.toHaveTextContent('recordPid');
  });

  it('shows START_PROCESS missing configuration failure without raw payload field names', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.runAndExecutePolicy).mockResolvedValue({
      policy: { status: 'MATCHED', matchedRuleCodes: ['R-SP'] },
      execution: {
        policyCode: 'complaint_form_submit_policy',
        overallStatus: 'FAILED',
        actions: [
          {
            ruleCode: 'R-SP',
            type: 'START_PROCESS',
            idempotencyKey: 'complaint:001:R-SP:START_PROCESS',
            status: 'FAILED',
            error: '缺少流程标识，无法启动流程',
            resultPayload: {
              failureReason: 'process_definition_missing',
              field: 'payload.processDefinitionId',
              recordPid: 'TEST-complaint_form_submit_policy',
            },
          },
        ],
      },
    });

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-test'));
    fireEvent.click(screen.getByTestId('epd-run-published'));

    await waitFor(() => expect(fakeApi.runAndExecutePolicy).toHaveBeenCalledOnce());
    const actionRow = await screen.findByTestId('epd-action-execution-0');
    const { payload } = await findActionExecutionEvidence();
    expect(actionRow).toHaveTextContent('启动流程');
    expect(actionRow).toHaveTextContent('失败');
    expect(actionRow).toHaveTextContent('缺少流程标识，无法启动流程');
    expect(payload).toHaveTextContent('失败原因');
    expect(payload).toHaveTextContent('缺少流程标识');
    expect(payload).toHaveTextContent('字段');
    expect(payload).toHaveTextContent('流程标识');
    expect(payload).toHaveTextContent('业务记录');
    expect(payload).toHaveTextContent('TEST-complaint_form_submit_policy');
    expect(payload).not.toHaveTextContent('failureReason');
    expect(payload).not.toHaveTextContent('process_definition_missing');
    expect(payload).not.toHaveTextContent('payload.processDefinitionId');
    expect(payload).not.toHaveTextContent('recordPid');
  });

  it('hydrates the selected policy latest version before saving a new draft', async () => {
    const fakeApi = api();
    vi.mocked(fakeApi.listPolicyVersions).mockResolvedValue([
      {
        pid: 'policy-version-pid-1',
        policyCode: 'complaint_form_submit_policy',
        version: 1,
        status: 'PUBLISHED',
        phase: 'ASYNC_WORKER',
        matchMode: 'PRIORITY_FIRST',
        executionMode: 'UNORDERED',
        failureStrategy: 'CONTINUE_ON_ERROR',
        conflictStrategy: 'PRIORITY_WINS',
        dedupStrategy: 'BY_ACTION_TYPE_AND_TARGET',
        rulesJson: [
          {
            ruleCode: 'VIP',
            ruleName: 'VIP escalation',
            priority: 10,
            enabled: true,
            condition: { op: 'AND', children: [] },
            actions: [
              {
                type: 'PATCH_RECORD',
                target: '',
                order: 1,
                payload: { fields: { severity: 'high' } },
                idempotencyKeyTemplate: '${record.recordPid}:vip',
              },
            ],
          },
        ],
      },
    ]);

    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    await waitFor(() =>
      expect(fakeApi.listPolicyVersions).toHaveBeenCalledWith('complaint_form_submit_policy'),
    );
    fireEvent.click(screen.getByTestId('epd-step-actions'));
    expect(screen.getByLabelText('action-type-0')).toHaveValue('PATCH_RECORD');

    fireEvent.click(screen.getByTestId('epd-step-publish'));
    fireEvent.click(screen.getByTestId('epd-save-draft'));
    await waitFor(() => expect(fakeApi.createPolicyDraftVersion).toHaveBeenCalled());

    expect(fakeApi.createPolicyDraftVersion).toHaveBeenCalledWith(
      'complaint_form_submit_policy',
      expect.objectContaining({
        phase: 'ASYNC_WORKER',
        matchMode: 'PRIORITY_FIRST',
        executionMode: 'UNORDERED',
        failureStrategy: 'CONTINUE_ON_ERROR',
        conflictStrategy: 'PRIORITY_WINS',
        dedupStrategy: 'BY_ACTION_TYPE_AND_TARGET',
        rulesJson: [
          expect.objectContaining({
            ruleCode: 'VIP',
            ruleName: 'VIP escalation',
            actions: [
              expect.objectContaining({
                type: 'PATCH_RECORD',
                target: '',
                payload: { fields: { severity: 'high' } },
              }),
            ],
          }),
        ],
      }),
    );
  });
});
