/**
 * Cloud Service Configuration Management
 *
 * Platform management page for configuring cloud vendor integrations.
 * Supports SMS, EMAIL, OAUTH, STORAGE, CDN, IM service types with
 * per-provider dynamic config forms and sensitive field masking.
 *
 * LLM and PROMPT_TEMPLATE types are managed via dedicated AuraBot pages.
 */

import { useState } from 'react';
import { PlusIcon, CloudIcon } from '@heroicons/react/24/outline';
import {
  SERVICE_TYPES,
  useCloudConfigs,
  ConfigCard,
  ConfigEditorModal,
  type ServiceType,
  type ConfigLevel,
} from './cloud-config-core';

// ---------------------------------------------------------------------------
// Page-specific constants
// ---------------------------------------------------------------------------

/** Service types visible on THIS page (excludes LLM & PROMPT_TEMPLATE). */
const PAGE_SERVICE_TYPES = SERVICE_TYPES.filter(
  (st) => st.key !== 'llm' && st.key !== 'prompt_template',
);

// ---------------------------------------------------------------------------
// Page meta
// ---------------------------------------------------------------------------

type MetaArgs = Record<string, unknown>;

export function meta({}: MetaArgs) {
  return [
    { title: '云服务配置 - 管理员' },
    { name: 'description', content: '管理云服务商集成配置' },
  ];
}

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

export default function CloudConfigPage() {
  const {
    configs,
    loading,
    level,
    setLevel,
    showEditor,
    setShowEditor,
    editingConfig,
    testingPid,
    handleCreate,
    handleEdit,
    handleDelete,
    handleTest,
    handleToggleEnabled,
    handleSave,
  } = useCloudConfigs();

  const [activeTab, setActiveTab] = useState<ServiceType>('sms');

  const filteredConfigs = configs.filter((c) => c.serviceType === activeTab);

  // -------------------------------------------------------------------------
  // Render
  // -------------------------------------------------------------------------

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto max-w-7xl px-6 py-5">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <CloudIcon className="h-7 w-7 text-blue-600" />
              <div>
                <h1 className="text-xl font-bold text-gray-900 dark:text-white">云服务配置</h1>
                <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">
                  管理短信、邮件、OAuth、存储、CDN、IM 等云服务商集成配置
                </p>
              </div>
            </div>
            <button
              onClick={handleCreate}
              className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
              data-testid="cloud-config-create-btn"
            >
              <PlusIcon className="h-4 w-4" />
              新建配置
            </button>
          </div>
        </div>
      </div>

      <div className="mx-auto max-w-7xl px-6 py-6">
        {/* Level toggle + Service type tabs */}
        <div className="rounded-lg border border-gray-200 bg-white shadow-sm dark:border-gray-700 dark:bg-gray-800">
          {/* Level & Tabs bar */}
          <div className="flex items-center justify-between px-4 pt-4 pb-0">
            {/* Level toggle */}
            <div className="inline-flex rounded-lg border border-gray-200 bg-gray-50 p-0.5 dark:border-gray-600 dark:bg-gray-700">
              {(['platform', 'tenant'] as ConfigLevel[]).map((lv) => (
                <button
                  key={lv}
                  onClick={() => setLevel(lv)}
                  className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
                    level === lv
                      ? 'bg-white text-blue-600 shadow-sm dark:bg-gray-600 dark:text-blue-400'
                      : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
                  }`}
                  data-testid={`cloud-config-level-${lv.toLowerCase()}`}
                >
                  {lv === 'platform' ? '平台级' : '租户级'}
                </button>
              ))}
            </div>
          </div>

          {/* Service type tabs */}
          <div className="mt-3 px-4">
            <nav className="flex gap-1 border-b border-gray-200 dark:border-gray-700">
              {PAGE_SERVICE_TYPES.map((st) => {
                const count = configs.filter((c) => c.serviceType === st.key).length;
                return (
                  <button
                    key={st.key}
                    onClick={() => setActiveTab(st.key)}
                    className={`border-b-2 px-4 py-2.5 text-sm font-medium whitespace-nowrap transition-colors ${
                      activeTab === st.key
                        ? 'border-blue-600 text-blue-600 dark:border-blue-400 dark:text-blue-400'
                        : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
                    }`}
                    data-testid={`cloud-config-tab-${st.key.toLowerCase()}`}
                  >
                    {st.label}
                    {count > 0 && (
                      <span className="ml-1.5 inline-flex h-5 w-5 items-center justify-center rounded-full bg-gray-100 text-xs text-gray-600 dark:bg-gray-700 dark:text-gray-300">
                        {count}
                      </span>
                    )}
                  </button>
                );
              })}
            </nav>
          </div>

          {/* Config list */}
          <div className="p-4">
            {loading ? (
              <div className="flex justify-center py-12">
                <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
              </div>
            ) : filteredConfigs.length === 0 ? (
              <div className="py-12 text-center text-gray-400 dark:text-gray-500">
                <CloudIcon className="mx-auto mb-3 h-12 w-12 opacity-40" />
                <p className="text-sm">
                  暂无{PAGE_SERVICE_TYPES.find((s) => s.key === activeTab)?.label}配置
                </p>
                <button
                  onClick={handleCreate}
                  className="mt-3 text-sm text-blue-600 hover:text-blue-700"
                >
                  + 新建配置
                </button>
              </div>
            ) : (
              <div className="space-y-3">
                {filteredConfigs.map((config) => (
                  <ConfigCard
                    key={config.pid}
                    config={config}
                    testing={testingPid === config.pid}
                    onEdit={() => handleEdit(config)}
                    onDelete={() => handleDelete(config)}
                    onTest={() => handleTest(config)}
                    onToggle={() => handleToggleEnabled(config)}
                  />
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Editor modal */}
      {showEditor && (
        <ConfigEditorModal
          config={editingConfig}
          currentLevel={level}
          currentServiceType={activeTab}
          serviceTypes={PAGE_SERVICE_TYPES}
          onClose={() => setShowEditor(false)}
          onSave={handleSave}
        />
      )}
    </div>
  );
}
