import { render, act, fireEvent, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import React from 'react';

// ---------------------------------------------------------------------------
// ACP H.1 regression test.
//
// Bug: AutomationEditor used to pass its local `flowData` state back to
// FlowDesigner as the `initialData` prop. Every onChange from FlowDesigner
// would call `setFlowData(...)`, producing a brand-new prop reference on
// the next render. FlowDesigner's mount-effect (`useEffect(..., [initialData])`)
// then re-ran `importData()`, which clears `selectedNodeId` and unmounts
// the property panel — forcing the user (and the LLM-call E2E spec) to
// re-click the node before each successive field edit.
//
// Fix: AutomationEditor memoises a `flowDataInitial` derived strictly from
// the `initialData` prop. The reference passed to FlowDesigner is now stable
// across onChange-driven re-renders. This test guards the invariant.
// ---------------------------------------------------------------------------

// Capture every `initialData` reference handed to FlowDesigner across renders.
const receivedInitialData: Array<unknown> = [];
const receivedFlowDesignerProps: Array<any> = [];
let lastOnChange: ((data: unknown) => void) | undefined;
const apiMocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  delete: vi.fn(),
}));

vi.mock('~/plugins/core-designer/components/flow-designer-sdk', () => ({
  FlowDesigner: (props: any) => {
    receivedFlowDesignerProps.push(props);
    receivedInitialData.push(props.initialData);
    lastOnChange = props.onChange;
    return <div data-testid="flow-designer-mock" />;
  },
  // useFlowValidation is consumed by AutomationEditor.handleToolbarSave to gate
  // saves (G4/P0-4). The validation behavior itself is covered by the SDK's
  // own unit tests + the golden E2E; here we only need a stable hook stub so
  // the editor renders.
  useFlowValidation: () => ({
    validate: () => ({ valid: true, errors: [] }),
    validationResult: null,
  }),
}));

vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (key: string) => key,
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
  }),
}));

vi.mock('../../debug', () => ({
  AutomationDebugger: () => <div data-testid="automation-debugger" />,
  useDebugSession: () => ({
    isDebugMode: false,
    startDebug: vi.fn(),
  }),
}));

vi.mock('~/shared/designer/DesignerToolbar', () => ({
  DesignerToolbar: ({ children, titleElement }: any) => (
    <div data-testid="designer-toolbar">
      {titleElement}
      {children}
    </div>
  ),
}));

vi.mock('~/shared/services/ApiService', () => ({
  getApiService: () => apiMocks,
}));

// nodes module is imported by AutomationEditor; provide a minimal stub so
// vitest does not pull the entire automation node graph during this unit
// test.
vi.mock('../../nodes', () => ({
  automationNodes: [
    {
      type: 'action-send-sms',
      label: '$i18n:automation.action.sendSms',
      icon: 'MessageSquareText',
      category: 'action',
      description: '$i18n:automation.action.sendSms.desc',
      defaultConfig: { actionType: 'send_sms' },
    },
  ],
  automationCategoryOrder: ['action'],
}));

// Import AFTER the mocks are wired up.
import { AutomationEditor } from '../AutomationEditor';
import type { FlowData } from '~/plugins/core-designer/components/flow-designer-sdk';

const buildFlowData = (suffix: string): FlowData => ({
  nodes: [
    {
      id: `node_${suffix}`,
      type: 'trigger.scheduled',
      position: { x: 0, y: 0 },
      data: { label: 'Trigger', config: {} },
    },
  ],
  edges: [],
});

describe('AutomationEditor — initialData stability (ACP H.1)', () => {
  beforeEach(() => {
    receivedInitialData.length = 0;
    receivedFlowDesignerProps.length = 0;
    lastOnChange = undefined;
    apiMocks.get.mockReset();
    apiMocks.post.mockReset();
    apiMocks.delete.mockReset();
    apiMocks.get.mockResolvedValue({ data: { actions: [] } });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('keeps the initialData reference stable across FlowDesigner onChange events', () => {
    const initial = {
      name: 'Auto X',
      description: 'desc',
      flowData: buildFlowData('a'),
    };

    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={initial}
        onSave={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    expect(receivedInitialData).toHaveLength(1);
    const firstRef = receivedInitialData[0];
    expect(firstRef).toBe(initial.flowData);

    // Simulate FlowDesigner reporting a content change (e.g. user typed in a
    // property field, store updated, onChange fired with new nodes/edges).
    act(() => {
      lastOnChange?.(buildFlowData('b'));
    });

    // The onChange handler bumps internal `flowData` state and `isDirty`,
    // forcing AutomationEditor to re-render. The CRITICAL invariant: the
    // reference passed to FlowDesigner as `initialData` MUST NOT change,
    // otherwise FlowDesigner's mount-effect re-runs importData() and resets
    // `selectedNodeId` to null.
    expect(receivedInitialData.length).toBeGreaterThan(1);
    for (const ref of receivedInitialData) {
      expect(ref).toBe(firstRef);
    }
  });

  it('updates initialData reference when the parent supplies a new prop', () => {
    const initialA = {
      name: 'Auto A',
      flowData: buildFlowData('a'),
    };
    const initialB = {
      name: 'Auto B',
      flowData: buildFlowData('b'),
    };

    const { rerender } = render(
      <AutomationEditor automationId="auto-1" initialData={initialA} />,
    );
    const refsAfterMount = [...receivedInitialData];
    expect(refsAfterMount[0]).toBe(initialA.flowData);

    rerender(
      <AutomationEditor automationId="auto-1" initialData={initialB} />,
    );

    // After the parent supplies a *new* initialData object (e.g. reload from
    // server), FlowDesigner SHOULD see the new reference so it imports the
    // refreshed schema.
    const lastRef = receivedInitialData[receivedInitialData.length - 1];
    expect(lastRef).toBe(initialB.flowData);
    expect(lastRef).not.toBe(initialA.flowData);
  });

  it('keeps the primary save action in the page toolbar instead of rendering a second canvas save', () => {
    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: 'Auto X',
          flowData: buildFlowData('a'),
        }}
        onSave={vi.fn().mockResolvedValue(undefined)}
      />,
    );

    expect(receivedFlowDesignerProps.at(-1)?.onSave).toBeUndefined();
    expect(receivedFlowDesignerProps.at(-1)?.onChange).toBeTypeOf('function');
  });

  it('keeps the automation canvas free of the minimap overlay', () => {
    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: 'Auto X',
          flowData: buildFlowData('a'),
        }}
      />,
    );

    expect(receivedFlowDesignerProps.at(-1)?.config.showMinimap).toBe(false);
    expect(receivedFlowDesignerProps.at(-1)?.config.showControls).toBe(true);
  });

  it('loads rule-center action catalog availability into Automation node definitions', async () => {
    apiMocks.get.mockResolvedValue({
      data: {
        actions: [
          {
            actionType: 'SEND_SMS',
            handlerAvailable: false,
            availabilityStatus: 'UNAVAILABLE',
            availabilityReason: '当前环境未配置真实短信 provider',
            consumerTypes: ['SLA', 'EVENT_POLICY', 'AUTOMATION'],
          },
        ],
      },
    });

    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: 'Auto X',
          flowData: buildFlowData('a'),
        }}
      />,
    );

    await waitFor(() => expect(apiMocks.get).toHaveBeenCalledWith('/decision/actions/catalog', undefined));
    await waitFor(() => {
      const smsNode = receivedFlowDesignerProps
        .at(-1)
        ?.config.nodeDefinitions.find((node: any) => node.type === 'action-send-sms');
      expect(smsNode?.metadata?.availability).toMatchObject({
        unavailable: true,
        status: 'UNAVAILABLE',
        reason: '当前环境未配置真实短信 provider',
        source: 'decision-action-catalog',
        actionType: 'SEND_SMS',
      });
    });
  });

  it('sends the configured business sample when running an automation test', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ data: { status: 'success', durationMs: 12 } }),
    });
    vi.stubGlobal('fetch', fetchMock);

    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: '长假申请提醒',
          flowData: buildFlowData('a'),
        }}
        testRunRecordPid="REQ-LONG-LEAVE-SAMPLE"
        testRunContext={{
          record: {
            wd_req_days: 5,
            wd_req_type: 'annual',
          },
          source: 'ui-test-run',
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('btn-test-run'));

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const [, init] = fetchMock.mock.calls[0];
    expect(init.credentials).toBe('same-origin');
    expect(JSON.parse(String(init.body))).toEqual({
      recordPid: 'REQ-LONG-LEAVE-SAMPLE',
      context: {
        record: {
          wd_req_days: 5,
          wd_req_type: 'annual',
        },
        source: 'ui-test-run',
      },
    });
  });

  it('shows the latest test run result with action side effects', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        data: {
          pid: 'log-1',
          status: 'success',
          durationMs: 59,
          triggerRecordPid: 'REQ-LONG-LEAVE-SAMPLE',
          triggerPayload: {
            source: 'ui-test-run',
            record: { wd_req_days: 5 },
          },
          actionResults: [
            {
              sequence: 1,
              actionType: 'send_notification',
              status: 'success',
              durationMs: 17,
              result: {
                success: true,
                type: 'in_app',
                title: '长假申请提醒',
                sentCount: 1,
                recipientCount: 1,
                recipients: ['ROLE:wd_manager'],
              },
            },
          ],
        },
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: '长假申请提醒',
          flowData: buildFlowData('a'),
        }}
        testRunRecordPid="REQ-LONG-LEAVE-SAMPLE"
        testRunContext={{
          record: { wd_req_days: 5 },
          source: 'ui-test-run',
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('btn-test-run'));

    await waitFor(() => expect(screen.getByTestId('automation-test-run-result')).toBeInTheDocument());
    expect(screen.getByTestId('automation-run-status')).toHaveTextContent('Success');
    expect(screen.getByTestId('automation-run-status')).not.toHaveTextContent('success');
    expect(screen.getByTestId('automation-test-run-result')).toHaveTextContent('REQ-LONG-LEAVE-SAMPLE');
    const actionResult = screen.getByTestId('automation-action-result-1');
    expect(actionResult).toHaveTextContent('Send Notification');
    expect(screen.getByTestId('automation-action-status-1')).toHaveTextContent('Success');
    expect(screen.getByTestId('automation-action-status-1')).not.toHaveTextContent('success');
    expect(actionResult).toHaveTextContent('Sent');
    expect(actionResult).toHaveTextContent('Recipients');
    expect(actionResult).toHaveTextContent('Recipient List');
    expect(screen.getByTestId('automation-action-evidence-type')).toHaveTextContent('In-app notification');
    expect(screen.getByTestId('automation-action-evidence-type')).not.toHaveTextContent('in_app');
    expect(actionResult).toHaveTextContent('ROLE:wd_manager');
    expect(actionResult).toHaveTextContent('Raw result');
    expect(actionResult).not.toHaveTextContent('send_notification');
  });

  it('shows rule binding decision trace and runtime overlay link after a test run', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        data: {
          id: 42,
          pid: 'log-decision',
          status: 'success',
          durationMs: 63,
          triggerRecordPid: 'ORDER-HIGH-VALUE',
          triggerPayload: {
            source: 'ui-test-run',
            decision: {
              decisionCode: 'auto_notify_route',
              status: 'MATCHED',
              matched: true,
              traceId: 'trace-route-1',
              outputs: {
                route: 'DIRECTOR',
                message: 'High value order',
              },
            },
          },
          actionResults: [
            {
              sequence: 1,
              actionType: 'send_notification',
              status: 'success',
              durationMs: 16,
              result: {
                success: true,
                type: 'in_app',
                title: 'Route DIRECTOR',
                content: 'Route DIRECTOR: High value order',
                sentCount: 1,
              },
            },
          ],
        },
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: '高价值订单通知',
          flowData: buildFlowData('a'),
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('btn-test-run'));

    await waitFor(() => expect(screen.getByTestId('automation-test-run-result')).toBeInTheDocument());
    const decisionTrace = screen.getByTestId('automation-decision-trace');
    expect(decisionTrace).toHaveTextContent('Rule Decision Trace');
    expect(decisionTrace).toHaveTextContent('Matched');
    expect(decisionTrace).toHaveTextContent('auto_notify_route');
    expect(decisionTrace).toHaveTextContent('trace-route-1');
    expect(decisionTrace).toHaveTextContent('route');
    expect(decisionTrace).toHaveTextContent('DIRECTOR');
    expect(decisionTrace).toHaveTextContent('message');
    expect(decisionTrace).toHaveTextContent('High value order');

    const unifiedTraceLink = screen.getByTestId('automation-unified-trace-link') as HTMLAnchorElement;
    expect(unifiedTraceLink).toHaveTextContent('Open Unified Trace');
    expect(unifiedTraceLink.getAttribute('href')).toBe(
      '/p/decisionops_execution_logs?traceId=trace-route-1&decisionCode=auto_notify_route&callerType=AUTOMATION&callerRef=auto-1',
    );

    const runtimeLink = screen.getByTestId('automation-runtime-trace-link') as HTMLAnchorElement;
    expect(runtimeLink).toHaveTextContent('Open Runtime Trace');
    expect(runtimeLink.getAttribute('href')).toBe('/automation/auto-1?logId=42');
  });

  it('shows SMS action side effects as structured evidence instead of raw-only JSON', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        data: {
          pid: 'log-sms',
          status: 'success',
          durationMs: 41,
          triggerRecordPid: 'REQ-LONG-LEAVE-SAMPLE',
          triggerPayload: { source: 'ui-test-run' },
          actionResults: [
            {
              sequence: 2,
              actionType: 'send_sms',
              status: 'success',
              durationMs: 24,
              result: {
                success: true,
                channel: 'sms',
                template: 'leave_timeout',
                sentCount: 1,
                targetPhones: ['+15551234567'],
                messageIds: ['sms-msg-1'],
                providers: ['twilio'],
                provider: 'twilio',
                modelCode: 'wd_leave_request',
                recordPid: 'REQ-LONG-LEAVE-SAMPLE',
              },
            },
          ],
        },
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: '长假申请提醒',
          flowData: buildFlowData('a'),
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('btn-test-run'));

    await waitFor(() => expect(screen.getByTestId('automation-test-run-result')).toBeInTheDocument());
    const actionResult = screen.getByTestId('automation-action-result-2');
    expect(actionResult).toHaveTextContent('Send Sms');
    expect(actionResult).toHaveTextContent('Channel');
    expect(actionResult).toHaveTextContent('SMS Template');
    expect(actionResult).toHaveTextContent('Phone Targets');
    expect(actionResult).toHaveTextContent('+15551234567');
    expect(actionResult).toHaveTextContent('Message IDs');
    expect(actionResult).toHaveTextContent('sms-msg-1');
    expect(actionResult).toHaveTextContent('SMS Providers');
    expect(actionResult).toHaveTextContent('twilio');
    const evidence = actionResult.querySelector('[data-testid="automation-action-evidence"]');
    expect(evidence).toBeInTheDocument();
    expect(evidence).not.toHaveTextContent('channel');
    expect(evidence).not.toHaveTextContent('targetPhones');
    expect(evidence).not.toHaveTextContent('messageIds');
  });

  it('shows SMS provider failure runtime evidence with product labels', async () => {
    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: '短信失败追踪',
          flowData: buildFlowData('sms-failed'),
        }}
        initialRunLog={{
          id: 53,
          pid: 'log-sms-failed-runtime',
          automationId: 'auto-1',
          status: 'failed',
          durationMs: 32,
          errorMessage: 'No real SMS sender available',
          triggerRecordPid: 'REQ-LONG-LEAVE-SAMPLE',
          triggerPayload: { source: 'runtime-trace-link' },
          actionResults: [
            {
              sequence: 1,
              actionType: 'send_sms',
              status: 'failed',
              durationMs: 17,
              errorMessage: 'No real SMS sender available',
              result: {
                success: false,
                channel: 'sms',
                template: 'automation_timeout',
                sentCount: 0,
                targetPhones: ['+8613800138000'],
                messageIds: [],
                providers: [],
                failureReason: 'sms_delivery_failed',
                errorMessage: 'No real SMS sender available',
                modelCode: 'wd_leave_request',
                recordPid: 'REQ-LONG-LEAVE-SAMPLE',
              },
            },
          ],
        }}
      />,
    );

    await waitFor(() => expect(screen.getByTestId('automation-test-run-result')).toBeInTheDocument());
    const actionResult = screen.getByTestId('automation-action-result-1');
    expect(actionResult).toHaveTextContent('Send Sms');
    expect(actionResult).toHaveTextContent('Failed');
    expect(actionResult).toHaveTextContent('Channel');
    expect(actionResult).toHaveTextContent('SMS');
    expect(actionResult).toHaveTextContent('SMS Template');
    expect(actionResult).toHaveTextContent('automation_timeout');
    expect(actionResult).toHaveTextContent('Phone Targets');
    expect(actionResult).toHaveTextContent('+8613800138000');
    expect(actionResult).toHaveTextContent('Failure Reason');
    expect(actionResult).toHaveTextContent('SMS delivery failed');
    expect(actionResult).toHaveTextContent('Error Message');
    expect(actionResult).toHaveTextContent('No real SMS sender available');
    const evidence = actionResult.querySelector('[data-testid="automation-action-evidence"]');
    expect(evidence).toBeInTheDocument();
    expect(evidence).not.toHaveTextContent('failureReason');
    expect(evidence).not.toHaveTextContent('sms_delivery_failed');
    expect(evidence).not.toHaveTextContent('targetPhones');
    expect(evidence).not.toHaveTextContent('errorMessage');
  });

  it('shows Webhook direct HTTP delivery evidence with product labels', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        data: {
          pid: 'log-webhook',
          status: 'success',
          durationMs: 38,
          triggerRecordPid: 'REQ-LONG-LEAVE-SAMPLE',
          triggerPayload: { source: 'ui-test-run' },
          actionResults: [
            {
              sequence: 3,
              actionType: 'send_webhook',
              status: 'success',
              durationMs: 29,
              result: {
                success: true,
                deliveryMode: 'direct_http',
                statusCode: 202,
                url: 'https://hooks.example/a',
                responseBodyPreview: '{"accepted":true}',
                responseBytes: 17,
              },
            },
          ],
        },
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: '长假申请提醒',
          flowData: buildFlowData('a'),
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('btn-test-run'));

    await waitFor(() => expect(screen.getByTestId('automation-test-run-result')).toBeInTheDocument());
    const actionResult = screen.getByTestId('automation-action-result-3');
    expect(actionResult).toHaveTextContent('Send Webhook');
    expect(actionResult).toHaveTextContent('Delivery Mode');
    expect(actionResult).toHaveTextContent('Direct HTTP');
    expect(actionResult).toHaveTextContent('HTTP Status');
    expect(actionResult).toHaveTextContent('202');
    expect(actionResult).toHaveTextContent('Target URL');
    expect(actionResult).toHaveTextContent('https://hooks.example/a');
    expect(actionResult).toHaveTextContent('Response Preview');
    expect(actionResult).toHaveTextContent('{"accepted":true}');
    expect(actionResult).toHaveTextContent('Response Bytes');
    const evidence = actionResult.querySelector('[data-testid="automation-action-evidence"]');
    expect(evidence).toBeInTheDocument();
    expect(evidence).not.toHaveTextContent('deliveryMode');
    expect(evidence).not.toHaveTextContent('responseBodyPreview');
    expect(evidence).not.toHaveTextContent('responseBytes');
  });

  it('opens runtime trace action evidence from an initial run log without clicking Test Run', async () => {
    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: 'Webhook 出站追踪',
          flowData: buildFlowData('webhook'),
        }}
        initialRunLog={{
          id: 51,
          pid: 'log-webhook-runtime',
          automationId: 'auto-1',
          status: 'success',
          durationMs: 38,
          triggerRecordPid: 'REQ-LONG-LEAVE-SAMPLE',
          triggerPayload: { source: 'runtime-trace-link' },
          actionResults: [
            {
              sequence: 1,
              actionType: 'send_webhook',
              status: 'success',
              durationMs: 29,
              result: {
                success: true,
                deliveryMode: 'direct_http',
                statusCode: 200,
                url: 'https://hooks.example/runtime',
                responseBodyPreview: '{"ok":true}',
                responseBytes: 11,
              },
            },
          ],
        }}
      />,
    );

    await waitFor(() => expect(screen.getByTestId('automation-test-run-result')).toBeInTheDocument());
    const actionResult = screen.getByTestId('automation-action-result-1');
    expect(actionResult).toHaveTextContent('Send Webhook');
    expect(actionResult).toHaveTextContent('Delivery Mode');
    expect(actionResult).toHaveTextContent('Direct HTTP');
    expect(actionResult).toHaveTextContent('HTTP Status');
    expect(actionResult).toHaveTextContent('200');
    expect(actionResult).toHaveTextContent('Target URL');
    expect(actionResult).toHaveTextContent('https://hooks.example/runtime');
    expect(actionResult).toHaveTextContent('Response Preview');
    expect(actionResult).toHaveTextContent('{"ok":true}');
    const evidence = actionResult.querySelector('[data-testid="automation-action-evidence"]');
    expect(evidence).toBeInTheDocument();
    expect(evidence).not.toHaveTextContent('deliveryMode');
    expect(evidence).not.toHaveTextContent('responseBodyPreview');
    const runtimeLink = screen.getByTestId('automation-runtime-trace-link') as HTMLAnchorElement;
    expect(runtimeLink.getAttribute('href')).toBe('/automation/auto-1?logId=51');
  });

  it('opens runtime trace action evidence when an initial run log arrives after route load', async () => {
    const { rerender } = render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: 'Webhook 出站追踪',
          flowData: buildFlowData('webhook'),
        }}
        initialRunLog={null}
      />,
    );

    expect(screen.queryByTestId('automation-test-run-result')).not.toBeInTheDocument();

    rerender(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: 'Webhook 出站追踪',
          flowData: buildFlowData('webhook'),
        }}
        initialRunLog={{
          id: 52,
          pid: 'log-webhook-runtime-async',
          automationId: 'auto-1',
          status: 'success',
          durationMs: 31,
          triggerRecordPid: 'REQ-ASYNC',
          triggerPayload: { source: 'runtime-route' },
          actionResults: [
            {
              sequence: 1,
              actionType: 'send_webhook',
              status: 'success',
              durationMs: 24,
              result: {
                success: true,
                deliveryMode: 'direct_http',
                statusCode: 200,
                url: 'https://hooks.example/async',
                responseBodyPreview: '{"ok":true}',
              },
            },
          ],
        }}
      />,
    );

    await waitFor(() => expect(screen.getByTestId('automation-test-run-result')).toBeInTheDocument());
    const actionResult = screen.getByTestId('automation-action-result-1');
    expect(actionResult).toHaveTextContent('Send Webhook');
    expect(actionResult).toHaveTextContent('https://hooks.example/async');
    expect(screen.getByTestId('automation-runtime-trace-link')).toHaveAttribute(
      'href',
      '/automation/auto-1?logId=52',
    );
  });

  it('shows collaboration and governance runtime evidence with product labels', async () => {
    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: '协作动作追踪',
          flowData: buildFlowData('collaboration'),
        }}
        initialRunLog={{
          id: 52,
          pid: 'log-collaboration-runtime',
          automationId: 'auto-1',
          status: 'success',
          durationMs: 84,
          triggerRecordPid: 'REQ-LONG-LEAVE-SAMPLE',
          triggerPayload: { source: 'runtime-trace-link' },
          actionResults: [
            {
              sequence: 1,
              actionType: 'send_im',
              status: 'success',
              durationMs: 12,
              result: {
                success: true,
                channel: 'im',
                sentCount: 1,
                targetUserIds: [1],
                conversationIds: [77],
                messageIds: [88],
                modelCode: 'wd_leave_request',
                recordPid: 'REQ-LONG-LEAVE-SAMPLE',
              },
            },
            {
              sequence: 2,
              actionType: 'create_task',
              status: 'success',
              durationMs: 18,
              result: {
                success: true,
                delivery: 'inbox',
                itemType: 'task',
                createdCount: 1,
                assigneeUserIds: [1],
                inboxItemIds: [101],
                modelCode: 'wd_leave_request',
                recordPid: 'REQ-LONG-LEAVE-SAMPLE',
              },
            },
            {
              sequence: 3,
              actionType: 'cc_task',
              status: 'success',
              durationMs: 14,
              result: {
                success: true,
                delivery: 'inbox',
                itemType: 'mention',
                ccCount: 1,
                targetUserIds: [1],
                inboxItemIds: [102],
              },
            },
            {
              sequence: 4,
              actionType: 'add_comment',
              status: 'success',
              durationMs: 9,
              result: {
                success: true,
                commentPid: 'comment-1',
                modelCode: 'wd_leave_request',
                recordPid: 'REQ-LONG-LEAVE-SAMPLE',
                mentions: 'ROLE:wd_manager',
              },
            },
            {
              sequence: 5,
              actionType: 'write_audit',
              status: 'success',
              durationMs: 11,
              result: {
                auditPid: 'audit-1',
                ruleCode: 'auto-1',
                message: 'Audit for REQ-LONG-LEAVE-SAMPLE',
              },
            },
          ],
        }}
      />,
    );

    await waitFor(() => expect(screen.getByTestId('automation-test-run-result')).toBeInTheDocument());

    const imResult = screen.getByTestId('automation-action-result-1');
    expect(imResult).toHaveTextContent('Channel');
    expect(imResult).toHaveTextContent('IM message');
    expect(imResult).toHaveTextContent('Target Users');
    expect(imResult).toHaveTextContent('Conversation IDs');
    expect(imResult).toHaveTextContent('Message IDs');
    const imEvidence = imResult.querySelector('[data-testid="automation-action-evidence"]');
    expect(imEvidence).not.toHaveTextContent('targetUserIds');
    expect(imEvidence).not.toHaveTextContent('conversationIds');

    const taskResult = screen.getByTestId('automation-action-result-2');
    expect(taskResult).toHaveTextContent('Delivery');
    expect(taskResult).toHaveTextContent('Inbox');
    expect(taskResult).toHaveTextContent('Inbox Type');
    expect(taskResult).toHaveTextContent('Task');
    expect(taskResult).toHaveTextContent('Created');
    expect(taskResult).toHaveTextContent('Assignees');
    expect(taskResult).toHaveTextContent('Inbox Item IDs');
    const taskEvidence = taskResult.querySelector('[data-testid="automation-action-evidence"]');
    expect(taskEvidence).not.toHaveTextContent('inboxItemIds');
    expect(taskEvidence).not.toHaveTextContent('assigneeUserIds');
    expect(taskEvidence).not.toHaveTextContent('createdCount');
    expect(taskEvidence).not.toHaveTextContent('itemType');

    const ccResult = screen.getByTestId('automation-action-result-3');
    expect(ccResult).toHaveTextContent('CC Count');
    expect(ccResult).toHaveTextContent('Mention');
    expect(ccResult).toHaveTextContent('Target Users');
    const ccEvidence = ccResult.querySelector('[data-testid="automation-action-evidence"]');
    expect(ccEvidence).not.toHaveTextContent('targetUserIds');
    expect(ccEvidence).not.toHaveTextContent('ccCount');

    const commentResult = screen.getByTestId('automation-action-result-4');
    expect(commentResult).toHaveTextContent('Comment');
    expect(commentResult).toHaveTextContent('Mentions');
    expect(commentResult.querySelector('[data-testid="automation-action-evidence"]')).not.toHaveTextContent('commentPid');

    const auditResult = screen.getByTestId('automation-action-result-5');
    expect(auditResult).toHaveTextContent('Audit Entry');
    expect(auditResult).toHaveTextContent('Rule / Automation');
    expect(auditResult.querySelector('[data-testid="automation-action-evidence"]')).not.toHaveTextContent('auditPid');
  });

  it('falls back to the configured action chain when the runtime log has no actionResults', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        data: {
          pid: 'log-2',
          status: 'success',
          durationMs: 25,
          triggerRecordPid: 'REQ-LONG-LEAVE-SAMPLE',
          triggerPayload: { source: 'ui-test-run' },
          actionResults: [],
        },
      }),
    });
    vi.stubGlobal('fetch', fetchMock);

    render(
      <AutomationEditor
        automationId="auto-1"
        initialData={{
          name: '长假申请提醒',
          flowData: {
            nodes: [
              ...buildFlowData('a').nodes,
              {
                id: 'action_0',
                type: 'action-send-notification',
                position: { x: 200, y: 0 },
                data: {
                  label: '通知主管',
                  config: {
                    actionType: 'send_notification',
                    title: '长假申请提醒',
                    recipients: 'ROLE:wd_manager',
                  },
                },
              },
            ],
            edges: [],
          },
        }}
      />,
    );

    fireEvent.click(screen.getByTestId('btn-test-run'));

    await waitFor(() => expect(screen.getByTestId('automation-test-run-result')).toBeInTheDocument());
    const declaredAction = screen.getByTestId('automation-declared-action-0');
    expect(declaredAction).toHaveTextContent('通知主管 · Send Notification');
    expect(declaredAction).toHaveTextContent('Recipient List');
    expect(declaredAction).toHaveTextContent('ROLE:wd_manager');
    expect(declaredAction).not.toHaveTextContent('send_notification');
    expect(screen.getByTestId('automation-test-run-result')).toHaveTextContent('actionResultsMissing');
  });
});
