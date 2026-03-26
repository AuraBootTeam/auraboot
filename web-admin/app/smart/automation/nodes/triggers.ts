// web-admin/app/smart/automation/nodes/triggers.ts
import type { FlowNodeDefinition } from '~/flow-designer-sdk';

/**
 * Automation Trigger Node Definitions
 *
 * 6 trigger types matching backend framework:
 * - ON_RECORD_CREATE: Record create event
 * - ON_RECORD_UPDATE: Record update event
 * - ON_FIELD_CHANGE: Field change event
 * - ON_STATE_CHANGE: State transition event
 * - SCHEDULED: Cron-based scheduled trigger
 * - WEBHOOK: External webhook trigger
 */
export const triggerNodes: FlowNodeDefinition[] = [
  {
    type: 'trigger-record-create',
    label: '$i18n:automation.trigger.recordCreate',
    icon: 'Plus',
    category: 'trigger',
    description: '$i18n:automation.trigger.recordCreate.desc',
    configSchema: [
      {
        key: 'modelCode',
        label: '$i18n:automation.field.modelCode',
        type: 'model-select',
        required: true,
        description: '$i18n:automation.field.modelCode.desc',
      },
    ],
    defaultConfig: {
      triggerType: 'on_record_create',
    },
  },
  {
    type: 'trigger-record-update',
    label: '$i18n:automation.trigger.recordUpdate',
    icon: 'Pencil',
    category: 'trigger',
    description: '$i18n:automation.trigger.recordUpdate.desc',
    configSchema: [
      {
        key: 'modelCode',
        label: '$i18n:automation.field.modelCode',
        type: 'model-select',
        required: true,
      },
      {
        key: 'watchFields',
        label: '$i18n:automation.field.watchFields',
        type: 'multiselect',
        description: '$i18n:automation.field.watchFields.desc',
      },
    ],
    defaultConfig: {
      triggerType: 'on_record_update',
    },
  },
  {
    type: 'trigger-field-change',
    label: '$i18n:automation.trigger.fieldChange',
    icon: 'RefreshCw',
    category: 'trigger',
    description: '$i18n:automation.trigger.fieldChange.desc',
    configSchema: [
      {
        key: 'modelCode',
        label: '$i18n:automation.field.modelCode',
        type: 'model-select',
        required: true,
      },
      {
        key: 'fieldCode',
        label: '$i18n:automation.field.fieldCode',
        type: 'field-select',
        required: true,
      },
      {
        key: 'fromValue',
        label: '$i18n:automation.field.fromValue',
        type: 'text',
        description: '$i18n:automation.field.fromValue.desc',
      },
      {
        key: 'toValue',
        label: '$i18n:automation.field.toValue',
        type: 'text',
        description: '$i18n:automation.field.toValue.desc',
      },
    ],
    defaultConfig: {
      triggerType: 'on_field_change',
    },
  },
  {
    type: 'trigger-state-change',
    label: '$i18n:automation.trigger.stateChange',
    icon: 'GitBranch',
    category: 'trigger',
    description: '$i18n:automation.trigger.stateChange.desc',
    configSchema: [
      {
        key: 'modelCode',
        label: '$i18n:automation.field.modelCode',
        type: 'model-select',
        required: true,
      },
      {
        key: 'stateField',
        label: '$i18n:automation.field.stateField',
        type: 'field-select',
        required: true,
      },
      {
        key: 'fromStates',
        label: '$i18n:automation.field.fromStates',
        type: 'multiselect',
        description: '$i18n:automation.field.fromStates.desc',
      },
      {
        key: 'toStates',
        label: '$i18n:automation.field.toStates',
        type: 'multiselect',
        description: '$i18n:automation.field.toStates.desc',
      },
    ],
    defaultConfig: {
      triggerType: 'on_state_change',
    },
  },
  {
    type: 'trigger-scheduled',
    label: '$i18n:automation.trigger.scheduled',
    icon: 'Clock',
    category: 'trigger',
    description: '$i18n:automation.trigger.scheduled.desc',
    configSchema: [
      {
        key: 'cron',
        label: '$i18n:automation.field.cron',
        type: 'text',
        required: true,
        placeholder: '0 0 * * *',
        description: '$i18n:automation.field.cron.desc',
      },
      {
        key: 'timezone',
        label: '$i18n:automation.field.timezone',
        type: 'select',
        options: [
          { label: 'Asia/Shanghai', value: 'Asia/Shanghai' },
          { label: 'utc', value: 'utc' },
          { label: 'America/New_York', value: 'America/New_York' },
          { label: 'Europe/London', value: 'Europe/London' },
        ],
      },
      {
        key: 'maxExecutionTime',
        label: '$i18n:automation.field.maxExecutionTime',
        type: 'number',
        description: '$i18n:automation.field.maxExecutionTime.desc',
      },
    ],
    defaultConfig: {
      triggerType: 'scheduled',
      timezone: 'Asia/Shanghai',
    },
  },
  {
    type: 'trigger-webhook',
    label: '$i18n:automation.trigger.webhook',
    icon: 'Link',
    category: 'trigger',
    description: '$i18n:automation.trigger.webhook.desc',
    configSchema: [
      {
        key: 'secret',
        label: '$i18n:automation.field.secret',
        type: 'text',
        description: '$i18n:automation.field.secret.desc',
      },
      {
        key: 'validationMode',
        label: '$i18n:automation.field.validationMode',
        type: 'select',
        options: [
          { label: '$i18n:automation.field.validationMode.none', value: 'none' },
          { label: '$i18n:automation.field.validationMode.signature', value: 'signature' },
          { label: '$i18n:automation.field.validationMode.token', value: 'token' },
        ],
      },
      {
        key: 'expectedHeaders',
        label: '$i18n:automation.field.expectedHeaders',
        type: 'multiselect',
        description: '$i18n:automation.field.expectedHeaders.desc',
      },
    ],
    defaultConfig: {
      triggerType: 'webhook',
      validationMode: 'none',
    },
  },
];
