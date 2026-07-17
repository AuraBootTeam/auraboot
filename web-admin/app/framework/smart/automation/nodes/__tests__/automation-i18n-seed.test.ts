import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const REQUIRED_AUTOMATION_GROUP_KEYS = [
  'automation.group.triggerSource',
  'automation.group.filter',
  'automation.group.advanced',
  'automation.group.target',
  'automation.group.fieldsMapping',
  'automation.group.notification',
  'automation.group.request',
  'automation.group.process',
];

const REQUIRED_SHARED_EXPRESSION_KEYS = [
  'expression.mode.conditions',
  'expression.mode.expression',
  'expression.tooComplexForBuilder',
  'expression.fieldGroup.fields',
  'expression.action.addCondition',
  'expression.placeholder.selectField',
  'expression.placeholder.value',
  'expression.placeholder.formula',
  'expression.tooltip.toggleAndOr',
  'expression.tooltip.deleteCondition',
  'expression.variable.userId',
  'expression.variable.userName',
  'expression.variable.userEmail',
  'expression.variable.userRoles',
  'expression.variable.userPermissions',
  'expression.variable.formMode',
  'expression.variable.pageKind',
  'expression.variable.pageModelCode',
  'expression.variable.pageKey',
  'expression.variable.pageMode',
  'expression.variable.currentRecordPid',
  'expression.variable.recordPid',
  'expression.variable.activeFilters',
  'expression.variable.selectedRowPids',
  'expression.variable.decisionMatched',
  'expression.variable.decisionStatus',
  'expression.variable.processKey',
  'expression.variable.instanceId',
  'expression.variable.taskId',
  'expression.fieldGroup.currentRecord',
  'expression.fieldGroup.triggerSample',
  'expression.fieldGroup.ruleOutputs',
  'expression.fieldGroup.decisionRuntime',
  'expression.fieldGroup.bpmContext',
  'formula.functions',
  'formula.insertField',
  'formula.fieldPicker.title',
  'formula.fieldPicker.close',
  'formula.fieldPicker.quick',
  'formula.fieldPicker.quickFields',
  'formula.fieldPicker.empty',
  'formula.preview',
  'formula.previewing',
  'formula.previewFailed',
  'formula.category.all',
  'formula.placeholder',
  'formula.result',
  'formula.help',
];

const REQUIRED_FLOW_TOOLBAR_KEYS = [
  'flow.toolbar.save',
  'flow.toolbar.saving',
  'flow.toolbar.unsaved',
  'flow.toolbar.undo',
  'flow.toolbar.redo',
];

const REQUIRED_FLOW_AVAILABILITY_KEYS = [
  'flow.availability.unavailable',
  'flow.availability.providerStatus',
  'flow.availability.reasonFallback',
];

const REQUIRED_EXPORT_LABELS: Record<string, string> = {
  'automation.editor.export': '导出自动化',
  'flow.toolbar.import': '导入流程',
  'flow.toolbar.export': '导出流程',
};

const REQUIRED_RULE_CENTER_ACTION_KEYS = [
  'automation.action.sendSms',
  'automation.action.sendSms.desc',
  'automation.action.sendIm',
  'automation.action.sendIm.desc',
  'automation.action.createTask',
  'automation.action.createTask.desc',
  'automation.action.ccTask',
  'automation.action.ccTask.desc',
  'automation.action.addComment',
  'automation.action.addComment.desc',
  'automation.action.patchRecord',
  'automation.action.patchRecord.desc',
  'automation.action.writeAudit',
  'automation.action.writeAudit.desc',
  'automation.field.actionTarget',
  'automation.field.actionTarget.desc',
  'automation.field.smsTemplate',
  'automation.field.smsTemplate.desc',
  'automation.field.smsContent',
  'automation.field.imChannel',
  'automation.field.imChannel.desc',
  'automation.field.imContent',
  'automation.field.taskTitle',
  'automation.field.taskAssignee',
  'automation.field.taskAssignee.desc',
  'automation.field.taskDueDate',
  'automation.field.taskId',
  'automation.field.ccMessage',
  'automation.field.commentContent',
  'automation.field.commentMentions',
  'automation.field.patchFields',
  'automation.field.auditMessage',
  'automation.field.auditPayload',
];

const REQUIRED_AUTOMATION_RESULT_KEYS = [
  'automation.editor.openRuntimeTrace',
  'automation.editor.openUnifiedTrace',
  'automation.editor.decisionTrace',
  'automation.editor.decisionTraceSubtitle',
  'automation.editor.decisionCode',
  'automation.editor.decisionTraceId',
  'automation.editor.decisionMatched',
  'automation.editor.decisionNotMatched',
  'automation.editor.resultField.channel',
  'automation.editor.resultField.failureReason',
  'automation.editor.resultField.errorMessage',
  'automation.editor.resultField.targetUserIds',
  'automation.editor.resultField.assigneeUserIds',
  'automation.editor.resultField.conversationIds',
  'automation.editor.resultField.messageIds',
  'automation.editor.resultField.delivery',
  'automation.editor.resultField.itemType',
  'automation.editor.resultField.createdCount',
  'automation.editor.resultField.ccCount',
  'automation.editor.resultField.inboxItemIds',
  'automation.editor.resultField.commentPid',
  'automation.editor.resultField.auditPid',
  'automation.editor.resultField.ruleCode',
  'automation.editor.resultField.mentions',
  'automation.editor.resultValue.delivery.inbox',
  'automation.editor.resultValue.itemType.task',
  'automation.editor.resultValue.itemType.mention',
  'automation.editor.resultValue.failureReason.smsDeliveryFailed',
];

describe('Automation i18n seed coverage', () => {
  it('contains property-panel group labels so raw group codes do not leak in zh-CN', () => {
    const entries = JSON.parse(
      readFileSync('../plugins/platform-admin/config/i18n.json', 'utf8'),
    ) as Array<Record<string, string>>;
    const keys = new Set(entries.map((entry) => entry.key));

    for (const key of REQUIRED_AUTOMATION_GROUP_KEYS) {
      expect(keys, `${key} is missing from platform-admin i18n seed`).toContain(key);
      const entry = entries.find((item) => item.key === key);
      expect(entry?.['zh-CN'], `${key} must have a zh-CN label`).toBeTruthy();
      expect(entry?.['en-US'], `${key} must have an en-US label`).toBeTruthy();
    }
  });

  it('contains shared expression editor labels used inside automation property fields', () => {
    const entries = JSON.parse(
      readFileSync('../plugins/platform-admin/config/i18n.json', 'utf8'),
    ) as Array<Record<string, string>>;
    const keys = new Set(entries.map((entry) => entry.key));

    for (const key of REQUIRED_SHARED_EXPRESSION_KEYS) {
      expect(keys, `${key} is missing from platform-admin i18n seed`).toContain(key);
      const entry = entries.find((item) => item.key === key);
      expect(entry?.['zh-CN'], `${key} must have a zh-CN label`).toBeTruthy();
      expect(entry?.['en-US'], `${key} must have an en-US label`).toBeTruthy();
    }
  });

  it('contains shared designer toolbar labels used by automation editor', () => {
    const entries = JSON.parse(
      readFileSync('../plugins/platform-admin/config/i18n.json', 'utf8'),
    ) as Array<Record<string, string>>;
    const keys = new Set(entries.map((entry) => entry.key));

    for (const key of REQUIRED_FLOW_TOOLBAR_KEYS) {
      expect(keys, `${key} is missing from platform-admin i18n seed`).toContain(key);
      const entry = entries.find((item) => item.key === key);
      expect(entry?.['zh-CN'], `${key} must have a zh-CN label`).toBeTruthy();
      expect(entry?.['en-US'], `${key} must have an en-US label`).toBeTruthy();
    }
  });

  it('contains shared flow availability labels for action catalog provider status', () => {
    const entries = JSON.parse(
      readFileSync('../plugins/platform-admin/config/i18n.json', 'utf8'),
    ) as Array<Record<string, string>>;
    const keys = new Set(entries.map((entry) => entry.key));

    for (const key of REQUIRED_FLOW_AVAILABILITY_KEYS) {
      expect(keys, `${key} is missing from platform-admin i18n seed`).toContain(key);
      const entry = entries.find((item) => item.key === key);
      expect(entry?.['zh-CN'], `${key} must have a zh-CN label`).toBeTruthy();
      expect(entry?.['en-US'], `${key} must have an en-US label`).toBeTruthy();
    }
  });

  it('uses distinct export/import labels for automation shell vs embedded flow canvas', () => {
    const entries = JSON.parse(
      readFileSync('../plugins/platform-admin/config/i18n.json', 'utf8'),
    ) as Array<Record<string, string>>;

    for (const [key, zhLabel] of Object.entries(REQUIRED_EXPORT_LABELS)) {
      const entry = entries.find((item) => item.key === key);
      expect(entry?.['zh-CN'], `${key} must say what object is imported/exported`).toBe(zhLabel);
      expect(entry?.['en-US'], `${key} must have an en-US label`).toBeTruthy();
    }
  });

  it('contains labels for rule-center catalog action nodes exposed in Automation', () => {
    const entries = JSON.parse(
      readFileSync('../plugins/platform-admin/config/i18n.json', 'utf8'),
    ) as Array<Record<string, string>>;
    const keys = new Set(entries.map((entry) => entry.key));

    for (const key of REQUIRED_RULE_CENTER_ACTION_KEYS) {
      expect(keys, `${key} is missing from platform-admin i18n seed`).toContain(key);
      const entry = entries.find((item) => item.key === key);
      expect(entry?.['zh-CN'], `${key} must have a zh-CN label`).toBeTruthy();
      expect(entry?.['en-US'], `${key} must have an en-US label`).toBeTruthy();
    }
  });

  it('contains automation run result trace labels so decision traces do not fall back to raw keys', () => {
    const entries = JSON.parse(
      readFileSync('../plugins/platform-admin/config/i18n.json', 'utf8'),
    ) as Array<Record<string, string>>;
    const keys = new Set(entries.map((entry) => entry.key));

    for (const key of REQUIRED_AUTOMATION_RESULT_KEYS) {
      expect(keys, `${key} is missing from platform-admin i18n seed`).toContain(key);
      const entry = entries.find((item) => item.key === key);
      expect(entry?.['zh-CN'], `${key} must have a zh-CN label`).toBeTruthy();
      expect(entry?.['en-US'], `${key} must have an en-US label`).toBeTruthy();
    }
  });
});
