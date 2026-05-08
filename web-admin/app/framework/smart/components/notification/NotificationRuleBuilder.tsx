import { useState, useEffect } from 'react';
import { post, put, get, ErrorCodes } from '~/shared/services/http-client';
import {
  BellIcon,
  ClockIcon,
  BoltIcon,
  FunnelIcon,
  MegaphoneIcon,
  PlusIcon,
  TrashIcon,
  SparklesIcon,
} from '@heroicons/react/24/outline';

// ============================================================================
// Types
// ============================================================================

export interface NotificationRule {
  id?: number;
  code: string;
  name: string;
  description?: string;
  enabled: boolean;
  triggerType: 'event' | 'scheduled';
  triggerConfig?: string;
  conditionModelCode?: string;
  conditionFilter?: string;
  actionChannel?: string;
  actionTemplateCode?: string;
  recipientType?: string;
  recipientField?: string;
  sendCount?: number;
  lastEvaluatedAt?: string;
  createdAt?: string;
}

interface FilterRow {
  fieldName: string;
  operator: string;
  value: string;
}

interface ModelOption {
  code: string;
  name: string;
}

interface Props {
  initial?: NotificationRule | null;
  onSaved: (rule: NotificationRule) => void;
  onCancel: () => void;
}

// ============================================================================
// Preset templates
// ============================================================================

const PRESET_TEMPLATES: Array<{
  label: string;
  icon: string;
  rule: Partial<NotificationRule>;
}> = [
  {
    label: '逾期付款提醒',
    icon: '💰',
    rule: {
      code: 'overdue-payment-alert',
      name: '逾期付款提醒',
      description: 'Daily check for AR invoices past due date',
      triggerType: 'scheduled',
      triggerConfig: JSON.stringify({ schedule: 'daily', hour: 9, minute: 0 }),
      conditionModelCode: 'fin_ar_invoice',
      conditionFilter: JSON.stringify([
        { fieldName: 'due_date', operator: 'LT', value: 'today' },
        { fieldName: 'status', operator: 'NE', value: 'paid' },
      ]),
      actionChannel: 'in_app',
      recipientType: 'operator',
    },
  },
  {
    label: '低库存预警',
    icon: '📦',
    rule: {
      code: 'low-stock-warning',
      name: '低库存预警',
      description: 'Daily check for items with quantity below threshold',
      triggerType: 'scheduled',
      triggerConfig: JSON.stringify({ schedule: 'daily', hour: 8, minute: 0 }),
      conditionModelCode: 'inv_item',
      conditionFilter: JSON.stringify([{ fieldName: 'quantity', operator: 'LT', value: '10' }]),
      actionChannel: 'in_app',
      recipientType: 'operator',
    },
  },
  {
    label: '审批超时提醒',
    icon: '⏰',
    rule: {
      code: 'approval-overdue-reminder',
      name: '审批超时提醒',
      description: 'Daily check for pending approvals older than 3 days',
      triggerType: 'scheduled',
      triggerConfig: JSON.stringify({ schedule: 'daily', hour: 10, minute: 0 }),
      conditionModelCode: 'bpm_task',
      conditionFilter: JSON.stringify([{ fieldName: 'status', operator: 'EQ', value: 'pending' }]),
      actionChannel: 'in_app',
      recipientType: 'record_owner',
    },
  },
];

// ============================================================================
// Constants
// ============================================================================

const TRIGGER_TYPES = [
  {
    value: 'scheduled',
    label: '定时触发',
    description: '按固定时间周期执行规则',
    icon: <ClockIcon className="h-5 w-5" />,
  },
  {
    value: 'event',
    label: '事件触发',
    description: '当记录发生变化时执行规则',
    icon: <BoltIcon className="h-5 w-5" />,
  },
];

const SCHEDULE_OPTIONS = [
  { value: 'hourly', label: '每小时' },
  { value: 'daily', label: '每天' },
  { value: 'weekly', label: '每周' },
];

const EVENT_OPTIONS = [
  { value: 'created', label: '记录创建' },
  { value: 'updated', label: '记录更新' },
  { value: 'deleted', label: '记录删除' },
];

const CHANNEL_OPTIONS = [
  { value: 'in_app', label: '站内消息' },
  { value: 'email', label: '邮件' },
  { value: 'webhook', label: 'Webhook' },
];

const RECIPIENT_OPTIONS = [
  { value: 'operator', label: '操作人' },
  { value: 'record_owner', label: '记录负责人' },
  { value: 'specific_users', label: '指定用户' },
];

const FILTER_OPERATORS = [
  { value: 'EQ', label: '等于' },
  { value: 'NE', label: '不等于' },
  { value: 'GT', label: '大于' },
  { value: 'GE', label: '大于等于' },
  { value: 'LT', label: '小于' },
  { value: 'LE', label: '小于等于' },
  { value: 'like', label: '包含' },
  { value: 'is_null', label: '为空' },
  { value: 'is_not_null', label: '不为空' },
];

// ============================================================================
// Helpers
// ============================================================================

function parseFilters(json?: string): FilterRow[] {
  if (!json) return [];
  try {
    const arr = JSON.parse(json);
    if (Array.isArray(arr)) return arr as FilterRow[];
  } catch {
    // ignore
  }
  return [];
}

function parseTriggerConfig(json?: string): Record<string, string> {
  if (!json) return {};
  try {
    return JSON.parse(json) ?? {};
  } catch {
    return {};
  }
}

// ============================================================================
// Component
// ============================================================================

export default function NotificationRuleBuilder({ initial, onSaved, onCancel }: Props) {
  const isEdit = !!initial?.id;

  // Form state
  const [code, setCode] = useState(initial?.code ?? '');
  const [name, setName] = useState(initial?.name ?? '');
  const [description, setDescription] = useState(initial?.description ?? '');
  const [enabled, setEnabled] = useState(initial?.enabled ?? true);
  const [triggerType, setTriggerType] = useState<'event' | 'scheduled'>(
    initial?.triggerType ?? 'scheduled',
  );
  const [schedule, setSchedule] = useState(
    parseTriggerConfig(initial?.triggerConfig).schedule ?? 'daily',
  );
  const [eventType, setEventType] = useState(
    parseTriggerConfig(initial?.triggerConfig).event ?? 'created',
  );
  const [conditionModel, setConditionModel] = useState(initial?.conditionModelCode ?? '');
  const [filters, setFilters] = useState<FilterRow[]>(parseFilters(initial?.conditionFilter));
  const [channel, setChannel] = useState(initial?.actionChannel ?? 'in_app');
  const [templateCode, setTemplateCode] = useState(initial?.actionTemplateCode ?? '');
  const [recipientType, setRecipientType] = useState(initial?.recipientType ?? 'operator');
  const [recipientField, setRecipientField] = useState(initial?.recipientField ?? '');

  // UI state
  const [models, setModels] = useState<ModelOption[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showPresets, setShowPresets] = useState(!isEdit);

  // Load model options
  useEffect(() => {
    get<{ records: ModelOption[]; total: number }>('/api/meta/models?size=200&page=1')
      .then((result) => {
        if (result.code === ErrorCodes.SUCCESS && result.data) {
          const raw = result.data as any;
          const list = raw.records ?? raw.data?.records ?? [];
          setModels(
            list.map((m: any) => ({ code: m.code, name: m.name || m.displayName || m.code })),
          );
        }
      })
      .catch(() => {});
  }, []);

  // Apply preset template
  const applyPreset = (preset: (typeof PRESET_TEMPLATES)[0]) => {
    const r = preset.rule;
    if (r.code) setCode(r.code);
    if (r.name) setName(r.name);
    if (r.description) setDescription(r.description);
    if (r.triggerType) setTriggerType(r.triggerType);
    if (r.triggerConfig) {
      const cfg = parseTriggerConfig(r.triggerConfig);
      if (cfg.schedule) setSchedule(cfg.schedule);
      if (cfg.event) setEventType(cfg.event);
    }
    if (r.conditionModelCode) setConditionModel(r.conditionModelCode);
    if (r.conditionFilter) setFilters(parseFilters(r.conditionFilter));
    if (r.actionChannel) setChannel(r.actionChannel);
    if (r.recipientType) setRecipientType(r.recipientType);
    setShowPresets(false);
  };

  // Filter management
  const addFilter = () => {
    setFilters((prev) => [...prev, { fieldName: '', operator: 'EQ', value: '' }]);
  };

  const updateFilter = (index: number, key: keyof FilterRow, value: string) => {
    setFilters((prev) => prev.map((f, i) => (i === index ? { ...f, [key]: value } : f)));
  };

  const removeFilter = (index: number) => {
    setFilters((prev) => prev.filter((_, i) => i !== index));
  };

  // Build payload from form state
  const buildPayload = (): Omit<NotificationRule, 'id'> => {
    const triggerConfig =
      triggerType === 'scheduled'
        ? JSON.stringify({ schedule })
        : JSON.stringify({ event: eventType });

    const conditionFilter =
      filters.length > 0
        ? JSON.stringify(filters.filter((f) => f.fieldName.trim() !== ''))
        : undefined;

    return {
      code: code.trim(),
      name: name.trim(),
      description: description.trim() || undefined,
      enabled,
      triggerType,
      triggerConfig,
      conditionModelCode: conditionModel || undefined,
      conditionFilter,
      actionChannel: channel,
      actionTemplateCode: templateCode.trim() || undefined,
      recipientType,
      recipientField: recipientField.trim() || undefined,
    };
  };

  const handleSave = async () => {
    if (!code.trim()) {
      setError('规则代码不能为空');
      return;
    }
    if (!name.trim()) {
      setError('规则名称不能为空');
      return;
    }
    setError(null);
    setSaving(true);
    try {
      const payload = buildPayload();
      let result;
      if (isEdit && initial?.id) {
        result = await put<NotificationRule>(`/api/notification-rules/${initial.id}`, payload);
      } else {
        result = await post<NotificationRule>('/api/notification-rules', payload);
      }
      if (result.code === ErrorCodes.SUCCESS && result.data) {
        onSaved(result.data);
      } else {
        setError(result.message ?? '保存失败，请重试');
      }
    } catch (e: any) {
      setError(e.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      {/* Preset templates (shown for new rules) */}
      {showPresets && !isEdit && (
        <div className="rounded-xl border border-indigo-200 bg-indigo-50 p-4 dark:border-indigo-700 dark:bg-indigo-900/20">
          <div className="mb-3 flex items-center gap-2">
            <SparklesIcon className="h-4 w-4 text-indigo-500" />
            <span className="text-sm font-medium text-indigo-700 dark:text-indigo-300">
              快速开始 — 选择模板
            </span>
          </div>
          <div className="grid grid-cols-3 gap-2">
            {PRESET_TEMPLATES.map((preset) => (
              <button
                key={preset.rule.code}
                onClick={() => applyPreset(preset)}
                className="flex items-center gap-2 rounded-lg border border-indigo-200 bg-white px-3 py-2 text-left text-sm transition-colors hover:border-indigo-400 hover:bg-indigo-50 dark:border-indigo-700 dark:bg-gray-800 dark:hover:bg-indigo-900/30"
              >
                <span>{preset.icon}</span>
                <span className="text-gray-700 dark:text-gray-300">{preset.label}</span>
              </button>
            ))}
          </div>
          <button
            onClick={() => setShowPresets(false)}
            className="mt-2 text-xs text-indigo-500 hover:text-indigo-700 dark:hover:text-indigo-300"
          >
            从空白创建 →
          </button>
        </div>
      )}

      {/* Basic Info */}
      <Section title="基础信息" icon={<BellIcon className="h-4 w-4" />}>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
              规则代码 <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              placeholder="my-rule-code"
              disabled={isEdit}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500 disabled:cursor-not-allowed disabled:opacity-50 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
              规则名称 <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="规则显示名称"
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
            />
          </div>
        </div>
        <div className="mt-3">
          <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
            描述
          </label>
          <input
            type="text"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            placeholder="可选描述..."
            className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-indigo-500 focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
          />
        </div>
        <div className="mt-3 flex items-center gap-2">
          <label className="relative inline-flex cursor-pointer items-center">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(e) => setEnabled(e.target.checked)}
              className="peer sr-only"
            />
            <div className="peer h-5 w-9 rounded-full bg-gray-200 peer-checked:bg-indigo-600 peer-focus:ring-2 peer-focus:ring-indigo-500 peer-focus:outline-none after:absolute after:top-[2px] after:left-[2px] after:h-4 after:w-4 after:rounded-full after:border after:border-gray-300 after:bg-white after:transition-all after:content-[''] peer-checked:after:translate-x-full peer-checked:after:border-white dark:border-gray-600 dark:bg-gray-700 dark:peer-focus:ring-indigo-600" />
          </label>
          <span className="text-sm text-gray-600 dark:text-gray-400">启用规则</span>
        </div>
      </Section>

      {/* Trigger */}
      <Section title="触发方式" icon={<ClockIcon className="h-4 w-4" />}>
        <div className="grid grid-cols-2 gap-3">
          {TRIGGER_TYPES.map((t) => (
            <button
              key={t.value}
              onClick={() => setTriggerType(t.value as 'event' | 'scheduled')}
              className={`flex items-start gap-3 rounded-lg border-2 p-3 text-left transition-colors ${
                triggerType === t.value
                  ? 'border-indigo-500 bg-indigo-50 dark:bg-indigo-900/20'
                  : 'border-gray-200 hover:border-gray-300 dark:border-gray-600 dark:hover:border-gray-500'
              }`}
            >
              <span
                className={`mt-0.5 ${triggerType === t.value ? 'text-indigo-500' : 'text-gray-400'}`}
              >
                {t.icon}
              </span>
              <div>
                <div
                  className={`text-sm font-medium ${triggerType === t.value ? 'text-indigo-700 dark:text-indigo-300' : 'text-gray-700 dark:text-gray-300'}`}
                >
                  {t.label}
                </div>
                <div className="mt-0.5 text-xs text-gray-500 dark:text-gray-400">
                  {t.description}
                </div>
              </div>
            </button>
          ))}
        </div>

        <div className="mt-4">
          {triggerType === 'scheduled' ? (
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                执行频率
              </label>
              <select
                value={schedule}
                onChange={(e) => setSchedule(e.target.value)}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              >
                {SCHEDULE_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                触发事件
              </label>
              <select
                value={eventType}
                onChange={(e) => setEventType(e.target.value)}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              >
                {EVENT_OPTIONS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
            </div>
          )}
        </div>
      </Section>

      {/* Condition */}
      <Section title="触发条件" icon={<FunnelIcon className="h-4 w-4" />}>
        <div className="mb-3">
          <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
            数据模型
          </label>
          <select
            value={conditionModel}
            onChange={(e) => setConditionModel(e.target.value)}
            className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
          >
            <option value="">-- 选择模型 --</option>
            {models.map((m) => (
              <option key={m.code} value={m.code}>
                {m.name} ({m.code})
              </option>
            ))}
          </select>
        </div>

        {/* Filter rows */}
        <div className="space-y-2">
          {filters.map((filter, index) => (
            <div key={index} className="flex items-center gap-2">
              <input
                type="text"
                value={filter.fieldName}
                onChange={(e) => updateFilter(index, 'fieldName', e.target.value)}
                placeholder="字段名"
                className="flex-1 rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900 focus:ring-1 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              />
              <select
                value={filter.operator}
                onChange={(e) => updateFilter(index, 'operator', e.target.value)}
                className="w-28 rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900 focus:ring-1 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              >
                {FILTER_OPERATORS.map((o) => (
                  <option key={o.value} value={o.value}>
                    {o.label}
                  </option>
                ))}
              </select>
              <input
                type="text"
                value={filter.value}
                onChange={(e) => updateFilter(index, 'value', e.target.value)}
                placeholder="值"
                disabled={['is_null', 'is_not_null'].includes(filter.operator)}
                className="flex-1 rounded border border-gray-300 bg-white px-2 py-1.5 text-sm text-gray-900 focus:ring-1 focus:ring-indigo-500 disabled:opacity-40 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              />
              <button
                onClick={() => removeFilter(index)}
                className="rounded p-1.5 text-red-400 transition-colors hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-900/20 dark:hover:text-red-400"
              >
                <TrashIcon className="h-4 w-4" />
              </button>
            </div>
          ))}
        </div>

        <button
          onClick={addFilter}
          className="mt-2 inline-flex items-center gap-1.5 rounded-lg bg-indigo-50 px-3 py-1.5 text-sm text-indigo-600 transition-colors hover:bg-indigo-100 dark:bg-indigo-900/20 dark:text-indigo-400 dark:hover:bg-indigo-900/30"
        >
          <PlusIcon className="h-3.5 w-3.5" />
          添加条件
        </button>
      </Section>

      {/* Action */}
      <Section title="通知动作" icon={<MegaphoneIcon className="h-4 w-4" />}>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
              通知渠道
            </label>
            <select
              value={channel}
              onChange={(e) => setChannel(e.target.value)}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
            >
              {CHANNEL_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
              消息模板代码
            </label>
            <input
              type="text"
              value={templateCode}
              onChange={(e) => setTemplateCode(e.target.value)}
              placeholder="template-code（可选）"
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
            />
          </div>
          <div>
            <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
              接收人策略
            </label>
            <select
              value={recipientType}
              onChange={(e) => setRecipientType(e.target.value)}
              className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
            >
              {RECIPIENT_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>
          {(recipientType === 'record_owner' || recipientType === 'specific_users') && (
            <div>
              <label className="mb-1 block text-xs font-medium text-gray-600 dark:text-gray-400">
                {recipientType === 'record_owner' ? '负责人字段名' : '用户ID（逗号分隔）'}
              </label>
              <input
                type="text"
                value={recipientField}
                onChange={(e) => setRecipientField(e.target.value)}
                placeholder={recipientType === 'record_owner' ? 'owner_id' : '101,102,103'}
                className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
              />
            </div>
          )}
        </div>
      </Section>

      {/* Error */}
      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600 dark:border-red-700 dark:bg-red-900/20 dark:text-red-400">
          {error}
        </div>
      )}

      {/* Footer actions */}
      <div className="flex items-center justify-end gap-3 border-t border-gray-200 pt-2 dark:border-gray-700">
        <button
          onClick={onCancel}
          className="rounded-lg bg-gray-100 px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-200 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
        >
          取消
        </button>
        <button
          onClick={handleSave}
          disabled={saving}
          className="rounded-lg bg-indigo-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-indigo-700 disabled:opacity-60"
        >
          {saving ? '保存中...' : isEdit ? '更新规则' : '创建规则'}
        </button>
      </div>
    </div>
  );
}

// ============================================================================
// Section helper
// ============================================================================

function Section({
  title,
  icon,
  children,
}: {
  title: string;
  icon: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <div className="rounded-xl border border-gray-200 bg-white p-5 dark:border-gray-700 dark:bg-gray-800">
      <div className="mb-4 flex items-center gap-2">
        <span className="text-indigo-500">{icon}</span>
        <h3 className="text-sm font-semibold text-gray-800 dark:text-gray-200">{title}</h3>
      </div>
      {children}
    </div>
  );
}
