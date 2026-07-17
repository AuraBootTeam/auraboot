import { describe, expect, it } from 'vitest';

import type { DecisionAction } from '~/shared/decision/api/decisionApi';
import { actionNodes } from '../actions';
import {
  AUTOMATION_ACTION_CATALOG_MAPPINGS,
  actionCatalogAvailabilityForAutomationAction,
  applyActionCatalogAvailabilityToAutomationNodes,
  catalogActionTypeForAutomationAction,
  missingRequiredCatalogPathsForAutomation,
} from '../actionCatalogMapping';

const CATALOG_FIXTURE: DecisionAction[] = [
  {
    actionType: 'NOTIFY',
    inputSchema: {
      required: ['target', 'payload.title', 'payload.content'],
      fields: {
        target: { dataType: 'string', label: '通知接收人', required: true },
        'payload.title': { dataType: 'string', label: '通知标题', required: true },
        'payload.content': { dataType: 'text', label: '通知内容', required: true },
      },
    },
  },
  {
    actionType: 'WEBHOOK',
    inputSchema: {
      required: ['payload.eventType'],
      fields: {
        'payload.eventType': { dataType: 'string', label: 'Webhook 事件类型', required: true },
        payload: { dataType: 'object', label: 'Webhook 请求体字段', required: false },
      },
    },
  },
  {
    actionType: 'START_PROCESS',
    inputSchema: {
      required: ['payload.processDefinitionId'],
      fields: {
        'payload.processDefinitionId': { dataType: 'string', label: '流程标识', required: true },
        'payload.businessKey': { dataType: 'string', label: '业务主键', required: false },
        'payload.variables': { dataType: 'object', label: '流程变量', required: false },
      },
    },
  },
  {
    actionType: 'UPDATE_RECORD',
    inputSchema: {
      required: ['payload.fields'],
      fields: {
        'payload.fields': {
          dataType: 'object',
          label: '按模型字段编码组织的字段值',
          required: true,
        },
      },
    },
  },
  {
    actionType: 'SEND_SMS',
    inputSchema: {
      required: ['target', 'payload.content'],
      fields: {
        target: { dataType: 'string', label: '手机号或接收人表达式', required: true },
        'payload.template': { dataType: 'string', label: '短信模板编码', required: false },
        'payload.content': { dataType: 'text', label: '短信内容', required: true },
      },
    },
  },
  {
    actionType: 'SEND_IM',
    inputSchema: {
      required: ['target', 'payload.content'],
      fields: {
        target: { dataType: 'string', label: 'IM 接收人或群组表达式', required: true },
        'payload.channel': { dataType: 'string', label: 'IM 渠道', required: false },
        'payload.content': { dataType: 'text', label: '消息内容', required: true },
      },
    },
  },
  {
    actionType: 'CREATE_TASK',
    inputSchema: {
      required: ['payload.title', 'payload.assignee'],
      fields: {
        target: { dataType: 'string', label: '任务归属对象', required: false },
        'payload.title': { dataType: 'string', label: '任务标题', required: true },
        'payload.assignee': { dataType: 'string', label: '处理人表达式', required: true },
        'payload.dueDate': { dataType: 'string', label: '截止时间表达式', required: false },
      },
    },
  },
  {
    actionType: 'CC_TASK',
    inputSchema: {
      required: ['target'],
      fields: {
        target: { dataType: 'string', label: '抄送接收人表达式', required: true },
        'payload.taskId': { dataType: 'string', label: '任务 ID 表达式', required: false },
        'payload.message': { dataType: 'text', label: '抄送消息', required: false },
      },
    },
  },
  {
    actionType: 'ADD_COMMENT',
    inputSchema: {
      required: ['payload.content'],
      fields: {
        'payload.content': { dataType: 'text', label: '评论内容', required: true },
        'payload.mentions': { dataType: 'string', label: '提及对象表达式', required: false },
      },
    },
  },
  {
    actionType: 'PATCH_RECORD',
    inputSchema: {
      required: ['payload.fields'],
      fields: {
        'payload.fields': {
          dataType: 'object',
          label: '按模型字段编码组织的字段值',
          required: true,
        },
      },
    },
  },
  {
    actionType: 'WRITE_AUDIT',
    inputSchema: {
      required: [],
      fields: {
        'payload.message': { dataType: 'text', label: '审计消息', required: false },
        payload: { dataType: 'object', label: '审计载荷', required: false },
      },
    },
  },
];

function actionNodeByRuntimeType(actionType: string) {
  return actionNodes.find((node) => node.defaultConfig?.actionType === actionType);
}

describe('Automation action nodes — rule-center action catalog alignment', () => {
  it('declares an explicit bridge from runtime action types to rule-center action types', () => {
    expect(AUTOMATION_ACTION_CATALOG_MAPPINGS.map((item) => item.automationActionType)).toEqual([
      'update_record',
      'send_notification',
      'send_webhook',
      'start_process',
      'send_sms',
      'send_im',
      'create_task',
      'cc_task',
      'add_comment',
      'patch_record',
      'write_audit',
    ]);

    expect(catalogActionTypeForAutomationAction('send_notification')).toBe('NOTIFY');
    expect(catalogActionTypeForAutomationAction('send_webhook')).toBe('WEBHOOK');
    expect(catalogActionTypeForAutomationAction('start_process')).toBe('START_PROCESS');
    expect(catalogActionTypeForAutomationAction('update_record')).toBe('UPDATE_RECORD');
    expect(catalogActionTypeForAutomationAction('send_sms')).toBe('SEND_SMS');
    expect(catalogActionTypeForAutomationAction('send_im')).toBe('SEND_IM');
    expect(catalogActionTypeForAutomationAction('create_task')).toBe('CREATE_TASK');
    expect(catalogActionTypeForAutomationAction('cc_task')).toBe('CC_TASK');
    expect(catalogActionTypeForAutomationAction('add_comment')).toBe('ADD_COMMENT');
    expect(catalogActionTypeForAutomationAction('patch_record')).toBe('PATCH_RECORD');
    expect(catalogActionTypeForAutomationAction('write_audit')).toBe('WRITE_AUDIT');
    expect(catalogActionTypeForAutomationAction('llm_call')).toBeUndefined();
  });

  it('covers every required rule-center catalog path with a visible Automation config field', () => {
    expect(missingRequiredCatalogPathsForAutomation(actionNodes, CATALOG_FIXTURE)).toEqual([]);
  });

  it('projects provider availability from rule-center action catalog onto Automation action nodes', () => {
    const catalog: DecisionAction[] = [
      {
        actionType: 'SEND_SMS',
        consumerTypes: ['SLA', 'EVENT_POLICY', 'AUTOMATION'],
        consumerAvailability: [
          {
            consumerType: 'SLA',
            handlerAvailable: true,
            availabilityStatus: 'AVAILABLE',
          },
          {
            consumerType: 'EVENT_POLICY',
            handlerAvailable: true,
            availabilityStatus: 'AVAILABLE',
          },
          {
            consumerType: 'AUTOMATION',
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
        ],
      },
    ];

    expect(actionCatalogAvailabilityForAutomationAction('send_sms', catalog)).toMatchObject({
      automationActionType: 'send_sms',
      decisionActionType: 'SEND_SMS',
      unavailable: true,
      status: 'UNAVAILABLE',
      reason: '当前环境未配置真实短信 provider',
      providerSummary: '依赖：真实短信 provider · 未配置',
    });

    const nextNodes = applyActionCatalogAvailabilityToAutomationNodes(actionNodes, catalog);
    const smsNode = nextNodes.find((node) => node.defaultConfig?.actionType === 'send_sms');
    expect(smsNode?.metadata?.availability).toMatchObject({
      unavailable: true,
      status: 'UNAVAILABLE',
      reason: '当前环境未配置真实短信 provider',
      providerSummary: '依赖：真实短信 provider · 未配置',
      source: 'decision-action-catalog',
      actionType: 'SEND_SMS',
    });
  });

  it('does not mark catalog actions that are not exposed to Automation consumers', () => {
    const catalog: DecisionAction[] = [
      {
        actionType: 'SEND_SMS',
        handlerAvailable: false,
        availabilityStatus: 'UNAVAILABLE',
        availabilityReason: 'SLA-only outage',
        consumerTypes: ['SLA'],
      },
    ];

    expect(actionCatalogAvailabilityForAutomationAction('send_sms', catalog)).toBeUndefined();
    const nextNodes = applyActionCatalogAvailabilityToAutomationNodes(actionNodes, catalog);
    const smsNode = nextNodes.find((node) => node.defaultConfig?.actionType === 'send_sms');
    expect(smsNode?.metadata?.availability).toBeUndefined();
  });

  it('keeps the Webhook event type visible instead of hiding it inside raw payload JSON', () => {
    const webhook = actionNodeByRuntimeType('send_webhook');
    const eventTypeField = webhook?.configSchema?.find((field) => field.key === 'eventType');

    expect(eventTypeField).toMatchObject({
      key: 'eventType',
      label: '$i18n:automation.field.webhookEventType',
      type: 'expression',
      required: true,
      group: 'target',
    });
  });

  it('keeps message and collaboration catalog actions visible as structured Automation fields', () => {
    expect(actionNodeByRuntimeType('send_sms')?.configSchema?.map((field) => field.key)).toEqual([
      'target',
      'template',
      'content',
    ]);
    expect(actionNodeByRuntimeType('send_im')?.configSchema?.map((field) => field.key)).toEqual([
      'target',
      'channel',
      'content',
    ]);
    expect(actionNodeByRuntimeType('cc_task')?.configSchema?.map((field) => field.key)).toEqual([
      'target',
      'taskId',
      'message',
    ]);
    expect(actionNodeByRuntimeType('add_comment')?.configSchema?.map((field) => field.key)).toEqual(
      ['content', 'mentions'],
    );
    expect(actionNodeByRuntimeType('write_audit')?.configSchema?.map((field) => field.key)).toEqual(
      ['message', 'payload'],
    );
  });
});
