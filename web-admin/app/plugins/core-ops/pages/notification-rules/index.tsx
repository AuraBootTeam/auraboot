import { useState, useEffect, useCallback } from 'react';
import { get, ErrorCodes } from '~/services/http-client';
import NotificationRuleList from '~/smart/components/notification/NotificationRuleList';
import NotificationRuleBuilder from '~/smart/components/notification/NotificationRuleBuilder';
import type { NotificationRule } from '~/smart/components/notification/NotificationRuleBuilder';
import { BellAlertIcon, PlusIcon, XMarkIcon } from '@heroicons/react/24/outline';

/**
 * Notification Rules management page.
 *
 * Provides a list view of all rules and a slide-in panel for create/edit.
 *
 * @since 5.2.0
 */
export default function NotificationRulesPage() {
  const [rules, setRules] = useState<NotificationRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Panel state: null = closed, undefined = new rule, NotificationRule = edit
  const [panelRule, setPanelRule] = useState<NotificationRule | null | undefined>(undefined);
  const isPanelOpen = panelRule !== undefined;

  // Load rules from API
  const loadRules = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await get<NotificationRule[]>('/api/notification-rules');
      if (result.code === ErrorCodes.SUCCESS && result.data) {
        setRules(Array.isArray(result.data) ? result.data : []);
      } else {
        setError('加载失败，请刷新页面');
      }
    } catch {
      setError('网络错误，请检查连接');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadRules();
  }, [loadRules]);

  // Panel actions
  const openCreate = () => setPanelRule(null);
  const openEdit = (rule: NotificationRule) => setPanelRule(rule);
  const closePanel = () => setPanelRule(undefined);

  const handleSaved = (saved: NotificationRule) => {
    setRules((prev) => {
      const idx = prev.findIndex((r) => r.id === saved.id);
      if (idx >= 0) {
        const next = [...prev];
        next[idx] = saved;
        return next;
      }
      return [saved, ...prev];
    });
    closePanel();
  };

  const handleDeleted = (id: number) => {
    setRules((prev) => prev.filter((r) => r.id !== id));
  };

  const handleToggled = (updated: NotificationRule) => {
    setRules((prev) => prev.map((r) => (r.id === updated.id ? updated : r)));
  };

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      {/* Page header */}
      <div className="border-b border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto max-w-5xl px-4 sm:px-6 lg:px-8">
          <div className="flex items-center justify-between py-5">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-indigo-100 dark:bg-indigo-900/30">
                <BellAlertIcon className="h-6 w-6 text-indigo-600 dark:text-indigo-400" />
              </div>
              <div>
                <h1 className="text-xl font-bold text-gray-900 dark:text-white">通知规则</h1>
                <p className="text-sm text-gray-500 dark:text-gray-400">
                  配置自动化通知触发规则 — 定时或事件驱动
                </p>
              </div>
            </div>
            <button
              onClick={openCreate}
              className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white shadow-sm transition-colors hover:bg-indigo-700"
            >
              <PlusIcon className="h-4 w-4" />
              创建规则
            </button>
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="mx-auto max-w-5xl px-4 py-6 sm:px-6 lg:px-8">
        {/* Stats bar */}
        {!loading && rules.length > 0 && (
          <div className="mb-6 grid grid-cols-3 gap-4">
            <StatCard
              label="规则总数"
              value={rules.length}
              color="text-indigo-600 dark:text-indigo-400"
            />
            <StatCard
              label="启用中"
              value={rules.filter((r) => r.enabled).length}
              color="text-green-600 dark:text-green-400"
            />
            <StatCard
              label="已禁用"
              value={rules.filter((r) => !r.enabled).length}
              color="text-gray-600 dark:text-gray-400"
            />
          </div>
        )}

        {/* Error state */}
        {error && (
          <div className="mb-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-600 dark:border-red-700 dark:bg-red-900/20 dark:text-red-400">
            {error}
          </div>
        )}

        {/* Rule list */}
        <NotificationRuleList
          rules={rules}
          loading={loading}
          onEdit={openEdit}
          onDeleted={handleDeleted}
          onToggled={handleToggled}
        />
      </div>

      {/* Side panel for create/edit */}
      {isPanelOpen && (
        <div className="fixed inset-0 z-50 flex">
          {/* Backdrop */}
          <div className="absolute inset-0 bg-black/40 dark:bg-black/60" onClick={closePanel} />
          {/* Panel */}
          <div className="relative ml-auto flex h-full w-full max-w-2xl flex-col bg-white shadow-2xl dark:bg-gray-900">
            {/* Panel header */}
            <div className="flex flex-shrink-0 items-center justify-between border-b border-gray-200 px-6 py-4 dark:border-gray-700">
              <div>
                <h2 className="text-lg font-semibold text-gray-900 dark:text-white">
                  {panelRule?.id ? '编辑通知规则' : '创建通知规则'}
                </h2>
                <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">
                  {panelRule?.id ? `规则代码: ${panelRule.code}` : '配置触发条件与通知动作'}
                </p>
              </div>
              <button
                onClick={closePanel}
                className="rounded-lg p-2 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-800 dark:hover:text-gray-300"
              >
                <XMarkIcon className="h-5 w-5" />
              </button>
            </div>

            {/* Panel body */}
            <div className="flex-1 overflow-y-auto px-6 py-5">
              <NotificationRuleBuilder
                initial={panelRule}
                onSaved={handleSaved}
                onCancel={closePanel}
              />
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ============================================================================
// Sub-components
// ============================================================================

function StatCard({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className="rounded-xl border border-gray-200 bg-white px-5 py-4 dark:border-gray-700 dark:bg-gray-800">
      <div className={`text-2xl font-bold ${color}`}>{value}</div>
      <div className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">{label}</div>
    </div>
  );
}
