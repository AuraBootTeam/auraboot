/**
 * Smart Component Runtime Manifest
 * Defines runtime module paths and export names for components.
 */

import type { ComponentRuntimeConfig } from '~/framework/meta/registry/components/ComponentConfig';

const SMART_COMPONENTS_BASE = '../../../../ui/smart';

function runtime(
  subdir: string,
  componentName: string,
  options?: {
    exportName?: string;
    aliases?: string[];
  },
): ComponentRuntimeConfig {
  return {
    modulePath: `${SMART_COMPONENTS_BASE}/${subdir}/${componentName}.tsx`,
    componentName,
    exportName: options?.exportName ?? componentName,
    aliases: options?.aliases ?? [],
  };
}

export const COMPONENT_RUNTIME_MANIFEST: Record<string, ComponentRuntimeConfig> = {
  input: runtime('form', 'Input', { aliases: ['SmartInput', 'Input', 'input'] }),
  textarea: runtime('form', 'Textarea', {
    aliases: ['SmartTextarea', 'Textarea', 'textarea'],
  }),
  select: runtime('form', 'Select', { aliases: ['SmartSelect', 'Select', 'select'] }),
  checkbox: runtime('form', 'Checkbox', {
    aliases: ['SmartCheckbox', 'Checkbox', 'checkbox'],
  }),
  radio: runtime('form', 'Radio', { aliases: ['SmartRadio', 'Radio', 'radio'] }),
  datepicker: runtime('datetime', 'DatePicker', {
    aliases: ['SmartDatePicker', 'DatePicker', 'datepicker', 'datePicker'],
  }),
  multiselect: runtime('form', 'MultiSelect', {
    aliases: ['SmartMultiSelect', 'MultiSelect', 'multiselect'],
  }),
  treeselect: runtime('picker', 'TreeSelect', {
    aliases: ['SmartTreeSelect', 'TreeSelect', 'treeselect'],
  }),
  timerangepicker: runtime('datetime', 'TimeRangePicker', {
    aliases: ['SmartTimeRangePicker', 'TimeRangePicker', 'timerangepicker'],
  }),
  daterange: runtime('datetime', 'DateRange', {
    aliases: ['SmartDateRange', 'DateRange', 'daterange', 'dateRange'],
  }),
  userselect: runtime('picker', 'UserSelect', {
    aliases: ['SmartUserSelect', 'UserSelect', 'userselect'],
  }),
  organizationselect: runtime('picker', 'OrganizationSelect', {
    aliases: ['SmartOrganizationSelect', 'OrganizationSelect', 'organizationselect'],
  }),
  coordinatespicker: runtime('picker', 'CoordinatesPicker', {
    aliases: ['SmartCoordinatesPicker', 'CoordinatesPicker', 'coordinatespicker'],
  }),
  cascadeselect: runtime('picker', 'CascadeSelect', {
    aliases: ['CascadeSelect', 'cascadeSelect', 'cascadeselect'],
  }),
  numberinput: runtime('form', 'NumberInput', {
    aliases: ['SmartNumberInput', 'NumberInput', 'numberinput', 'number'],
  }),
  switch: runtime('form', 'Switch', { aliases: ['SmartSwitch', 'Switch', 'switch'] }),
  upload: runtime('form', 'Upload', {
    aliases: ['SmartUpload', 'Upload', 'upload', 'fileupload'],
  }),
  richtext: runtime('form', 'RichTextEditor', {
    aliases: ['SmartRichTextEditor', 'RichTextEditor', 'richtext', 'rich_text'],
  }),
  timepicker: runtime('datetime', 'TimePicker', {
    aliases: ['SmartTimePicker', 'TimePicker', 'timepicker'],
  }),
  formref: runtime('form', 'FormRef', { aliases: ['SmartFormRef', 'FormRef', 'formref'] }),
  display: runtime('display', 'Display', { aliases: ['SmartDisplay', 'Display', 'display'] }),
  imagedisplay: runtime('display', 'ImageDisplay', {
    aliases: ['SmartImageDisplay', 'ImageDisplay', 'imagedisplay'],
  }),
  table: runtime('display', 'Table', { aliases: ['SmartTable', 'Table', 'table'] }),
  list: runtime('display', 'List', { aliases: ['SmartList', 'List', 'list'] }),
  button: runtime('interaction', 'Button', { aliases: ['SmartButton', 'Button', 'button'] }),
  navigation: runtime('interaction', 'Navigation', {
    aliases: ['SmartNavigation', 'Navigation', 'navigation'],
  }),
  form: runtime('layout', 'Form', { aliases: ['SmartForm', 'Form', 'form'] }),
  layout: runtime('layout', 'Layout', { aliases: ['SmartLayout', 'Layout', 'layout'] }),
  date: runtime('datetime', 'Date', { aliases: ['SmartDate', 'Date', 'date'] }),
  datetime: runtime('datetime', 'Datetime', {
    aliases: ['SmartDatetime', 'Datetime', 'datetime'],
  }),
  memberpicker: runtime('picker', 'MemberPicker', {
    aliases: ['MemberPicker', 'memberpicker', 'member_picker'],
  }),
  ratingfield: runtime('display', 'RatingField', {
    aliases: ['RatingField', 'ratingfield', 'rating'],
  }),
  progressfield: runtime('display', 'ProgressField', {
    aliases: ['ProgressField', 'progressfield', 'progress'],
  }),
  aifield: runtime('form', 'AiField', {
    aliases: ['AiField', 'aifield', 'ai_input', 'ai_text'],
  }),
  agenttoolpicker: runtime('form', 'AgentToolPicker', {
    aliases: ['SmartAgentToolPicker', 'AgentToolPicker', 'agenttoolpicker', 'agent_tool_picker'],
  }),
  guardrailseditor: runtime('form', 'GuardrailsEditor', {
    aliases: ['SmartGuardrailsEditor', 'GuardrailsEditor', 'guardrailseditor', 'guardrails_editor'],
  }),
  moneyinput: runtime('form', 'MoneyInput', {
    aliases: ['SmartMoneyInput', 'MoneyInput', 'moneyinput', 'money'],
  }),
  colorpicker: runtime('form', 'ColorPickerField', {
    aliases: ['ColorPickerField', 'colorpicker', 'color_picker', 'color'],
  }),
  fileattachment: runtime('display', 'FileAttachmentField', {
    aliases: ['FileAttachmentField', 'fileattachment', 'file_attachment', 'attachment'],
  }),
  addressfield: runtime('picker', 'AddressField', {
    aliases: ['AddressField', 'address_field', 'address'],
  }),
};
