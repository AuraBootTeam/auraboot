// web-admin/app/smart/automation/nodes/controls.ts
import type { FlowNodeDefinition } from '~/flow-designer-sdk';

/**
 * Automation Control Node Definitions
 */
export const controlNodes: FlowNodeDefinition[] = [
  {
    type: 'control-condition',
    label: '$i18n:automation.control.condition',
    icon: '🔀',
    category: 'control',
    description: '$i18n:automation.control.condition.desc',
    configSchema: [
      {
        key: 'expression',
        label: '$i18n:automation.field.expression',
        type: 'expression',
        required: true,
        placeholder: '${trigger.status} === "approved"',
        description: '$i18n:automation.field.expression.desc',
      },
    ],
    defaultConfig: {
      controlType: 'condition',
    },
    // Condition node has two outputs: true and false
    validation: {
      minInputs: 1,
      maxInputs: 1,
      minOutputs: 2,
      maxOutputs: 2,
    },
  },
  {
    type: 'control-delay',
    label: '$i18n:automation.control.delay',
    icon: '⏳',
    category: 'control',
    description: '$i18n:automation.control.delay.desc',
    configSchema: [
      {
        key: 'duration',
        label: '$i18n:automation.field.duration',
        type: 'number',
        required: true,
        placeholder: '30',
        description: '$i18n:automation.field.duration.desc',
      },
      {
        key: 'unit',
        label: '$i18n:automation.field.unit',
        type: 'select',
        required: true,
        options: [
          { label: '$i18n:automation.field.unit.seconds', value: 'seconds' },
          { label: '$i18n:automation.field.unit.minutes', value: 'minutes' },
          { label: '$i18n:automation.field.unit.hours', value: 'hours' },
          { label: '$i18n:automation.field.unit.days', value: 'days' },
        ],
      },
    ],
    defaultConfig: {
      controlType: 'delay',
      duration: 30,
      unit: 'minutes',
    },
  },
  {
    type: 'control-loop',
    label: '$i18n:automation.control.loop',
    icon: '🔁',
    category: 'control',
    description: '$i18n:automation.control.loop.desc',
    configSchema: [
      {
        key: 'collection',
        label: '$i18n:automation.field.collection',
        type: 'expression',
        required: true,
        placeholder: '${trigger.items}',
        description: '$i18n:automation.field.collection.desc',
      },
      {
        key: 'itemVariable',
        label: '$i18n:automation.field.itemVariable',
        type: 'text',
        placeholder: 'item',
        description: '$i18n:automation.field.itemVariable.desc',
      },
    ],
    defaultConfig: {
      controlType: 'loop',
      itemVariable: 'item',
    },
  },
];
