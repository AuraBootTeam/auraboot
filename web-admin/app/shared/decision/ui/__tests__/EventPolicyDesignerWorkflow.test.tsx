import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { EventPolicyDesignerWorkflow } from '../EventPolicyDesignerWorkflow';
import type { DecisionApi, EventPolicySummary } from '../../api/decisionApi';
import type { FieldOption } from '../ConditionBuilder';

const FIELDS: FieldOption[] = [
  { scope: 'record', path: 'data.priority', label: '优先级', dataType: 'enum', options: ['HIGH', 'LOW'] },
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
    createPolicyDraftVersion: vi.fn(async () => ({ pid: 'draft-pid-1', status: 'DRAFT', version: 2 })),
    validatePolicyVersion: vi.fn(async () => ({ pid: 'draft-pid-1', status: 'VALIDATED', version: 2 })),
    publishPolicyVersion: vi.fn(async () => ({ pid: 'draft-pid-1', status: 'PUBLISHED', version: 2 })),
    runPolicy: vi.fn(async () => ({ status: 'MATCHED', matchedRuleCodes: ['R-1'] })),
    getActionCatalog: vi.fn(async () => ({
      actions: [
        { actionType: 'NOTIFY', label: 'Send notification', handlerAvailable: true, inputSchema: {} },
        { actionType: 'START_PROCESS', label: 'Start BPM process', handlerAvailable: true, inputSchema: {} },
        { actionType: 'ADD_COMMENT', label: 'Add comment', handlerAvailable: true, inputSchema: {} },
        { actionType: 'UPDATE_RECORD', label: 'Update record', handlerAvailable: true, inputSchema: {} },
        { actionType: 'PATCH_RECORD', label: 'Patch record', handlerAvailable: true, inputSchema: {} },
        { actionType: 'WEBHOOK', label: 'Webhook', handlerAvailable: true, inputSchema: {} },
        { actionType: 'WRITE_AUDIT', label: 'Write audit', handlerAvailable: true, inputSchema: {} },
      ],
    })),
  } as unknown as DecisionApi;
}

function apiWithActionCatalog(): DecisionApi {
  return {
    ...api(),
    getActionCatalog: vi.fn(async () => ({
      actions: [
        { actionType: 'NOTIFY', label: 'Send notification', handlerAvailable: true, inputSchema: {} },
        { actionType: 'PATCH_RECORD', label: 'Patch record', handlerAvailable: true, inputSchema: {} },
        { actionType: 'WRITE_AUDIT', label: 'Write audit', handlerAvailable: true, inputSchema: {} },
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
    fireEvent.change(screen.getByLabelText('action-target-0'), { target: { value: 'ROLE:support_manager' } });

    const draft = JSON.parse(screen.getByTestId('epd-draft-json').textContent || '{}');
    expect(draft.rules[0].ruleName).toBe('高优先级通知');
    expect(draft.rules[0].actions[0]).toMatchObject({
      type: 'NOTIFY',
      target: 'ROLE:support_manager',
    });
  });

  it('loads runtime action catalog and hides unsupported action types', async () => {
    const fakeApi = apiWithActionCatalog();
    render(<EventPolicyDesignerWorkflow api={fakeApi} fields={FIELDS} selectedPolicy={POLICY} />);

    fireEvent.click(screen.getByTestId('epd-step-actions'));
    fireEvent.click(screen.getByTestId('epd-add-action'));

    await waitFor(() => expect(fakeApi.getActionCatalog).toHaveBeenCalledOnce());
    const select = screen.getByLabelText('action-type-0') as HTMLSelectElement;
    const options = Array.from(select.options)
      .map((option) => option.value);
    expect(options).toContain('PATCH_RECORD');
    expect(options).toContain('WRITE_AUDIT');
    expect(options).not.toContain('CREATE_TASK');
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

    expect(fakeApi.createPolicyDraftVersion).toHaveBeenCalledWith('complaint_form_submit_policy', expect.objectContaining({
      phase: 'AFTER_COMMIT',
      matchMode: 'COLLECT_ALL',
      rulesJson: expect.arrayContaining([
        expect.objectContaining({
          ruleCode: 'R-1',
          actions: expect.arrayContaining([expect.objectContaining({ type: 'NOTIFY' })]),
        }),
      ]),
    }));
    expect(screen.getByTestId('epd-publish-status')).toHaveTextContent('PUBLISHED');
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

    await waitFor(() => expect(fakeApi.listPolicyVersions).toHaveBeenCalledWith('complaint_form_submit_policy'));
    fireEvent.click(screen.getByTestId('epd-step-actions'));
    expect(screen.getByLabelText('action-type-0')).toHaveValue('PATCH_RECORD');

    fireEvent.click(screen.getByTestId('epd-step-publish'));
    fireEvent.click(screen.getByTestId('epd-save-draft'));
    await waitFor(() => expect(fakeApi.createPolicyDraftVersion).toHaveBeenCalled());

    expect(fakeApi.createPolicyDraftVersion).toHaveBeenCalledWith('complaint_form_submit_policy', expect.objectContaining({
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
    }));
  });
});
