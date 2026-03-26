// web-admin/app/smart/automation/nodes/actions.ts
import type { FlowNodeDefinition } from '~/flow-designer-sdk';

/**
 * Automation Action Node Definitions
 *
 * 6 action types matching backend framework:
 * - UPDATE_RECORD: Update the triggering record or related records
 * - CREATE_RECORD: Create new records
 * - SEND_NOTIFICATION: Send notification to users
 * - EXECUTE_COMMAND: Execute a defined command
 * - CALL_API: Call external API
 * - SEND_WEBHOOK: Send webhook to external system
 */
export const actionNodes: FlowNodeDefinition[] = [
  {
    type: 'action-update-record',
    label: '$i18n:automation.action.updateRecord',
    icon: 'Save',
    category: 'action',
    description: '$i18n:automation.action.updateRecord.desc',
    configSchema: [
      {
        key: 'modelCode',
        label: '$i18n:automation.field.modelCode',
        type: 'model-select',
        required: true,
      },
      {
        key: 'recordId',
        label: '$i18n:automation.field.recordId',
        type: 'expression',
        required: true,
        placeholder: '${trigger.recordId}',
        description: '$i18n:automation.field.recordId.desc',
      },
      {
        key: 'fields',
        label: '$i18n:automation.field.updateFields',
        type: 'json',
        required: true,
        placeholder: '{ "status": "completed" }',
        description: '$i18n:automation.field.updateFields.desc',
      },
    ],
    defaultConfig: {
      actionType: 'update_record',
    },
  },
  {
    type: 'action-create-record',
    label: '$i18n:automation.action.createRecord',
    icon: 'FilePlus',
    category: 'action',
    description: '$i18n:automation.action.createRecord.desc',
    configSchema: [
      {
        key: 'modelCode',
        label: '$i18n:automation.field.modelCode',
        type: 'model-select',
        required: true,
      },
      {
        key: 'fields',
        label: '$i18n:automation.field.createFields',
        type: 'json',
        required: true,
        placeholder: '{ "name": "${trigger.name}", "status": "new" }',
        description: '$i18n:automation.field.createFields.desc',
      },
    ],
    defaultConfig: {
      actionType: 'create_record',
    },
  },
  {
    type: 'action-send-notification',
    label: '$i18n:automation.action.sendNotification',
    icon: 'Bell',
    category: 'action',
    description: '$i18n:automation.action.sendNotification.desc',
    configSchema: [
      {
        key: 'notificationType',
        label: '$i18n:automation.field.notificationType',
        type: 'select',
        required: true,
        options: [
          { label: '$i18n:automation.field.notificationType.email', value: 'email' },
          { label: '$i18n:automation.field.notificationType.sms', value: 'sms' },
          { label: '$i18n:automation.field.notificationType.push', value: 'push' },
          { label: '$i18n:automation.field.notificationType.inApp', value: 'in_app' },
        ],
      },
      {
        key: 'title',
        label: '$i18n:automation.field.notificationTitle',
        type: 'expression',
        required: true,
        placeholder: 'Task ${trigger.name} completed',
      },
      {
        key: 'content',
        label: '$i18n:automation.field.notificationContent',
        type: 'expression',
        required: true,
      },
      {
        key: 'recipients',
        label: '$i18n:automation.field.recipients',
        type: 'expression',
        required: true,
        placeholder: '${trigger.assignee}',
        description: '$i18n:automation.field.recipients.desc',
      },
    ],
    defaultConfig: {
      actionType: 'send_notification',
      notificationType: 'in_app',
    },
  },
  {
    type: 'action-execute-command',
    label: '$i18n:automation.action.executeCommand',
    icon: 'Terminal',
    category: 'action',
    description: '$i18n:automation.action.executeCommand.desc',
    configSchema: [
      {
        key: 'commandCode',
        label: '$i18n:automation.field.commandCode',
        type: 'command-select',
        required: true,
        description: '$i18n:automation.field.commandCode.desc',
      },
      {
        key: 'params',
        label: '$i18n:automation.field.commandParams',
        type: 'json',
        placeholder: '{ "recordId": "${trigger.recordId}" }',
        description: '$i18n:automation.field.commandParams.desc',
      },
    ],
    defaultConfig: {
      actionType: 'execute_command',
    },
  },
  {
    type: 'action-call-api',
    label: '$i18n:automation.action.callApi',
    icon: 'Globe',
    category: 'action',
    description: '$i18n:automation.action.callApi.desc',
    configSchema: [
      {
        key: 'url',
        label: '$i18n:automation.field.apiUrl',
        type: 'expression',
        required: true,
        placeholder: 'https://api.example.com/endpoint',
      },
      {
        key: 'method',
        label: '$i18n:automation.field.httpMethod',
        type: 'select',
        required: true,
        options: [
          { label: 'get', value: 'get' },
          { label: 'post', value: 'post' },
          { label: 'put', value: 'put' },
          { label: 'patch', value: 'patch' },
          { label: 'delete', value: 'delete' },
        ],
      },
      {
        key: 'headers',
        label: '$i18n:automation.field.httpHeaders',
        type: 'json',
        placeholder: '{ "Authorization": "Bearer ${secret.apiToken}" }',
      },
      {
        key: 'body',
        label: '$i18n:automation.field.httpBody',
        type: 'json',
        placeholder: '{ "data": "${trigger.record}" }',
      },
    ],
    defaultConfig: {
      actionType: 'call_api',
      method: 'post',
    },
  },
  {
    type: 'action-send-webhook',
    label: '$i18n:automation.action.sendWebhook',
    icon: 'Send',
    category: 'action',
    description: '$i18n:automation.action.sendWebhook.desc',
    configSchema: [
      {
        key: 'url',
        label: '$i18n:automation.field.webhookUrl',
        type: 'text',
        required: true,
        placeholder: 'https://hooks.example.com/webhook',
      },
      {
        key: 'payload',
        label: '$i18n:automation.field.webhookPayload',
        type: 'json',
        required: true,
        placeholder: '{ "event": "record_updated", "data": "${trigger.record}" }',
      },
    ],
    defaultConfig: {
      actionType: 'send_webhook',
    },
  },
  {
    type: 'action-start-process',
    label: '$i18n:automation.action.startProcess',
    icon: 'Play',
    category: 'action',
    description: '$i18n:automation.action.startProcess.desc',
    configSchema: [
      {
        key: 'processKey',
        label: '$i18n:automation.field.processKey',
        type: 'process-select',
        required: true,
      },
      {
        key: 'businessKey',
        label: '$i18n:automation.field.businessKey',
        type: 'expression',
        placeholder: '${trigger.recordId}',
        description: '$i18n:automation.field.businessKey.desc',
      },
      {
        key: 'variables',
        label: '$i18n:automation.field.processVariables',
        type: 'json',
        placeholder: '{ "assignee": "${trigger.assignee}" }',
        description: '$i18n:automation.field.processVariables.desc',
      },
    ],
    defaultConfig: {
      actionType: 'start_process',
    },
  },
];
