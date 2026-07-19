import { describe, expect, it } from 'vitest';
import { findRuntimeLogByRouteParam, synthesizeFlowData } from '../AutomationEditPageImpl';
import type { Automation } from '../../services/automationService';

describe('AutomationEditPageImpl legacy flow synthesis', () => {
  it('uses registered flow node types for legacy trigger and action payloads', () => {
    const flow = synthesizeFlowData({
      pid: 'auto-1',
      name: '长假申请提醒',
      enabled: true,
      triggerType: 'on_record_create',
      triggerConfig: {},
      modelCode: 'wd_leave_request',
      actions: [
        {
          type: 'send_notification',
          label: '通知主管',
          config: { title: '长假申请提醒' },
        },
      ],
      createdAt: '2026-07-05T00:00:00Z',
      updatedAt: '2026-07-05T00:00:00Z',
    } satisfies Automation);

    expect(flow?.nodes.map((node) => node.type)).toEqual([
      'trigger-record-create',
      'action-send-notification',
    ]);
    expect(flow?.edges).toHaveLength(1);
  });

  it('normalizes legacy notification action config for the visual property panel', () => {
    const flow = synthesizeFlowData({
      pid: 'auto-1',
      name: '长假申请提醒',
      enabled: true,
      triggerType: 'on_record_create',
      triggerConfig: {},
      modelCode: 'wd_leave_request',
      actions: [
        {
          type: 'send_notification',
          label: '通知主管',
          config: {
            type: 'in_app',
            title: '长假申请提醒',
            recipients: ['1', '2'],
          },
        },
      ],
      createdAt: '2026-07-05T00:00:00Z',
      updatedAt: '2026-07-05T00:00:00Z',
    } satisfies Automation);

    expect(flow?.nodes[1].data.config).toMatchObject({
      actionType: 'send_notification',
      notificationType: 'in_app',
      title: '长假申请提醒',
      recipients: '1, 2',
    });
  });

  it('preserves legacy trigger rule binding when synthesizing flow nodes', () => {
    const flow = synthesizeFlowData({
      pid: 'auto-1',
      name: '长假申请提醒',
      enabled: true,
      triggerType: 'on_record_create',
      triggerConfig: {
        modelCode: 'wd_leave_request',
        ruleBinding: {
          consumerType: 'AUTOMATION',
          consumerCode: 'wd_leave_high_value_notify',
          consumerNodeId: 'trigger',
          bindingKind: 'DECISION_REF',
          enabled: true,
          decisionBinding: {
            decisionCode: 'leave_request_automation',
            versionPolicy: 'LATEST_PUBLISHED',
            traceMode: 'ALWAYS',
            enabled: true,
            inputMappings: [
              {
                input: 'leaveDays',
                source: { kind: 'FIELD', scope: 'RECORD', path: 'data.wd_req_days' },
              },
            ],
            outputMappings: [],
            fallbackPolicy: { mode: 'FAIL_CLOSED' },
          },
        },
      },
      modelCode: 'wd_leave_request',
      actions: [],
      createdAt: '2026-07-05T00:00:00Z',
      updatedAt: '2026-07-05T00:00:00Z',
    } satisfies Automation);

    const triggerConfig = flow?.nodes[0].data.config as any;
    expect(triggerConfig.ruleBinding.decisionBinding.decisionCode).toBe('leave_request_automation');
    expect(triggerConfig.ruleBinding.decisionBinding.inputMappings[0].source.scope).toBe('record');
  });
});

describe('AutomationEditPageImpl runtime log route matching', () => {
  it('matches runtime logs by numeric id from ?logId and by pid for legacy links', () => {
    const rows = [
      {
        id: 51,
        pid: 'log-webhook-runtime',
        automationId: 'auto-1',
        status: 'success',
      },
      {
        id: 52,
        pid: 'log-other',
        automationId: 'auto-1',
        status: 'failed',
      },
    ] as const;

    expect(findRuntimeLogByRouteParam([...rows], '51')?.pid).toBe('log-webhook-runtime');
    expect(findRuntimeLogByRouteParam([...rows], 'log-other')?.id).toBe(52);
    expect(findRuntimeLogByRouteParam([...rows], 'missing')).toBeNull();
  });
});
