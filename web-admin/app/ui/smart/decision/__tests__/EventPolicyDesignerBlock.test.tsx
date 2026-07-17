import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EventPolicyDesignerBlock } from '../EventPolicyDesignerBlock';

const get = vi.fn();
const post = vi.fn();

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => ({
    get,
    post,
    delete: vi.fn(),
  }),
}));

describe('EventPolicyDesignerBlock', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    post.mockResolvedValue({
      data: {
        policy: { status: 'MATCHED', matchedRuleCodes: ['notify_long_leave'] },
        execution: { overallStatus: 'ALL_SUCCESS', actions: [] },
      },
    });
    get.mockImplementation((endpoint: string) => {
      if (endpoint === '/event-policy/definitions') {
        return Promise.resolve({
          data: [
            {
              policyCode: 'complaint_policy',
              policyName: 'Complaint Policy',
              eventType: 'FORM_SUBMITTED',
              targetType: 'FORM',
              targetKey: 'complaint',
              status: 'DRAFT',
              latestVersionPid: 'policy-version-pid',
            },
            {
              policyCode: 'leave_request_event_policy',
              policyName: '请假事件动作策略',
              eventType: 'LEAVE_REQUEST_CREATED',
              targetType: 'MODEL',
              targetKey: 'wd_leave_request',
              status: 'PUBLISHED',
              latestVersionPid: 'leave-policy-version-pid',
            },
          ],
        });
      }
      if (endpoint === '/event-policy/definitions/complaint_policy/versions') {
        return Promise.resolve({ data: [] });
      }
      if (endpoint === '/event-policy/definitions/leave_request_event_policy/versions') {
        return Promise.resolve({ data: [] });
      }
      if (endpoint === '/decision/model/fields') {
        return Promise.resolve({
          data: [
            {
              entityCode: 'record',
              modelCode: 'complaint',
              modelName: '投诉',
              path: 'record.data.customerLevel',
              label: '客户等级',
              dataType: 'enum',
            },
            {
              entityCode: 'record',
              modelCode: 'wd_leave_request',
              modelName: '请假申请',
              path: 'record.data.wd_req_days',
              label: '请假天数',
              dataType: 'decimal',
            },
            {
              entityCode: 'record',
              modelCode: 'agent_memory',
              modelName: 'Agent 记忆',
              path: 'record.data.access_count',
              label: '访问次数',
              dataType: 'integer',
            },
          ],
        });
      }
      return Promise.resolve({ data: [] });
    });
  });

  it('loads the selected policy from URL policyCode and renders the workflow', async () => {
    render(
      <MemoryRouter>
        <EventPolicyDesignerBlock block={{ props: { policyCode: 'complaint_policy' } }} />
      </MemoryRouter>,
    );

    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/event-policy/definitions', {
        keyword: 'complaint_policy',
      }),
    );
    await screen.findByTestId('epd-workflow');
    expect(screen.getByTestId('epd-command-center')).toHaveTextContent('策略链路摘要');
    expect(screen.getByTestId('epd-strategy-summary')).toHaveTextContent('Complaint Policy');
    expect(screen.getByTestId('epd-strategy-summary')).toHaveTextContent('FORM_SUBMITTED');
    expect(screen.getByTestId('epd-run-summary')).toHaveTextContent('待运行样例');
    expect(screen.getByTestId('epd-trigger-context')).toHaveTextContent('Complaint Policy');
    expect(screen.getByTestId('epd-trigger-context')).toHaveTextContent('FORM_SUBMITTED');
    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/event-policy/definitions/complaint_policy/versions', undefined),
    );
  });

  it('loads model fields into the rule field picker with default event fields as fallback', async () => {
    render(
      <MemoryRouter>
        <EventPolicyDesignerBlock block={{ props: { policyCode: 'complaint_policy' } }} />
      </MemoryRouter>,
    );

    await waitFor(() => expect(get).toHaveBeenCalledWith('/decision/model/fields', undefined));
    await screen.findByTestId('epd-workflow');
    fireEvent.click(screen.getByTestId('epd-step-rules'));
    fireEvent.click(screen.getByTestId('cb-add'));

    const fieldPicker = screen.getByLabelText('field-0');
    expect(fieldPicker).toHaveTextContent('客户等级');
    expect(fieldPicker).toHaveTextContent('优先级');
    expect(fieldPicker).toHaveTextContent('金额');
  });

  it('filters rule field picker to the selected policy target model', async () => {
    render(
      <MemoryRouter>
        <EventPolicyDesignerBlock block={{ props: { policyCode: 'leave_request_event_policy' } }} />
      </MemoryRouter>,
    );

    await screen.findByTestId('epd-workflow');
    fireEvent.click(screen.getByTestId('epd-step-rules'));
    fireEvent.click(screen.getByTestId('cb-add'));

    const fieldPicker = screen.getByLabelText('field-0');
    expect(fieldPicker).toHaveTextContent('请假天数');
    expect(fieldPicker).not.toHaveTextContent('访问次数');
    expect(fieldPicker).not.toHaveTextContent('客户等级');
  });

  it('prefers unified fact catalog fields for event policy rules when available', async () => {
    get.mockImplementation((endpoint: string, params?: Record<string, unknown>) => {
      if (endpoint === '/event-policy/definitions') {
        return Promise.resolve({
          data: [
            {
              policyCode: 'leave_request_event_policy',
              policyName: '请假事件动作策略',
              eventType: 'LEAVE_REQUEST_CREATED',
              targetType: 'MODEL',
              targetKey: 'wd_leave_request',
              status: 'PUBLISHED',
              latestVersionPid: 'leave-policy-version-pid',
            },
          ],
        });
      }
      if (endpoint === '/event-policy/definitions/leave_request_event_policy/versions') {
        return Promise.resolve({ data: [] });
      }
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

    render(
      <MemoryRouter>
        <EventPolicyDesignerBlock block={{ props: { policyCode: 'leave_request_event_policy' } }} />
      </MemoryRouter>,
    );

    await waitFor(() =>
      expect(get).toHaveBeenCalledWith('/decision/facts/catalog', { modelCode: 'wd_leave_request' }),
    );
    expect(get).not.toHaveBeenCalledWith('/decision/model/fields', undefined);

    await screen.findByTestId('epd-workflow');
    fireEvent.click(screen.getByTestId('epd-step-rules'));
    fireEvent.click(screen.getByTestId('cb-add'));

    const fieldPicker = screen.getByLabelText('field-0');
    expect(fieldPicker).toHaveTextContent('请假类型');
    expect(fieldPicker).not.toHaveTextContent('旧字段目录');

    fireEvent.change(fieldPicker, { target: { value: 'record:data.wd_leave_type' } });
    const valuePicker = screen.getByLabelText('value-0');
    expect(valuePicker).toHaveTextContent('年假');
    expect(valuePicker).toHaveTextContent('病假');
  });

  it('provides a workflow-demo leave request sample so test execution renders stable idempotency keys', async () => {
    render(
      <MemoryRouter>
        <EventPolicyDesignerBlock block={{ props: { policyCode: 'leave_request_event_policy' } }} />
      </MemoryRouter>,
    );

    await screen.findByTestId('epd-workflow');
    fireEvent.click(screen.getByTestId('epd-step-test'));
    expect(screen.getByTestId('condition-testrun')).toHaveTextContent('5天长假申请');
    fireEvent.click(screen.getByTestId('epd-run-published'));

    await waitFor(() => expect(post).toHaveBeenCalledWith('/event-policy/run-and-execute', expect.objectContaining({
      eventType: 'LEAVE_REQUEST_CREATED',
      targetType: 'MODEL',
      targetKey: 'wd_leave_request',
      context: expect.objectContaining({
        record: expect.objectContaining({
          entityCode: 'wd_leave_request',
          data: expect.objectContaining({
            wd_req_no: expect.stringMatching(/^REQ-LONG-LEAVE-SAMPLE-RUN-/),
            wd_req_days: 5,
          }),
        }),
      }),
    })));
    const requestBody = post.mock.calls[0]?.[1] as {
      context?: { record?: { recordPid?: string; data?: { wd_req_no?: string; recordPid?: string } } };
    };
    expect(requestBody.context?.record?.recordPid).toMatch(/^REQ-LONG-LEAVE-SAMPLE-RUN-/);
    expect(requestBody.context?.record?.data?.recordPid).toBe(requestBody.context?.record?.recordPid);
    expect(requestBody.context?.record?.data?.wd_req_no).toBe(requestBody.context?.record?.recordPid);
    await waitFor(() => expect(screen.getByTestId('epd-run-summary')).toHaveTextContent('已命中 / 全部成功'));
    expect(screen.getByTestId('epd-abnormal-actions')).toHaveTextContent('无异常动作');
  });
});
