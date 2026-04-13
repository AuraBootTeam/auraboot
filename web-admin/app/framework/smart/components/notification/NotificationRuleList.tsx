import { useState, useCallback } from 'react';
import { del, post, put, ErrorCodes } from '~/services/http-client';
import {
  PencilIcon,
  TrashIcon,
  BeakerIcon,
  ClockIcon,
  BoltIcon,
  CheckCircleIcon,
  XCircleIcon,
} from '@heroicons/react/24/outline';
import type { NotificationRule } from './NotificationRuleBuilder';

// ============================================================================
// Types
// ============================================================================

interface TestResult {
  success: boolean;
  matchedCount: number;
  summary: string;
  error?: string;
}

interface Props {
  rules: NotificationRule[];
  loading: boolean;
  onEdit: (rule: NotificationRule) => void;
  onDeleted: (id: number) => void;
  onToggled: (rule: NotificationRule) => void;
}

// ============================================================================
// Helpers
// ============================================================================

const CHANNEL_LABELS: Record<string, string> = {
  in_app: '站内消息',
  email: '邮件',
  webhook: 'Webhook',
};

const CHANNEL_COLORS: Record<string, string> = {
  in_app: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
  email: 'bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400',
  webhook: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400',
};

function TriggerBadge({ type }: { type: string }) {
  if (type === 'scheduled') {
    return (
      <span className="inline-flex items-center gap-1 rounded bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700 dark:bg-green-900/30 dark:text-green-400">
        <ClockIcon className="h-3 w-3" />
        定时
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 rounded bg-yellow-100 px-2 py-0.5 text-xs font-medium text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400">
      <BoltIcon className="h-3 w-3" />
      事件
    </span>
  );
}

function ChannelBadge({ channel }: { channel?: string }) {
  if (!channel) return null;
  const normalizedChannel = channel.toLowerCase();
  return (
    <span
      className={`inline-flex items-center rounded px-2 py-0.5 text-xs font-medium ${
        CHANNEL_COLORS[normalizedChannel] ??
        'bg-gray-100 text-gray-600 dark:bg-gray-700 dark:text-gray-400'
      }`}
    >
      {CHANNEL_LABELS[normalizedChannel] ?? normalizedChannel}
    </span>
  );
}

// ============================================================================
// Component
// ============================================================================

export default function NotificationRuleList({
  rules,
  loading,
  onEdit,
  onDeleted,
  onToggled,
}: Props) {
  const [deletingId, setDeletingId] = useState<number | null>(null);
  const [testingId, setTestingId] = useState<number | null>(null);
  const [testResults, setTestResults] = useState<Record<number, TestResult>>({});
  const [togglingId, setTogglingId] = useState<number | null>(null);

  const handleDelete = useCallback(
    async (rule: NotificationRule) => {
      if (!rule.id) return;
      if (!window.confirm(`确认删除规则「${rule.name}」？此操作不可撤销。`)) return;
      setDeletingId(rule.id);
      try {
        const result = await del(`/api/notification-rules/${rule.id}`);
        if (result.code === ErrorCodes.SUCCESS) {
          onDeleted(rule.id);
        } else {
          alert('删除失败，请重试');
        }
      } catch {
        alert('删除失败，请重试');
      } finally {
        setDeletingId(null);
      }
    },
    [onDeleted],
  );

  const handleTest = useCallback(async (rule: NotificationRule) => {
    if (!rule.id) return;
    setTestingId(rule.id);
    setTestResults((prev) => {
      const next = { ...prev };
      delete next[rule.id!];
      return next;
    });
    try {
      const result = await post<TestResult>(`/api/notification-rules/${rule.id}/test`, {});
      if (result.code === ErrorCodes.SUCCESS && result.data) {
        setTestResults((prev) => ({ ...prev, [rule.id!]: result.data! }));
      }
    } catch {
      setTestResults((prev) => ({
        ...prev,
        [rule.id!]: { success: false, matchedCount: 0, summary: '', error: '测试失败' },
      }));
    } finally {
      setTestingId(null);
    }
  }, []);

  const handleToggle = useCallback(
    async (rule: NotificationRule) => {
      if (!rule.id) return;
      setTogglingId(rule.id);
      try {
        const newEnabled = !rule.enabled;
        const result = await put<NotificationRule>(`/api/notification-rules/${rule.id}/toggle`, {
          enabled: newEnabled,
        });
        if (result.code === ErrorCodes.SUCCESS && result.data) {
          onToggled(result.data);
        }
      } catch {
        // ignore
      } finally {
        setTogglingId(null);
      }
    },
    [onToggled],
  );

  if (loading) {
    return (
      <div className="space-y-3">
        {[1, 2, 3].map((i) => (
          <div key={i} className="h-16 animate-pulse rounded-lg bg-gray-100 dark:bg-gray-700" />
        ))}
      </div>
    );
  }

  if (rules.length === 0) {
    return (
      <div className="rounded-xl border border-gray-200 bg-white py-12 text-center dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-gray-100 dark:bg-gray-700">
          <ClockIcon className="h-6 w-6 text-gray-400" />
        </div>
        <p className="text-sm text-gray-500 dark:text-gray-400">暂无通知规则</p>
        <p className="mt-1 text-xs text-gray-400 dark:text-gray-500">
          点击右上角「创建规则」开始配置
        </p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {rules.map((rule) => {
        const testResult = rule.id ? testResults[rule.id] : undefined;
        return (
          <div
            key={rule.id ?? rule.code}
            className="overflow-hidden rounded-xl border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800"
          >
            <div className="flex items-center gap-4 px-4 py-4">
              {/* Enabled toggle */}
              <div className="flex-shrink-0" title={rule.enabled ? '点击禁用' : '点击启用'}>
                <button
                  onClick={() => handleToggle(rule)}
                  disabled={togglingId === rule.id}
                  className={`relative inline-flex h-5 w-9 cursor-pointer items-center rounded-full transition-colors ${
                    rule.enabled ? 'bg-indigo-600' : 'bg-gray-300 dark:bg-gray-600'
                  } disabled:opacity-50`}
                >
                  <span
                    className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform ${
                      rule.enabled ? 'translate-x-4' : 'translate-x-0.5'
                    }`}
                  />
                </button>
              </div>

              {/* Content */}
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-semibold text-gray-900 dark:text-white">
                    {rule.name}
                  </span>
                  <TriggerBadge type={rule.triggerType} />
                  <ChannelBadge channel={rule.actionChannel} />
                  {!rule.enabled && (
                    <span className="inline-flex items-center rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-500 dark:bg-gray-700 dark:text-gray-400">
                      已禁用
                    </span>
                  )}
                </div>
                <div className="mt-1 flex flex-wrap items-center gap-3">
                  <span className="font-mono text-xs text-gray-400 dark:text-gray-500">
                    {rule.code}
                  </span>
                  {rule.conditionModelCode && (
                    <span className="text-xs text-gray-500 dark:text-gray-400">
                      模型: {rule.conditionModelCode}
                    </span>
                  )}
                  {rule.sendCount != null && rule.sendCount > 0 && (
                    <span className="text-xs text-gray-400 dark:text-gray-500">
                      已发送 {rule.sendCount} 次
                    </span>
                  )}
                </div>
              </div>

              {/* Actions */}
              <div className="flex flex-shrink-0 items-center gap-1">
                <button
                  onClick={() => handleTest(rule)}
                  disabled={testingId === rule.id}
                  title="测试规则"
                  className="rounded-lg p-2 text-gray-400 transition-colors hover:bg-blue-50 hover:text-blue-500 disabled:opacity-50 dark:hover:bg-blue-900/20 dark:hover:text-blue-400"
                >
                  {testingId === rule.id ? (
                    <div className="h-4 w-4 animate-spin rounded-full border-2 border-blue-400 border-t-transparent" />
                  ) : (
                    <BeakerIcon className="h-4 w-4" />
                  )}
                </button>
                <button
                  onClick={() => onEdit(rule)}
                  title="编辑规则"
                  className="rounded-lg p-2 text-gray-400 transition-colors hover:bg-indigo-50 hover:text-indigo-500 dark:hover:bg-indigo-900/20 dark:hover:text-indigo-400"
                >
                  <PencilIcon className="h-4 w-4" />
                </button>
                <button
                  onClick={() => handleDelete(rule)}
                  disabled={deletingId === rule.id}
                  title="删除规则"
                  className="rounded-lg p-2 text-gray-400 transition-colors hover:bg-red-50 hover:text-red-500 disabled:opacity-50 dark:hover:bg-red-900/20 dark:hover:text-red-400"
                >
                  {deletingId === rule.id ? (
                    <div className="h-4 w-4 animate-spin rounded-full border-2 border-red-400 border-t-transparent" />
                  ) : (
                    <TrashIcon className="h-4 w-4" />
                  )}
                </button>
              </div>
            </div>

            {/* Test result panel */}
            {testResult && (
              <div
                className={`flex items-start gap-2 border-t px-4 py-3 text-sm ${
                  testResult.success
                    ? 'border-green-200 bg-green-50 dark:border-green-800 dark:bg-green-900/10'
                    : 'border-red-200 bg-red-50 dark:border-red-800 dark:bg-red-900/10'
                }`}
              >
                {testResult.success ? (
                  <CheckCircleIcon className="mt-0.5 h-4 w-4 flex-shrink-0 text-green-500" />
                ) : (
                  <XCircleIcon className="mt-0.5 h-4 w-4 flex-shrink-0 text-red-500" />
                )}
                <div>
                  {testResult.success ? (
                    <>
                      <span className="font-medium text-green-700 dark:text-green-400">
                        测试通过
                      </span>
                      <span className="ml-2 text-green-600 dark:text-green-500">
                        {testResult.summary}
                      </span>
                    </>
                  ) : (
                    <span className="text-red-600 dark:text-red-400">
                      {testResult.error ?? '测试失败'}
                    </span>
                  )}
                </div>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
