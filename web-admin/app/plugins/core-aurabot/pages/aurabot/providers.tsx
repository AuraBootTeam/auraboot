/**
 * AuraBot — LLM Provider Management (Card Grid UX)
 *
 * Modern card-grid layout with provider picker, side-panel editor,
 * and inline test-connection. Replaces the flat-list + modal approach.
 */

import { useState, useEffect, useMemo } from 'react';
import {
  CloudIcon,
  PlusIcon,
  PencilIcon,
  TrashIcon,
  BeakerIcon,
  XMarkIcon,
  EyeIcon,
  EyeSlashIcon,
  ChevronRightIcon,
} from '@heroicons/react/24/outline';
import {
  useCloudConfigs,
  PROVIDER_LABELS,
  PROVIDER_FIELDS,
  PROVIDERS_BY_TYPE,
  llmFields,
  safeParseJSON,
  type CloudConfig,
  type ConfigLevel,
  type ServiceType,
} from '~/shared/admin/cloud-config-core';
import { useToastContext } from '~/contexts/ToastContext';
import { post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { workspacePageClassName } from '~/shared/layout/WorkspacePageLayout';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const LLM_TYPE: ServiceType = 'llm';

const PROVIDER_ICONS: Record<string, string> = {
  anthropic: '\u{1F916}',
  openai: '\u{1F9E0}',
  deepseek: '\u{1F50D}',
  minimaxi: '\u{1F30A}',
  qianwen: '\u{2601}\uFE0F',
  zhipu: '\u{1F52E}',
  moonshot: '\u{1F319}',
};

const PROVIDER_PRESETS = [
  {
    code: 'anthropic',
    name: 'Anthropic (Claude)',
    desc: 'Claude family \u2014 Opus, Sonnet, Haiku',
    icon: '\u{1F916}',
    apiFormat: 'messages',
  },
  {
    code: 'openai',
    name: 'OpenAI',
    desc: 'GPT-4o, GPT-4, o1 series',
    icon: '\u{1F9E0}',
    apiFormat: 'chat_completions',
  },
  {
    code: 'deepseek',
    name: 'DeepSeek',
    desc: 'DeepSeek Chat & Reasoner',
    icon: '\u{1F50D}',
    apiFormat: 'chat_completions',
  },
  {
    code: 'minimaxi',
    name: 'MiniMaxi',
    desc: 'MiniMax-Text-01',
    icon: '\u{1F30A}',
    apiFormat: 'chat_completions',
  },
  {
    code: 'qianwen',
    name: 'Qwen',
    desc: 'Qwen-Plus, Qwen-Max',
    icon: '\u{2601}\uFE0F',
    apiFormat: 'chat_completions',
  },
  {
    code: 'zhipu',
    name: 'Zhipu',
    desc: 'GLM-4, GLM-3-Turbo',
    icon: '\u{1F52E}',
    apiFormat: 'chat_completions',
  },
  {
    code: 'moonshot',
    name: 'Moonshot',
    desc: 'Moonshot-v1-8k/32k/128k',
    icon: '\u{1F319}',
    apiFormat: 'chat_completions',
  },
] as const;

const API_FORMAT_LABELS: Record<string, string> = {
  messages: 'Messages API',
  chat_completions: 'Chat Completions',
};

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function slugify(name: string): string {
  return (
    name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '')
      .slice(0, 40) || 'custom'
  );
}

/** Get the status of a provider config */
function getProviderStatus(config: CloudConfig): 'active' | 'unconfigured' | 'ready' | 'disabled' {
  const parsed = safeParseJSON(config.config);
  if (!parsed.apiKey) return 'unconfigured';
  if (!config.enabled) return 'ready'; // has key but not yet tested/activated
  return 'active';
}

function StatusDot({ status }: { status: 'active' | 'unconfigured' | 'ready' | 'disabled' }) {
  const colors = {
    active: 'bg-emerald-500',
    unconfigured: 'bg-gray-400',
    ready: 'bg-amber-500',
    disabled: 'bg-gray-400',
  };
  const labels = {
    active: 'Active',
    unconfigured: 'No API Key',
    ready: 'Ready to Test',
    disabled: 'Disabled',
  };
  return (
    <span className="flex items-center gap-1.5">
      <span className={`inline-block h-2 w-2 rounded-full ${colors[status]}`} />
      <span className="text-xs text-gray-500 dark:text-gray-400">{labels[status]}</span>
    </span>
  );
}

// ---------------------------------------------------------------------------
// Meta
// ---------------------------------------------------------------------------

export function meta() {
  return [
    { title: 'LLM Providers - AuraBot' },
    { name: 'description', content: 'Manage LLM provider API keys and model configuration' },
  ];
}

// ---------------------------------------------------------------------------
// Main Page
// ---------------------------------------------------------------------------

export default function LlmProvidersPage() {
  const {
    configs,
    loading,
    level,
    setLevel,
    testingPid,
    handleDelete,
    handleToggleEnabled,
    handleSave,
  } = useCloudConfigs();

  const { showSuccessToast, showErrorToast } = useToastContext();

  const llmConfigs = configs.filter((c) => c.serviceType === LLM_TYPE);

  // --- UI state ---
  const [showPicker, setShowPicker] = useState(false);
  const [sidePanel, setSidePanel] = useState<{
    config: CloudConfig | null;
    providerCode: string;
    isNew: boolean;
    customMode?: boolean;
  } | null>(null);
  const [localTestingPid, setLocalTestingPid] = useState<string | null>(null);

  // Track dynamic custom provider fields (triggers re-render when new custom providers are registered)
  const [, setCustomProviderFields] = useState<Record<string, unknown>>({});
  // Combined testing state
  const isTestingPid = testingPid || localTestingPid;

  // --- Handlers ---

  const handleAddClick = () => {
    setShowPicker(true);
  };

  const handlePickPreset = (preset: (typeof PROVIDER_PRESETS)[number]) => {
    setShowPicker(false);
    setSidePanel({
      config: null,
      providerCode: preset.code,
      isNew: true,
    });
  };

  const handlePickCustom = () => {
    setShowPicker(false);
    setSidePanel({
      config: null,
      providerCode: '',
      isNew: true,
      customMode: true,
    });
  };

  const handleEditCard = (config: CloudConfig) => {
    const isCustom = !PROVIDERS_BY_TYPE.llm.includes(config.providerCode);
    setSidePanel({
      config,
      providerCode: config.providerCode,
      isNew: false,
      customMode: isCustom,
    });
  };

  const handleSideClose = () => {
    setSidePanel(null);
  };

  const handleSideSave = async (data: {
    configLevel: ConfigLevel;
    serviceType: ServiceType;
    providerCode: string;
    config: Record<string, string>;
    enabled: boolean;
    priority: number;
  }) => {
    // Register custom provider fields if needed
    if (data.providerCode && !PROVIDER_FIELDS[data.providerCode]) {
      const baseUrl = data.config.baseUrl || '';
      const fields = llmFields({
        placeholder: 'sk-...',
        baseUrl: baseUrl || 'https://api.example.com',
        model: data.config.defaultModel || 'default-model',
      });
      PROVIDER_FIELDS[data.providerCode] = fields;
      setCustomProviderFields((prev) => ({ ...prev, [data.providerCode]: fields }));
    }
    await handleSave(data);
    setSidePanel(null);
  };

  const handleTestInline = async (config: CloudConfig) => {
    setLocalTestingPid(config.pid);
    try {
      const result = await post('/api/admin/cloud-config/{pid}/test', { pid: config.pid });
      if (ResultHelper.isSuccess(result)) {
        // Auto-enable provider after successful test
        if (!config.enabled) {
          await handleToggleEnabled(config);
          showSuccessToast('Connection test passed — provider activated');
        } else {
          showSuccessToast('Connection test passed');
        }
      } else {
        showErrorToast(result.desc || 'Connection test failed');
      }
    } catch (e: any) {
      showErrorToast(e.message || 'Connection test failed');
    } finally {
      setLocalTestingPid(null);
    }
  };

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className={workspacePageClassName('header')}>
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-gradient-to-br from-violet-500 to-purple-600">
                <CloudIcon className="h-5 w-5 text-white" />
              </div>
              <div>
                <h1 className="text-xl font-bold text-gray-900 dark:text-white">LLM Providers</h1>
                <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">
                  Manage API keys, models, and endpoints for AI model providers
                </p>
              </div>
            </div>
            <button
              onClick={handleAddClick}
              data-testid="add-provider-btn"
              className="flex items-center gap-2 rounded-lg bg-violet-600 px-4 py-2 text-sm font-medium text-white shadow-sm transition-colors hover:bg-violet-700"
            >
              <PlusIcon className="h-4 w-4" />
              Add Provider
            </button>
          </div>
        </div>
      </div>

      <div className={workspacePageClassName('content')}>
        {/* Level toggle + count */}
        <div className="mb-5 flex items-center justify-between">
          <div className="inline-flex rounded-lg border border-gray-200 bg-gray-50 p-0.5 dark:border-gray-600 dark:bg-gray-700">
            {(['platform', 'tenant'] as const).map((lv) => (
              <button
                key={lv}
                onClick={() => setLevel(lv)}
                data-testid={`level-toggle-${lv.toLowerCase()}`}
                className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
                  level === lv
                    ? 'bg-white text-violet-600 shadow-sm dark:bg-gray-600 dark:text-violet-400'
                    : 'text-gray-500 hover:text-gray-700 dark:text-gray-400'
                }`}
              >
                {lv === 'platform' ? 'Platform' : 'Tenant'}
              </button>
            ))}
          </div>
          <span className="text-sm text-gray-400">
            {llmConfigs.length} provider{llmConfigs.length !== 1 ? 's' : ''} configured
          </span>
        </div>

        {/* Card Grid */}
        {loading ? (
          <div className="flex justify-center py-16">
            <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-violet-600" />
          </div>
        ) : llmConfigs.length === 0 ? (
          <EmptyState onAdd={handleAddClick} />
        ) : (
          <div
            className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3"
            data-testid="provider-card-grid"
          >
            {llmConfigs.map((config) => (
              <ProviderCard
                key={config.pid}
                config={config}
                testing={isTestingPid === config.pid}
                onEdit={() => handleEditCard(config)}
                onDelete={() => handleDelete(config)}
                onTest={() => handleTestInline(config)}
                onToggle={() => handleToggleEnabled(config)}
              />
            ))}
          </div>
        )}
      </div>

      {/* Provider Picker Overlay */}
      {showPicker && (
        <ProviderPickerOverlay
          existingCodes={llmConfigs.map((c) => c.providerCode)}
          onPickPreset={handlePickPreset}
          onPickCustom={handlePickCustom}
          onClose={() => setShowPicker(false)}
        />
      )}

      {/* Edit Side Panel */}
      {sidePanel && (
        <EditSidePanel
          config={sidePanel.config}
          providerCode={sidePanel.providerCode}
          isNew={sidePanel.isNew}
          customMode={sidePanel.customMode}
          currentLevel={level}
          onClose={handleSideClose}
          onSave={handleSideSave}
          onTest={handleTestInline}
          testing={isTestingPid}
        />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Empty State
// ---------------------------------------------------------------------------

function EmptyState({ onAdd }: { onAdd: () => void }) {
  return (
    <div className="rounded-xl border border-gray-200 bg-white py-16 text-center dark:border-gray-700 dark:bg-gray-800">
      <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-2xl bg-gray-100 dark:bg-gray-700">
        <CloudIcon className="h-8 w-8 text-gray-400" />
      </div>
      <h3 className="mb-1 text-base font-medium text-gray-900 dark:text-white">
        No LLM providers configured
      </h3>
      <p className="mx-auto mb-4 max-w-sm text-sm text-gray-500 dark:text-gray-400">
        Add an AI model provider to enable AuraBot chat, AI scoring, and other intelligent features.
      </p>
      <button
        onClick={onAdd}
        className="inline-flex items-center gap-2 rounded-lg bg-violet-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-violet-700"
      >
        <PlusIcon className="h-4 w-4" />
        Add your first provider
      </button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Provider Card
// ---------------------------------------------------------------------------

function ProviderCard({
  config,
  testing,
  onEdit,
  onDelete,
  onTest,
  onToggle,
}: {
  config: CloudConfig;
  testing: boolean;
  onEdit: () => void;
  onDelete: () => void;
  onTest: () => void;
  onToggle: () => void;
}) {
  const parsed = safeParseJSON(config.config);
  const status = getProviderStatus(config);
  const icon = PROVIDER_ICONS[config.providerCode] || '\u{2699}\uFE0F';
  const displayName =
    parsed.displayName || PROVIDER_LABELS[config.providerCode] || config.providerCode;
  const model = parsed.defaultModel;
  const apiFormat = parsed.apiFormat;

  return (
    <div
      className={`group relative rounded-xl border transition-all duration-200 ${
        config.enabled
          ? 'border-gray-200 bg-white hover:border-violet-300 hover:shadow-md dark:border-gray-700 dark:bg-gray-800 dark:hover:border-violet-600'
          : 'border-gray-100 bg-gray-50 opacity-75 hover:opacity-100 dark:border-gray-700/50 dark:bg-gray-800/50'
      }`}
      data-testid={`provider-card-${config.providerCode}`}
    >
      {/* Card Body */}
      <div className="p-4">
        {/* Top row: icon + name + status */}
        <div className="mb-3 flex items-start justify-between">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-gray-100 text-xl dark:bg-gray-700">
              {icon}
            </div>
            <div className="min-w-0">
              <h3 className="truncate text-sm font-semibold text-gray-900 dark:text-white">
                {displayName}
              </h3>
              <span className="font-mono text-xs text-gray-400 dark:text-gray-500">
                {config.providerCode}
              </span>
            </div>
          </div>
          <StatusDot status={status} />
        </div>

        {/* Info chips */}
        <div className="mb-3 flex flex-wrap gap-1.5">
          {model && (
            <span className="inline-flex items-center rounded-md bg-violet-50 px-2 py-0.5 text-xs font-medium text-violet-700 dark:bg-violet-900/30 dark:text-violet-300">
              {model}
            </span>
          )}
          {apiFormat && API_FORMAT_LABELS[apiFormat] && (
            <span className="inline-flex items-center rounded-md bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
              {API_FORMAT_LABELS[apiFormat]}
            </span>
          )}
          {config.priority > 0 && (
            <span className="inline-flex items-center rounded-md bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-700 dark:bg-amber-900/30 dark:text-amber-300">
              Priority {config.priority}
            </span>
          )}
        </div>

        {/* Actions row */}
        <div className="flex items-center justify-between border-t border-gray-100 pt-3 dark:border-gray-700/50">
          {/* Toggle */}
          <button
            onClick={onToggle}
            className={`relative inline-flex h-5 w-9 items-center rounded-full transition-colors focus:ring-2 focus:ring-violet-500 focus:ring-offset-1 focus:outline-none ${
              config.enabled ? 'bg-violet-600' : 'bg-gray-300 dark:bg-gray-600'
            }`}
            role="switch"
            aria-checked={config.enabled}
            data-testid={`provider-toggle-${config.providerCode}`}
          >
            <span
              className={`inline-block h-3.5 w-3.5 transform rounded-full bg-white shadow transition-transform ${
                config.enabled ? 'translate-x-4' : 'translate-x-0.5'
              }`}
            />
          </button>

          {/* Action buttons */}
          <div className="flex items-center gap-0.5">
            <button
              onClick={onTest}
              disabled={testing}
              className="rounded-md p-1.5 text-gray-400 transition-colors hover:text-emerald-600 disabled:cursor-not-allowed disabled:opacity-40 dark:hover:text-emerald-400"
              title="Test Connection"
              data-testid={`provider-test-${config.providerCode}`}
            >
              {testing ? (
                <div className="h-4 w-4 animate-spin rounded-full border-b-2 border-emerald-600" />
              ) : (
                <BeakerIcon className="h-4 w-4" />
              )}
            </button>
            <button
              onClick={onEdit}
              className="rounded-md p-1.5 text-gray-400 transition-colors hover:text-violet-600 dark:hover:text-violet-400"
              title="Edit"
              data-testid={`provider-edit-${config.providerCode}`}
            >
              <PencilIcon className="h-4 w-4" />
            </button>
            <button
              onClick={onDelete}
              className="rounded-md p-1.5 text-gray-400 transition-colors hover:text-red-600 dark:hover:text-red-400"
              title="Delete"
              data-testid={`provider-delete-${config.providerCode}`}
            >
              <TrashIcon className="h-4 w-4" />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Provider Picker Overlay
// ---------------------------------------------------------------------------

function ProviderPickerOverlay({
  existingCodes,
  onPickPreset,
  onPickCustom,
  onClose,
}: {
  existingCodes: string[];
  onPickPreset: (preset: (typeof PROVIDER_PRESETS)[number]) => void;
  onPickCustom: () => void;
  onClose: () => void;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-black/50 pt-[8vh]">
      <div
        className="mx-4 mb-8 w-full max-w-2xl rounded-xl bg-white shadow-2xl dark:bg-gray-800"
        data-testid="provider-picker-overlay"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <div>
            <h3 className="text-lg font-semibold text-gray-900 dark:text-white">Add Provider</h3>
            <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">
              Choose a model provider to configure
            </p>
          </div>
          <button
            onClick={onClose}
            className="rounded-md p-1.5 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
          >
            <XMarkIcon className="h-5 w-5" />
          </button>
        </div>

        {/* Preset grid */}
        <div className="p-6">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            {PROVIDER_PRESETS.map((preset) => {
              const alreadyAdded = existingCodes.includes(preset.code);
              return (
                <button
                  key={preset.code}
                  onClick={() => onPickPreset(preset)}
                  disabled={alreadyAdded}
                  data-testid={`picker-preset-${preset.code}`}
                  className={`group flex items-center gap-3 rounded-lg border p-4 text-left transition-all ${
                    alreadyAdded
                      ? 'cursor-not-allowed border-gray-100 bg-gray-50 opacity-50 dark:border-gray-700/50 dark:bg-gray-800/50'
                      : 'cursor-pointer border-gray-200 bg-white hover:border-violet-300 hover:shadow-md dark:border-gray-700 dark:bg-gray-800 dark:hover:border-violet-600'
                  }`}
                >
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-gray-100 text-xl dark:bg-gray-700">
                    {preset.icon}
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-semibold text-gray-900 dark:text-white">
                        {preset.name}
                      </span>
                      {alreadyAdded && (
                        <span className="rounded bg-gray-200 px-1.5 py-0.5 text-[10px] font-medium text-gray-500 dark:bg-gray-600 dark:text-gray-400">
                          Added
                        </span>
                      )}
                    </div>
                    <span className="text-xs text-gray-500 dark:text-gray-400">{preset.desc}</span>
                  </div>
                  {!alreadyAdded && (
                    <ChevronRightIcon className="h-4 w-4 shrink-0 text-gray-300 transition-colors group-hover:text-violet-500" />
                  )}
                </button>
              );
            })}

            {/* Custom provider card */}
            <button
              onClick={onPickCustom}
              data-testid="picker-preset-custom"
              className="group flex cursor-pointer items-center gap-3 rounded-lg border border-dashed border-gray-300 bg-white p-4 text-left transition-all hover:border-violet-400 hover:shadow-md dark:border-gray-600 dark:bg-gray-800 dark:hover:border-violet-500"
            >
              <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-gray-100 text-xl dark:bg-gray-700">
                <PlusIcon className="h-5 w-5 text-gray-500 dark:text-gray-400" />
              </div>
              <div className="min-w-0 flex-1">
                <span className="text-sm font-semibold text-gray-900 dark:text-white">
                  Custom (OpenAI-compatible)
                </span>
                <span className="block text-xs text-gray-500 dark:text-gray-400">
                  Add a self-hosted or unlisted provider
                </span>
              </div>
              <ChevronRightIcon className="h-4 w-4 shrink-0 text-gray-300 transition-colors group-hover:text-violet-500" />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Edit Side Panel
// ---------------------------------------------------------------------------

function EditSidePanel({
  config,
  providerCode: initialProviderCode,
  isNew,
  customMode,
  currentLevel,
  onClose,
  onSave,
  onTest,
  testing,
}: {
  config: CloudConfig | null;
  providerCode: string;
  isNew: boolean;
  customMode?: boolean;
  currentLevel: ConfigLevel;
  onClose: () => void;
  onSave: (data: {
    configLevel: ConfigLevel;
    serviceType: ServiceType;
    providerCode: string;
    config: Record<string, string>;
    enabled: boolean;
    priority: number;
  }) => Promise<void>;
  onTest: (config: CloudConfig) => void;
  testing: string | null;
}) {
  const existingParsed = config ? safeParseJSON(config.config) : {};

  // --- Form state ---
  const [configLevel, setConfigLevel] = useState<ConfigLevel>(config?.configLevel || currentLevel);
  const [providerCode] = useState(initialProviderCode);
  const [enabled, setEnabled] = useState(config?.enabled ?? false);
  const [priority, setPriority] = useState(config?.priority ?? 0);
  const [saving, setSaving] = useState(false);

  // Custom mode fields
  const [customDisplayName, setCustomDisplayName] = useState(existingParsed.displayName || '');
  const [customBaseUrl] = useState(existingParsed.baseUrl || '');

  // Config values
  const [configValues, setConfigValues] = useState<Record<string, string>>(existingParsed);
  const [showSensitive, setShowSensitive] = useState<Record<string, boolean>>({});

  // API format
  const [apiFormat, setApiFormat] = useState(
    existingParsed.apiFormat ||
      PROVIDER_PRESETS.find((p) => p.code === initialProviderCode)?.apiFormat ||
      'chat_completions',
  );

  // Derive provider code for custom mode
  const effectiveProviderCode =
    customMode && isNew ? slugify(customDisplayName) || 'custom' : providerCode;

  // Get fields for current provider
  const fields = useMemo(() => {
    if (customMode && isNew) {
      return llmFields({
        placeholder: 'sk-...',
        baseUrl: customBaseUrl || 'https://api.example.com',
        model: 'model-name',
      });
    }
    return (
      PROVIDER_FIELDS[providerCode] ||
      llmFields({
        placeholder: 'sk-...',
        baseUrl: 'https://api.example.com',
        model: 'model-name',
      })
    );
  }, [customMode, isNew, providerCode, customBaseUrl]);

  // Provider display info
  const preset = PROVIDER_PRESETS.find((p) => p.code === providerCode);
  const icon = PROVIDER_ICONS[providerCode] || (customMode ? '\u{2699}\uFE0F' : '\u{2699}\uFE0F');
  const displayName = customMode
    ? customDisplayName || 'Custom Provider'
    : preset?.name || PROVIDER_LABELS[providerCode] || providerCode;

  // Sync apiFormat into configValues
  useEffect(() => {
    setConfigValues((prev) => ({ ...prev, apiFormat }));
  }, [apiFormat]);

  // For custom mode, sync displayName
  useEffect(() => {
    if (customMode && customDisplayName) {
      setConfigValues((prev) => ({ ...prev, displayName: customDisplayName }));
    }
  }, [customMode, customDisplayName]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    // Validate required
    for (const f of fields) {
      if (f.key === 'apiKey' && f.required && !configValues.apiKey?.trim()) {
        return;
      }
    }

    setSaving(true);
    try {
      await onSave({
        configLevel,
        serviceType: LLM_TYPE,
        providerCode: effectiveProviderCode,
        config: configValues,
        enabled,
        priority,
      });
    } finally {
      setSaving(false);
    }
  };

  const handleFieldChange = (key: string, value: string) => {
    setConfigValues((prev) => ({ ...prev, [key]: value }));
  };

  const toggleSensitive = (key: string) => {
    setShowSensitive((prev) => ({ ...prev, [key]: !prev[key] }));
  };

  // Field rendering order: apiKey first, then others (skip apiFormat — we render it as radio)
  const orderedFields = useMemo(() => {
    const apiKeyField = fields.find((f) => f.key === 'apiKey');
    const others = fields.filter((f) => f.key !== 'apiKey' && f.key !== 'apiFormat');
    return apiKeyField ? [apiKeyField, ...others] : others;
  }, [fields]);

  return (
    <>
      {/* Backdrop */}
      <div className="fixed inset-0 z-40 bg-black/30 transition-opacity" onClick={onClose} />

      {/* Panel */}
      <div
        className="fixed top-0 right-0 bottom-0 z-50 flex w-full max-w-md flex-col overflow-hidden border-l border-gray-200 bg-white shadow-2xl dark:border-gray-700 dark:bg-gray-800"
        data-testid="provider-edit-panel"
      >
        {/* Panel header */}
        <div className="flex shrink-0 items-center gap-3 border-b border-gray-200 px-6 py-4 dark:border-gray-700">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-gray-100 text-lg dark:bg-gray-700">
            {icon}
          </div>
          <div className="min-w-0 flex-1">
            <h3 className="truncate text-base font-semibold text-gray-900 dark:text-white">
              {isNew ? 'Configure' : 'Edit'} {displayName}
            </h3>
            {!customMode && <span className="font-mono text-xs text-gray-400">{providerCode}</span>}
          </div>
          <button
            onClick={onClose}
            className="rounded-md p-1.5 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
          >
            <XMarkIcon className="h-5 w-5" />
          </button>
        </div>

        {/* Panel body (scrollable) */}
        <form onSubmit={handleSubmit} className="flex-1 overflow-y-auto">
          <div className="space-y-5 p-6">
            {/* Custom mode: Display Name + Base URL first */}
            {customMode && isNew && (
              <>
                <div>
                  <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                    Display Name <span className="text-red-500">*</span>
                  </label>
                  <input
                    type="text"
                    value={customDisplayName}
                    onChange={(e) => setCustomDisplayName(e.target.value)}
                    placeholder="e.g., My Local LLM"
                    required
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-violet-500 focus:ring-2 focus:ring-violet-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    data-testid="custom-display-name"
                  />
                  {customDisplayName && (
                    <p className="mt-1 text-xs text-gray-400">
                      Provider code: <code className="font-mono">{effectiveProviderCode}</code>
                    </p>
                  )}
                </div>
              </>
            )}

            {/* Config level */}
            <div>
              <label className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-300">
                Config Level
              </label>
              <div className="flex gap-3">
                {(['platform', 'tenant'] as ConfigLevel[]).map((lv) => (
                  <label key={lv} className="flex cursor-pointer items-center gap-2">
                    <input
                      type="radio"
                      name="configLevel"
                      value={lv}
                      checked={configLevel === lv}
                      onChange={() => setConfigLevel(lv)}
                      className="text-violet-600 focus:ring-violet-500"
                      disabled={!isNew}
                    />
                    <span className="text-sm text-gray-700 dark:text-gray-300">
                      {lv === 'platform' ? 'Platform' : 'Tenant'}
                    </span>
                  </label>
                ))}
              </div>
            </div>

            {/* Dynamic config fields */}
            {orderedFields.map((f) => (
              <div key={f.key}>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  {f.label}
                  {f.required && <span className="ml-0.5 text-red-500">*</span>}
                </label>
                <div className="relative">
                  <input
                    type={f.sensitive && !showSensitive[f.key] ? 'password' : 'text'}
                    value={configValues[f.key] || ''}
                    onChange={(e) => handleFieldChange(f.key, e.target.value)}
                    required={f.required}
                    placeholder={f.placeholder || ''}
                    className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 pr-9 text-sm text-gray-900 focus:border-violet-500 focus:ring-2 focus:ring-violet-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    autoComplete="off"
                    data-testid={`field-${f.key}`}
                  />
                  {f.sensitive && (
                    <button
                      type="button"
                      onClick={() => toggleSensitive(f.key)}
                      className="absolute top-1/2 right-2 -translate-y-1/2 text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"
                      tabIndex={-1}
                    >
                      {showSensitive[f.key] ? (
                        <EyeSlashIcon className="h-4 w-4" />
                      ) : (
                        <EyeIcon className="h-4 w-4" />
                      )}
                    </button>
                  )}
                </div>
              </div>
            ))}

            {/* API Format radio */}
            <div>
              <label className="mb-2 block text-sm font-medium text-gray-700 dark:text-gray-300">
                API Format
              </label>
              <div className="flex gap-4">
                {[
                  { value: 'messages', label: 'Messages API', hint: 'Anthropic' },
                  {
                    value: 'chat_completions',
                    label: 'Chat Completions',
                    hint: 'OpenAI-compatible',
                  },
                ].map((opt) => (
                  <label
                    key={opt.value}
                    className={`flex flex-1 cursor-pointer items-center gap-2 rounded-lg border p-3 transition-colors ${
                      apiFormat === opt.value
                        ? 'border-violet-300 bg-violet-50 dark:border-violet-600 dark:bg-violet-900/20'
                        : 'border-gray-200 hover:border-gray-300 dark:border-gray-600'
                    }`}
                  >
                    <input
                      type="radio"
                      name="apiFormat"
                      value={opt.value}
                      checked={apiFormat === opt.value}
                      onChange={() => setApiFormat(opt.value)}
                      className="text-violet-600 focus:ring-violet-500"
                    />
                    <div>
                      <span className="text-sm font-medium text-gray-900 dark:text-white">
                        {opt.label}
                      </span>
                      <span className="block text-[10px] text-gray-400">{opt.hint}</span>
                    </div>
                  </label>
                ))}
              </div>
            </div>

            {/* Priority */}
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                Priority
              </label>
              <div className="flex items-center gap-2">
                <input
                  type="number"
                  min={0}
                  max={99}
                  value={priority}
                  onChange={(e) => setPriority(Number(e.target.value))}
                  className="w-20 rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:ring-2 focus:ring-violet-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                  data-testid="field-priority"
                />
                <span className="text-xs text-gray-400">Higher = preferred</span>
              </div>
            </div>

            {/* Enable toggle */}
            <div className="flex items-center justify-between py-2">
              <span className="text-sm font-medium text-gray-700 dark:text-gray-300">
                Enable Provider
              </span>
              <button
                type="button"
                onClick={() => setEnabled(!enabled)}
                className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus:ring-2 focus:ring-violet-500 focus:ring-offset-1 focus:outline-none ${
                  enabled ? 'bg-violet-600' : 'bg-gray-300 dark:bg-gray-600'
                }`}
                role="switch"
                aria-checked={enabled}
                data-testid="field-enabled-toggle"
              >
                <span
                  className={`inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform ${
                    enabled ? 'translate-x-5.5' : 'translate-x-0.5'
                  }`}
                  style={{ transform: enabled ? 'translateX(22px)' : 'translateX(2px)' }}
                />
              </button>
            </div>

            {/* Test Connection button (only for existing configs) */}
            {config && (
              <button
                type="button"
                onClick={() => onTest(config)}
                disabled={!!testing}
                className="flex w-full items-center justify-center gap-2 rounded-lg border border-gray-200 px-4 py-2.5 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-40 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
                data-testid="panel-test-connection"
              >
                {testing === config.pid ? (
                  <>
                    <div className="h-4 w-4 animate-spin rounded-full border-b-2 border-emerald-600" />
                    Testing...
                  </>
                ) : (
                  <>
                    <BeakerIcon className="h-4 w-4" />
                    Test Connection
                  </>
                )}
              </button>
            )}
          </div>
        </form>

        {/* Panel footer */}
        <div className="flex shrink-0 items-center justify-end gap-3 border-t border-gray-200 bg-gray-50 px-6 py-4 dark:border-gray-700 dark:bg-gray-800/50">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm text-gray-700 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
          >
            Cancel
          </button>
          <button
            onClick={handleSubmit as any}
            disabled={
              saving ||
              (!customMode && !providerCode) ||
              (customMode && isNew && !customDisplayName.trim())
            }
            className="rounded-lg bg-violet-600 px-5 py-2 text-sm font-medium text-white shadow-sm transition-colors hover:bg-violet-700 disabled:cursor-not-allowed disabled:opacity-50"
            data-testid="panel-save-btn"
          >
            {saving ? 'Saving...' : isNew ? 'Create' : 'Save Changes'}
          </button>
        </div>
      </div>
    </>
  );
}
