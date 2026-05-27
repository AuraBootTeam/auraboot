// web-admin/app/smart/automation/templates/automationTemplates.ts
import type { FlowData } from '~/plugins/core-designer/components/flow-designer-sdk';
import type { LocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';

/**
 * Automation Template
 *
 * Pre-built workflow definitions that users can select to bootstrap
 * a new automation. Each template contains a complete FlowData (nodes + edges)
 * with sensible defaults and placeholder expressions.
 *
 * Display text (name, description, category label) is stored as LocalizedText
 * so the framework renderer (useSmartText / I18nContext) resolves it per locale.
 * Search is locale-aware and matches against both en-US and zh-CN.
 */
export interface AutomationTemplate {
  id: string;
  name: LocalizedText;
  description: LocalizedText;
  category: 'sales' | 'operations' | 'notifications' | 'integrations';
  icon: string;
  flowData: FlowData;
  tags: string[];
}

// ---------------------------------------------------------------------------
// Helper: generate unique node/edge IDs per template (deterministic so
// templates are stable across renders).
// ---------------------------------------------------------------------------
let _counter = 0;
function nid(prefix: string) {
  return `tpl-${prefix}-${++_counter}`;
}

function resetCounter() {
  _counter = 0;
}

// Pick a single locale value from a LocalizedText with fallback chain.
// Used for search and for outbound payloads when a plain string is required.
export function resolveLocalizedText(
  text: LocalizedText | string | null | undefined,
  locale: string,
): string {
  if (text == null) return '';
  if (typeof text === 'string') return text;
  return text[locale] || text['zh-CN'] || text['en-US'] || '';
}

// ---------------------------------------------------------------------------
// Template definitions
// ---------------------------------------------------------------------------

function buildNewLeadNotification(): AutomationTemplate {
  resetCounter();
  const triggerId = nid('trigger');
  const actionId = nid('action');
  return {
    id: 'tpl-new-lead-notification',
    name: { 'en-US': 'New Lead Notification', 'zh-CN': '新线索通知' },
    description: {
      'en-US': 'Send an email notification to the sales team whenever a new lead is created.',
      'zh-CN': '当创建新线索时,向销售团队发送邮件通知。',
    },
    category: 'sales',
    icon: 'UserPlus',
    tags: ['crm', 'lead', 'email', 'notification'],
    flowData: {
      nodes: [
        {
          id: triggerId,
          type: 'trigger-record-create',
          position: { x: 300, y: 80 },
          data: {
            label: 'New Lead Created',
            config: {
              triggerType: 'on_record_create',
              modelCode: 'crm_lead',
            },
          },
        },
        {
          id: actionId,
          type: 'action-send-notification',
          position: { x: 300, y: 300 },
          data: {
            label: 'Notify Sales Team',
            config: {
              actionType: 'send_notification',
              notificationType: 'email',
              title: 'New Lead: ${trigger.record.name}',
              content:
                'A new lead "${trigger.record.name}" has been created. Source: ${trigger.record.source}. Please follow up.',
              recipients: '${trigger.record.owner}',
            },
          },
        },
      ],
      edges: [{ id: `${triggerId}-${actionId}`, source: triggerId, target: actionId }],
    },
  };
}

function buildOverdueTaskAlert(): AutomationTemplate {
  resetCounter();
  const triggerId = nid('trigger');
  const notifyId = nid('notify');
  const updateId = nid('update');
  return {
    id: 'tpl-overdue-task-alert',
    name: { 'en-US': 'Overdue Task Alert', 'zh-CN': '逾期任务预警' },
    description: {
      'en-US':
        'When a task stays in_progress for 24 hours, notify the assignee and escalate priority to HIGH.',
      'zh-CN': '任务处于"进行中"状态超过 24 小时时,通知负责人并将优先级升级为「高」。',
    },
    category: 'operations',
    icon: 'AlertTriangle',
    tags: ['task', 'overdue', 'escalation', 'priority'],
    flowData: {
      nodes: [
        {
          id: triggerId,
          type: 'trigger-scheduled',
          position: { x: 300, y: 80 },
          data: {
            label: 'Check Every Hour',
            config: {
              triggerType: 'scheduled',
              cron: '0 * * * *',
              timezone: 'Asia/Shanghai',
            },
          },
        },
        {
          id: notifyId,
          type: 'action-send-notification',
          position: { x: 150, y: 300 },
          data: {
            label: 'Notify Assignee',
            config: {
              actionType: 'send_notification',
              notificationType: 'in_app',
              title: 'Task Overdue: ${trigger.record.name}',
              content:
                'Your task "${trigger.record.name}" has been in progress for over 24 hours. Please update status.',
              recipients: '${trigger.record.assignee}',
            },
          },
        },
        {
          id: updateId,
          type: 'action-update-record',
          position: { x: 450, y: 300 },
          data: {
            label: 'Set Priority HIGH',
            config: {
              actionType: 'update_record',
              modelCode: 'task',
              recordId: '${trigger.recordId}',
              fields: '{ "priority": "high" }',
            },
          },
        },
      ],
      edges: [
        { id: `${triggerId}-${notifyId}`, source: triggerId, target: notifyId },
        { id: `${triggerId}-${updateId}`, source: triggerId, target: updateId },
      ],
    },
  };
}

function buildApprovalRequestRouter(): AutomationTemplate {
  resetCounter();
  const triggerId = nid('trigger');
  const conditionId = nid('condition');
  const notifyManagerId = nid('notify-mgr');
  const notifyDirectorId = nid('notify-dir');
  return {
    id: 'tpl-approval-request-router',
    name: { 'en-US': 'Approval Request Router', 'zh-CN': '审批请求路由' },
    description: {
      'en-US':
        'Route approval notifications based on amount: >10,000 goes to director, otherwise to manager.',
      'zh-CN': '根据金额路由审批通知:金额大于 10,000 通知总监,否则通知经理。',
    },
    category: 'operations',
    icon: 'GitBranch',
    tags: ['approval', 'routing', 'condition', 'workflow'],
    flowData: {
      nodes: [
        {
          id: triggerId,
          type: 'trigger-state-change',
          position: { x: 300, y: 80 },
          data: {
            label: 'Status -> Pending Approval',
            config: {
              triggerType: 'on_state_change',
              modelCode: '',
              stateField: 'status',
              toStates: ['pending_approval'],
            },
          },
        },
        {
          id: conditionId,
          type: 'control-condition',
          position: { x: 300, y: 280 },
          data: {
            label: 'Amount > 10,000?',
            config: {
              controlType: 'condition',
              expression: '${trigger.record.amount} > 10000',
            },
          },
        },
        {
          id: notifyDirectorId,
          type: 'action-send-notification',
          position: { x: 100, y: 500 },
          data: {
            label: 'Notify Director',
            config: {
              actionType: 'send_notification',
              notificationType: 'email',
              title: 'High-value Approval Required',
              content: 'A record with amount $${trigger.record.amount} requires your approval.',
              recipients: '${trigger.record.director}',
            },
          },
        },
        {
          id: notifyManagerId,
          type: 'action-send-notification',
          position: { x: 500, y: 500 },
          data: {
            label: 'Notify Manager',
            config: {
              actionType: 'send_notification',
              notificationType: 'email',
              title: 'Approval Required',
              content: 'A record requires your approval. Amount: $${trigger.record.amount}.',
              recipients: '${trigger.record.manager}',
            },
          },
        },
      ],
      edges: [
        { id: `${triggerId}-${conditionId}`, source: triggerId, target: conditionId },
        {
          id: `${conditionId}-${notifyDirectorId}`,
          source: conditionId,
          target: notifyDirectorId,
          sourceHandle: 'true',
          data: { label: 'Yes', condition: { type: 'expression', content: 'true' } },
        },
        {
          id: `${conditionId}-${notifyManagerId}`,
          source: conditionId,
          target: notifyManagerId,
          sourceHandle: 'false',
          data: { label: 'No', condition: { type: 'expression', content: 'false' } },
        },
      ],
    },
  };
}

function buildWelcomeEmail(): AutomationTemplate {
  resetCounter();
  const triggerId = nid('trigger');
  const actionId = nid('action');
  return {
    id: 'tpl-welcome-email',
    name: { 'en-US': 'Welcome Email', 'zh-CN': '欢迎邮件' },
    description: {
      'en-US': 'Automatically send a welcome email when a new contact is created.',
      'zh-CN': '当创建新联系人时,自动发送欢迎邮件。',
    },
    category: 'sales',
    icon: 'Mail',
    tags: ['contact', 'welcome', 'email', 'onboarding'],
    flowData: {
      nodes: [
        {
          id: triggerId,
          type: 'trigger-record-create',
          position: { x: 300, y: 80 },
          data: {
            label: 'New Contact Created',
            config: {
              triggerType: 'on_record_create',
              modelCode: 'contact',
            },
          },
        },
        {
          id: actionId,
          type: 'action-send-notification',
          position: { x: 300, y: 300 },
          data: {
            label: 'Send Welcome Email',
            config: {
              actionType: 'send_notification',
              notificationType: 'email',
              title: 'Welcome to ${tenant.name}!',
              content:
                'Hello ${trigger.record.name}, welcome aboard! We are excited to have you. Please do not hesitate to reach out if you need anything.',
              recipients: '${trigger.record.email}',
            },
          },
        },
      ],
      edges: [{ id: `${triggerId}-${actionId}`, source: triggerId, target: actionId }],
    },
  };
}

function buildDailyDigest(): AutomationTemplate {
  resetCounter();
  const triggerId = nid('trigger');
  const commandId = nid('command');
  const notifyId = nid('notify');
  return {
    id: 'tpl-daily-digest',
    name: { 'en-US': 'Daily Digest', 'zh-CN': '每日报告' },
    description: {
      'en-US': 'Generate a daily report at 9 AM on weekdays and send it to the team.',
      'zh-CN': '工作日每天上午 9 点生成日报并发送给团队。',
    },
    category: 'operations',
    icon: 'FileText',
    tags: ['scheduled', 'report', 'digest', 'daily'],
    flowData: {
      nodes: [
        {
          id: triggerId,
          type: 'trigger-scheduled',
          position: { x: 300, y: 80 },
          data: {
            label: 'Every Weekday 9 AM',
            config: {
              triggerType: 'scheduled',
              cron: '0 9 * * 1-5',
              timezone: 'Asia/Shanghai',
            },
          },
        },
        {
          id: commandId,
          type: 'action-execute-command',
          position: { x: 300, y: 300 },
          data: {
            label: 'Generate Report',
            config: {
              actionType: 'execute_command',
              commandCode: 'generate_daily_report',
              params: '{}',
            },
          },
        },
        {
          id: notifyId,
          type: 'action-send-notification',
          position: { x: 300, y: 520 },
          data: {
            label: 'Send to Team',
            config: {
              actionType: 'send_notification',
              notificationType: 'email',
              title: 'Daily Digest - ${date.today}',
              content:
                'Here is your daily digest for ${date.today}. See the attached report for details.',
              recipients: 'team-channel',
            },
          },
        },
      ],
      edges: [
        { id: `${triggerId}-${commandId}`, source: triggerId, target: commandId },
        { id: `${commandId}-${notifyId}`, source: commandId, target: notifyId },
      ],
    },
  };
}

function buildSlaBreachEscalation(): AutomationTemplate {
  resetCounter();
  const triggerId = nid('trigger');
  const updateId = nid('update');
  const notifyId = nid('notify');
  return {
    id: 'tpl-sla-breach-escalation',
    name: { 'en-US': 'SLA Breach Escalation', 'zh-CN': 'SLA 违约升级' },
    description: {
      'en-US':
        'When a support ticket stays open for 4+ hours, escalate to CRITICAL and notify the supervisor.',
      'zh-CN': '当支持工单未关闭超过 4 小时时,优先级升级为「紧急」并通知主管。',
    },
    category: 'operations',
    icon: 'ShieldAlert',
    tags: ['sla', 'support', 'escalation', 'ticket'],
    flowData: {
      nodes: [
        {
          id: triggerId,
          type: 'trigger-scheduled',
          position: { x: 300, y: 80 },
          data: {
            label: 'Check Every 30 Min',
            config: {
              triggerType: 'scheduled',
              cron: '*/30 * * * *',
              timezone: 'Asia/Shanghai',
            },
          },
        },
        {
          id: updateId,
          type: 'action-update-record',
          position: { x: 150, y: 300 },
          data: {
            label: 'Set Priority CRITICAL',
            config: {
              actionType: 'update_record',
              modelCode: 'support_ticket',
              recordId: '${trigger.recordId}',
              fields: '{ "priority": "critical" }',
            },
          },
        },
        {
          id: notifyId,
          type: 'action-send-notification',
          position: { x: 450, y: 300 },
          data: {
            label: 'Notify Supervisor',
            config: {
              actionType: 'send_notification',
              notificationType: 'in_app',
              title: 'SLA Breach: Ticket #${trigger.record.ticket_number}',
              content:
                'Support ticket #${trigger.record.ticket_number} has been open for over 4 hours. Escalated to CRITICAL.',
              recipients: '${trigger.record.supervisor}',
            },
          },
        },
      ],
      edges: [
        { id: `${triggerId}-${updateId}`, source: triggerId, target: updateId },
        { id: `${triggerId}-${notifyId}`, source: triggerId, target: notifyId },
      ],
    },
  };
}

function buildDataQualityCheck(): AutomationTemplate {
  resetCounter();
  const triggerId = nid('trigger');
  const commandId = nid('command');
  const conditionId = nid('condition');
  const notifyId = nid('notify');
  const createId = nid('create');
  return {
    id: 'tpl-data-quality-check',
    name: { 'en-US': 'Data Quality Check', 'zh-CN': '数据质量检查' },
    description: {
      'en-US':
        'Run nightly data validation. If errors are found, create an issue and notify the data team.',
      'zh-CN': '每夜运行数据验证。若发现错误,创建问题单并通知数据团队。',
    },
    category: 'operations',
    icon: 'CheckSquare',
    tags: ['data', 'quality', 'validation', 'scheduled'],
    flowData: {
      nodes: [
        {
          id: triggerId,
          type: 'trigger-scheduled',
          position: { x: 300, y: 60 },
          data: {
            label: 'Nightly at 2 AM',
            config: {
              triggerType: 'scheduled',
              cron: '0 2 * * *',
              timezone: 'Asia/Shanghai',
            },
          },
        },
        {
          id: commandId,
          type: 'action-execute-command',
          position: { x: 300, y: 260 },
          data: {
            label: 'Validate Data',
            config: {
              actionType: 'execute_command',
              commandCode: 'validate_data_quality',
              params: '{}',
            },
          },
        },
        {
          id: conditionId,
          type: 'control-condition',
          position: { x: 300, y: 460 },
          data: {
            label: 'Errors Found?',
            config: {
              controlType: 'condition',
              expression: '${previous.errorCount} > 0',
            },
          },
        },
        {
          id: createId,
          type: 'action-create-record',
          position: { x: 100, y: 680 },
          data: {
            label: 'Create Issue',
            config: {
              actionType: 'create_record',
              modelCode: 'issue',
              fields:
                '{ "title": "Data quality errors detected", "description": "${previous.errorSummary}", "priority": "high" }',
            },
          },
        },
        {
          id: notifyId,
          type: 'action-send-notification',
          position: { x: 500, y: 680 },
          data: {
            label: 'Notify Data Team',
            config: {
              actionType: 'send_notification',
              notificationType: 'email',
              title: 'Data Quality Alert: ${previous.errorCount} errors found',
              content:
                'The nightly data quality check found ${previous.errorCount} issues. An issue ticket has been created. Please review.',
              recipients: 'data-team',
            },
          },
        },
      ],
      edges: [
        { id: `${triggerId}-${commandId}`, source: triggerId, target: commandId },
        { id: `${commandId}-${conditionId}`, source: commandId, target: conditionId },
        {
          id: `${conditionId}-${createId}`,
          source: conditionId,
          target: createId,
          sourceHandle: 'true',
          data: { label: 'Yes', condition: { type: 'expression', content: 'true' } },
        },
        {
          id: `${conditionId}-${notifyId}`,
          source: conditionId,
          target: notifyId,
          sourceHandle: 'true',
          data: { label: 'Yes', condition: { type: 'expression', content: 'true' } },
        },
      ],
    },
  };
}

function buildWebhookIntegration(): AutomationTemplate {
  resetCounter();
  const triggerId = nid('trigger');
  const conditionId = nid('condition');
  const createId = nid('create');
  const notifyId = nid('notify');
  return {
    id: 'tpl-webhook-integration',
    name: { 'en-US': 'Webhook Integration', 'zh-CN': 'Webhook 集成' },
    description: {
      'en-US':
        'Receive external webhook, validate payload, create a record, and send a confirmation notification.',
      'zh-CN': '接收外部 webhook,校验负载,创建记录并发送确认通知。',
    },
    category: 'integrations',
    icon: 'Link',
    tags: ['webhook', 'integration', 'api', 'external'],
    flowData: {
      nodes: [
        {
          id: triggerId,
          type: 'trigger-webhook',
          position: { x: 300, y: 60 },
          data: {
            label: 'Receive Webhook',
            config: {
              triggerType: 'webhook',
              validationMode: 'signature',
              secret: '',
            },
          },
        },
        {
          id: conditionId,
          type: 'control-condition',
          position: { x: 300, y: 260 },
          data: {
            label: 'Valid Payload?',
            config: {
              controlType: 'condition',
              expression:
                '${trigger.payload.event} !== undefined && ${trigger.payload.data} !== undefined',
            },
          },
        },
        {
          id: createId,
          type: 'action-create-record',
          position: { x: 150, y: 480 },
          data: {
            label: 'Create Record',
            config: {
              actionType: 'create_record',
              modelCode: '',
              fields:
                '{ "name": "${trigger.payload.data.name}", "source": "webhook", "external_id": "${trigger.payload.data.id}" }',
            },
          },
        },
        {
          id: notifyId,
          type: 'action-send-notification',
          position: { x: 450, y: 480 },
          data: {
            label: 'Send Confirmation',
            config: {
              actionType: 'send_notification',
              notificationType: 'in_app',
              title: 'Webhook Record Created',
              content: 'A new record was created from webhook event "${trigger.payload.event}".',
              recipients: 'admin',
            },
          },
        },
      ],
      edges: [
        { id: `${triggerId}-${conditionId}`, source: triggerId, target: conditionId },
        {
          id: `${conditionId}-${createId}`,
          source: conditionId,
          target: createId,
          sourceHandle: 'true',
          data: { label: 'Valid', condition: { type: 'expression', content: 'true' } },
        },
        {
          id: `${createId}-${notifyId}`,
          source: createId,
          target: notifyId,
        },
      ],
    },
  };
}

// ---------------------------------------------------------------------------
// Exported template list
// ---------------------------------------------------------------------------

export const automationTemplates: AutomationTemplate[] = [
  buildNewLeadNotification(),
  buildOverdueTaskAlert(),
  buildApprovalRequestRouter(),
  buildWelcomeEmail(),
  buildDailyDigest(),
  buildSlaBreachEscalation(),
  buildDataQualityCheck(),
  buildWebhookIntegration(),
];

export interface TemplateCategoryEntry {
  key: 'all' | 'sales' | 'operations' | 'notifications' | 'integrations';
  label: LocalizedText;
}

/**
 * All available template categories
 */
export const templateCategories: readonly TemplateCategoryEntry[] = [
  { key: 'all', label: { 'en-US': 'All Templates', 'zh-CN': '全部模板' } },
  { key: 'sales', label: { 'en-US': 'Sales', 'zh-CN': '销售' } },
  { key: 'operations', label: { 'en-US': 'Operations', 'zh-CN': '运营' } },
  { key: 'notifications', label: { 'en-US': 'Notifications', 'zh-CN': '通知' } },
  { key: 'integrations', label: { 'en-US': 'Integrations', 'zh-CN': '集成' } },
] as const;

export type TemplateCategory = TemplateCategoryEntry['key'];

/**
 * Get a template by ID
 */
export function getTemplateById(id: string): AutomationTemplate | undefined {
  return automationTemplates.find((t) => t.id === id);
}

/**
 * Filter templates by category
 */
export function filterTemplatesByCategory(category: TemplateCategory): AutomationTemplate[] {
  if (category === 'all') return automationTemplates;
  return automationTemplates.filter((t) => t.category === category);
}

/**
 * Search templates by name, description (across both locales), or tags.
 */
export function searchTemplates(query: string): AutomationTemplate[] {
  const q = query.toLowerCase().trim();
  if (!q) return automationTemplates;
  return automationTemplates.filter((t) => {
    const nameMatches =
      (t.name['en-US'] || '').toLowerCase().includes(q) ||
      (t.name['zh-CN'] || '').toLowerCase().includes(q);
    const descMatches =
      (t.description['en-US'] || '').toLowerCase().includes(q) ||
      (t.description['zh-CN'] || '').toLowerCase().includes(q);
    const tagMatches = t.tags.some((tag) => tag.includes(q));
    return nameMatches || descMatches || tagMatches;
  });
}
