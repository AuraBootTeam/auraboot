/**
 * AuraBot — Prompt Template Management (Split-Pane Editor)
 *
 * Redesigned with split-pane layout: left panel for template list,
 * right panel for editor with preview and variable highlighting.
 */

import { useState, useMemo, useCallback, useRef } from 'react';
import {
  DocumentTextIcon,
  PlusIcon,
  TrashIcon,
  CheckIcon,
  ArrowPathIcon,
  CodeBracketIcon,
  EyeIcon,
  ClockIcon,
  HashtagIcon,
} from '@heroicons/react/24/outline';
import {
  useCloudConfigs,
  PROVIDER_LABELS,
  safeParseJSON,
  type CloudConfig,
  type ServiceType,
  type ConfigLevel,
} from '~/plugins/core-admin/pages/admin/cloud-config-core';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const PROMPT_TYPE: ServiceType = 'prompt_template';

const SAMPLE_DATA: Record<string, Record<string, string>> = {
  aurabot_system: {
    tenantName: 'Acme Corp',
    context: '[page context here]',
    tools: '[available tools]',
  },
  aurabot_context: {
    pageType: 'list',
    modelCode: 'sales_order',
    breadcrumb: 'Sales > Orders',
    recordData: '{"orderNo": "SO-001"}',
  },
  aurabot_tool_hint: {},
};

const TEMPLATE_DESCRIPTIONS: Record<string, string> = {
  aurabot_system: 'Main system prompt that defines AuraBot personality and capabilities',
  aurabot_context: 'Injects current page context (model, breadcrumb, record data) into the prompt',
  aurabot_tool_hint: 'Instructions for AuraBot on how and when to use available tools',
};

// ---------------------------------------------------------------------------
// Utility functions
// ---------------------------------------------------------------------------

function extractVariables(template: string): string[] {
  const matches = template.match(/\{\{(\w+)\}\}/g);
  if (!matches) return [];
  const unique = [...new Set(matches.map((m) => m.replace(/\{\{|\}\}/g, '')))];
  return unique;
}

function renderTemplate(template: string, vars: Record<string, string>): string {
  return template.replace(/\{\{(\w+)\}\}/g, (match, key) => {
    return vars[key] !== undefined ? vars[key] : match;
  });
}

function timeAgo(dateStr?: string): string {
  if (!dateStr) return 'Unknown';
  const diff = Date.now() - new Date(dateStr).getTime();
  const minutes = Math.floor(diff / 60000);
  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

// ---------------------------------------------------------------------------
// Route meta
// ---------------------------------------------------------------------------

export function meta() {
  return [
    { title: 'Prompt Templates - AuraBot' },
    { name: 'description', content: 'Manage AuraBot prompt templates' },
  ];
}

// ---------------------------------------------------------------------------
// Main Page Component
// ---------------------------------------------------------------------------

export default function PromptTemplatesPage() {
  const {
    configs,
    loading,
    level,
    setLevel,
    editingConfig,
    handleCreate,
    handleEdit,
    handleDelete,
    handleSave,
    loadConfigs,
  } = useCloudConfigs();

  const promptConfigs = configs.filter((c) => c.serviceType === PROMPT_TYPE);

  // Track which config is selected in the right panel (independent of modal editing)
  const [selectedPid, setSelectedPid] = useState<string | null>(null);
  const selectedConfig = promptConfigs.find((c) => c.pid === selectedPid) || null;

  const handleSelectTemplate = (config: CloudConfig) => {
    setSelectedPid(config.pid);
    // Also set as editing in the hook so handleSave knows the pid
    handleEdit(config);
  };

  const handleCreateNew = () => {
    setSelectedPid(null);
    handleCreate();
  };

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900" data-testid="prompt-templates-page">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
        <div className="mx-auto max-w-7xl px-6 py-5">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-gradient-to-br from-amber-500 to-orange-600">
                <DocumentTextIcon className="h-5 w-5 text-white" />
              </div>
              <div>
                <h1 className="text-xl font-bold text-gray-900 dark:text-white">
                  Prompt Templates
                </h1>
                <p className="mt-0.5 text-sm text-gray-500 dark:text-gray-400">
                  Manage system prompts, context templates, and tool hints for AuraBot
                </p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              {/* Level toggle */}
              <div className="inline-flex rounded-lg border border-gray-200 bg-gray-50 p-0.5 dark:border-gray-600 dark:bg-gray-700">
                {(['platform', 'tenant'] as const).map((lv) => (
                  <button
                    key={lv}
                    onClick={() => setLevel(lv)}
                    data-testid={`prompt-level-${lv.toLowerCase()}`}
                    className={`rounded-md px-3 py-1.5 text-xs font-medium transition-colors ${
                      level === lv
                        ? 'bg-white text-amber-600 shadow-sm dark:bg-gray-600 dark:text-amber-400'
                        : 'text-gray-500 hover:text-gray-700 dark:text-gray-400'
                    }`}
                  >
                    {lv === 'platform' ? 'Platform' : 'Tenant'}
                  </button>
                ))}
              </div>
              <button
                onClick={handleCreateNew}
                data-testid="prompt-add-btn"
                className="flex items-center gap-2 rounded-lg bg-amber-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-amber-700"
              >
                <PlusIcon className="h-4 w-4" />
                Add Template
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* Split pane */}
      <div className="mx-auto max-w-7xl px-6 py-6">
        <div className="flex flex-col gap-6 lg:flex-row">
          {/* Left Panel — Template List */}
          <div className="w-full shrink-0 lg:w-80">
            <div className="mb-3 text-xs font-medium tracking-wider text-gray-400 uppercase">
              {promptConfigs.length} template{promptConfigs.length !== 1 ? 's' : ''}
            </div>
            {loading ? (
              <div className="flex justify-center py-16">
                <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-amber-600" />
              </div>
            ) : promptConfigs.length === 0 ? (
              <div className="rounded-lg border border-gray-200 bg-white py-12 text-center dark:border-gray-700 dark:bg-gray-800">
                <DocumentTextIcon className="mx-auto mb-3 h-10 w-10 text-gray-300" />
                <p className="text-sm text-gray-500">No templates yet</p>
                <button
                  onClick={handleCreateNew}
                  className="mt-3 text-sm text-amber-600 hover:text-amber-700"
                >
                  + Add your first template
                </button>
              </div>
            ) : (
              <div className="space-y-2">
                {promptConfigs.map((config) => (
                  <TemplateCard
                    key={config.pid}
                    config={config}
                    isSelected={selectedPid === config.pid}
                    onSelect={() => handleSelectTemplate(config)}
                    onDelete={() => handleDelete(config)}
                  />
                ))}
              </div>
            )}
          </div>

          {/* Right Panel — Editor + Preview */}
          <div className="min-w-0 flex-1">
            {selectedConfig ? (
              <TemplateEditor
                config={selectedConfig}
                level={level}
                onSave={handleSave}
                onReload={loadConfigs}
              />
            ) : (
              <div className="flex flex-col items-center justify-center rounded-xl border border-gray-200 bg-white py-24 dark:border-gray-700 dark:bg-gray-800">
                <CodeBracketIcon className="mb-4 h-12 w-12 text-gray-300 dark:text-gray-600" />
                <p className="text-sm text-gray-500 dark:text-gray-400">
                  Select a template to edit, or create a new one
                </p>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// TemplateCard — Left panel item
// ---------------------------------------------------------------------------

function TemplateCard({
  config,
  isSelected,
  onSelect,
  onDelete,
}: {
  config: CloudConfig;
  isSelected: boolean;
  onSelect: () => void;
  onDelete: () => void;
}) {
  const parsed = safeParseJSON(config.config);
  const template = parsed.template || '';
  const description = parsed.description || TEMPLATE_DESCRIPTIONS[config.providerCode] || '';
  const variables = extractVariables(template);
  const label = PROVIDER_LABELS[config.providerCode] || config.providerCode;

  return (
    <button
      onClick={onSelect}
      data-testid={`prompt-card-${config.providerCode}`}
      className={`w-full rounded-lg border p-4 text-left transition-all ${
        isSelected
          ? 'border-amber-400 bg-amber-50 ring-1 ring-amber-400/50 dark:border-amber-500 dark:bg-amber-900/20'
          : 'border-gray-200 bg-white hover:border-gray-300 dark:border-gray-700 dark:bg-gray-800 dark:hover:border-gray-600'
      }`}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <DocumentTextIcon className="h-4 w-4 shrink-0 text-amber-500" />
            <span className="truncate text-sm font-medium text-gray-900 dark:text-white">
              {label}
            </span>
          </div>
          {description && (
            <p className="mt-1.5 line-clamp-2 text-xs text-gray-500 dark:text-gray-400">
              {description.slice(0, 100)}
              {description.length > 100 ? '...' : ''}
            </p>
          )}
          <div className="mt-2 flex items-center gap-3 text-[11px] text-gray-400 dark:text-gray-500">
            <span className="flex items-center gap-1">
              <HashtagIcon className="h-3 w-3" />
              {variables.length} variable{variables.length !== 1 ? 's' : ''}
            </span>
            {config.updatedAt && (
              <span className="flex items-center gap-1">
                <ClockIcon className="h-3 w-3" />
                {timeAgo(config.updatedAt)}
              </span>
            )}
            {!config.enabled && <span className="font-medium text-red-400">Disabled</span>}
          </div>
        </div>
        <button
          onClick={(e) => {
            e.stopPropagation();
            onDelete();
          }}
          className="shrink-0 rounded p-1 text-gray-300 transition-colors hover:text-red-500 dark:text-gray-600 dark:hover:text-red-400"
          title="Delete template"
          data-testid={`prompt-delete-${config.providerCode}`}
        >
          <TrashIcon className="h-4 w-4" />
        </button>
      </div>
    </button>
  );
}

// ---------------------------------------------------------------------------
// TemplateEditor — Right panel with Editor/Preview tabs
// ---------------------------------------------------------------------------

type EditorTab = 'editor' | 'preview';

function TemplateEditor({
  config,
  level,
  onSave,
  onReload,
}: {
  config: CloudConfig;
  level: ConfigLevel;
  onSave: (data: {
    configLevel: ConfigLevel;
    serviceType: ServiceType;
    providerCode: string;
    config: Record<string, string>;
    enabled: boolean;
    priority: number;
  }) => Promise<void>;
  onReload: () => Promise<void>;
}) {
  const parsed = safeParseJSON(config.config);
  const [template, setTemplate] = useState(parsed.template || '');
  const [description, setDescription] = useState(
    parsed.description || TEMPLATE_DESCRIPTIONS[config.providerCode] || '',
  );
  const [activeTab, setActiveTab] = useState<EditorTab>('editor');
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // Reset state when config changes
  const [prevPid, setPrevPid] = useState(config.pid);
  if (config.pid !== prevPid) {
    setPrevPid(config.pid);
    const p = safeParseJSON(config.config);
    setTemplate(p.template || '');
    setDescription(p.description || TEMPLATE_DESCRIPTIONS[config.providerCode] || '');
    setActiveTab('editor');
    setSaved(false);
  }

  const variables = useMemo(() => extractVariables(template), [template]);
  const sampleData = SAMPLE_DATA[config.providerCode] || {};

  const isDirty =
    template !== (parsed.template || '') ||
    description !== (parsed.description || TEMPLATE_DESCRIPTIONS[config.providerCode] || '');

  const handleSaveClick = useCallback(async () => {
    setSaving(true);
    try {
      await onSave({
        configLevel: config.configLevel || level,
        serviceType: PROMPT_TYPE,
        providerCode: config.providerCode,
        config: { template, description },
        enabled: config.enabled,
        priority: config.priority,
      });
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
      await onReload();
    } finally {
      setSaving(false);
    }
  }, [config, level, template, description, onSave, onReload]);

  const handleInsertVariable = useCallback(
    (varName: string) => {
      const textarea = textareaRef.current;
      if (!textarea) return;
      const { selectionStart, selectionEnd } = textarea;
      const insertion = `{{${varName}}}`;
      const newValue = template.slice(0, selectionStart) + insertion + template.slice(selectionEnd);
      setTemplate(newValue);
      // Restore focus and cursor
      requestAnimationFrame(() => {
        textarea.focus();
        const pos = selectionStart + insertion.length;
        textarea.setSelectionRange(pos, pos);
      });
    },
    [template],
  );

  const label = PROVIDER_LABELS[config.providerCode] || config.providerCode;

  return (
    <div
      className="overflow-hidden rounded-xl border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800"
      data-testid="prompt-editor-panel"
    >
      {/* Editor header */}
      <div className="flex items-center justify-between border-b border-gray-200 px-5 py-3 dark:border-gray-700">
        <div className="flex items-center gap-3">
          <h2 className="text-sm font-semibold text-gray-900 dark:text-white">{label}</h2>
          <span className="font-mono text-xs text-gray-400">{config.providerCode}</span>
        </div>
        <div className="flex items-center gap-2">
          {isDirty && (
            <span
              className="text-xs font-medium text-amber-500"
              data-testid="prompt-unsaved-indicator"
            >
              Unsaved changes
            </span>
          )}
          <button
            onClick={handleSaveClick}
            disabled={saving || !isDirty}
            data-testid="prompt-save-btn"
            className={`flex items-center gap-1.5 rounded-lg px-4 py-1.5 text-sm font-medium transition-colors ${
              saved
                ? 'bg-green-600 text-white'
                : 'bg-amber-600 text-white hover:bg-amber-700 disabled:cursor-not-allowed disabled:opacity-40'
            }`}
          >
            {saving ? (
              <ArrowPathIcon className="h-4 w-4 animate-spin" />
            ) : saved ? (
              <CheckIcon className="h-4 w-4" />
            ) : null}
            {saving ? 'Saving...' : saved ? 'Saved!' : 'Save'}
          </button>
        </div>
      </div>

      {/* Tab bar */}
      <div className="flex border-b border-gray-200 px-5 dark:border-gray-700">
        {[
          { key: 'editor' as EditorTab, label: 'Editor', icon: CodeBracketIcon },
          { key: 'preview' as EditorTab, label: 'Preview', icon: EyeIcon },
        ].map((tab) => (
          <button
            key={tab.key}
            onClick={() => setActiveTab(tab.key)}
            data-testid={`prompt-tab-${tab.key}`}
            className={`-mb-px flex items-center gap-1.5 border-b-2 px-4 py-2.5 text-sm font-medium transition-colors ${
              activeTab === tab.key
                ? 'border-amber-500 text-amber-600 dark:text-amber-400'
                : 'border-transparent text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'
            }`}
          >
            <tab.icon className="h-4 w-4" />
            {tab.label}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="p-5">
        {activeTab === 'editor' ? (
          <EditorTabContent
            template={template}
            setTemplate={setTemplate}
            description={description}
            setDescription={setDescription}
            variables={variables}
            config={config}
            textareaRef={textareaRef}
            onInsertVariable={handleInsertVariable}
          />
        ) : (
          <PreviewTabContent
            template={template}
            sampleData={sampleData}
            providerCode={config.providerCode}
          />
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// EditorTabContent
// ---------------------------------------------------------------------------

function EditorTabContent({
  template,
  setTemplate,
  description,
  setDescription,
  variables,
  config,
  textareaRef,
  onInsertVariable,
}: {
  template: string;
  setTemplate: (v: string) => void;
  description: string;
  setDescription: (v: string) => void;
  variables: string[];
  config: CloudConfig;
  textareaRef: React.RefObject<HTMLTextAreaElement | null>;
  onInsertVariable: (varName: string) => void;
}) {
  const lineCount = template.split('\n').length;

  return (
    <div className="space-y-5">
      {/* Template textarea with line number indication */}
      <div>
        <label className="mb-2 block text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
          Template Content
        </label>
        <div className="relative overflow-hidden rounded-lg border border-gray-200 focus-within:border-amber-500 focus-within:ring-2 focus-within:ring-amber-500 dark:border-gray-600">
          <div className="flex">
            {/* Line numbers gutter */}
            <div
              className="shrink-0 border-r border-gray-200 bg-gray-50 px-2 py-3 select-none dark:border-gray-600 dark:bg-gray-900/50"
              aria-hidden="true"
            >
              {Array.from({ length: Math.max(lineCount, 10) }, (_, i) => (
                <div
                  key={i}
                  className="w-6 text-right font-mono text-[11px] leading-[1.625rem] text-gray-400 dark:text-gray-600"
                >
                  {i + 1}
                </div>
              ))}
            </div>
            {/* Textarea */}
            <textarea
              ref={textareaRef}
              value={template}
              onChange={(e) => setTemplate(e.target.value)}
              data-testid="prompt-template-textarea"
              rows={Math.max(lineCount + 2, 12)}
              className="flex-1 resize-none bg-white px-4 py-3 font-mono text-sm leading-[1.625rem] text-gray-900 placeholder:text-gray-400 focus:outline-none dark:bg-gray-800 dark:text-gray-100"
              placeholder="Enter your prompt template here...&#10;Use {{variableName}} for dynamic values."
              spellCheck={false}
            />
          </div>
        </div>
        <div className="mt-1 text-right text-xs text-gray-400">
          {template.length} chars · {lineCount} lines
        </div>
      </div>

      {/* Variables chips */}
      {variables.length > 0 && (
        <div data-testid="prompt-variables-section">
          <label className="mb-2 block text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
            Variables ({variables.length})
          </label>
          <div className="flex flex-wrap gap-2">
            {variables.map((v) => (
              <button
                key={v}
                onClick={() => onInsertVariable(v)}
                title={`Insert {{${v}}} at cursor`}
                data-testid={`prompt-var-chip-${v}`}
                className="inline-flex cursor-pointer items-center gap-1 rounded-md border border-amber-200 bg-amber-50 px-2.5 py-1 font-mono text-xs text-amber-700 transition-colors hover:bg-amber-100 dark:border-amber-700/50 dark:bg-amber-900/30 dark:text-amber-300 dark:hover:bg-amber-900/50"
              >
                <span className="text-amber-400">{'{{'}</span>
                {v}
                <span className="text-amber-400">{'}}'}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Metadata section */}
      <div className="space-y-4 border-t border-gray-100 pt-5 dark:border-gray-700">
        <div>
          <label className="mb-2 block text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
            Description
          </label>
          <input
            type="text"
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            data-testid="prompt-description-input"
            placeholder="Describe the purpose of this template"
            className="w-full rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm text-gray-900 placeholder:text-gray-400 focus:border-amber-500 focus:ring-2 focus:ring-amber-500 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100"
          />
        </div>

        <div className="flex items-center gap-6 text-xs text-gray-400 dark:text-gray-500">
          {config.updatedAt && (
            <span className="flex items-center gap-1">
              <ClockIcon className="h-3.5 w-3.5" />
              Updated {timeAgo(config.updatedAt)}
            </span>
          )}
          <span>
            Provider: <span className="font-mono">{config.providerCode}</span>
          </span>
          <span>Level: {config.configLevel}</span>
          <span className={config.enabled ? 'text-green-500' : 'text-red-400'}>
            {config.enabled ? 'Enabled' : 'Disabled'}
          </span>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// PreviewTabContent
// ---------------------------------------------------------------------------

function PreviewTabContent({
  template,
  sampleData,
  providerCode,
}: {
  template: string;
  sampleData: Record<string, string>;
  providerCode: string;
}) {
  const [customData, setCustomData] = useState<Record<string, string>>({});
  const variables = useMemo(() => extractVariables(template), [template]);

  // Merge sample data with any custom overrides
  const mergedData = useMemo(() => ({ ...sampleData, ...customData }), [sampleData, customData]);
  const rendered = useMemo(() => renderTemplate(template, mergedData), [template, mergedData]);

  if (!template.trim()) {
    return (
      <div className="py-12 text-center text-gray-400">
        <EyeIcon className="mx-auto mb-2 h-8 w-8" />
        <p className="text-sm">No template content to preview</p>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {/* Sample data editor */}
      {variables.length > 0 && (
        <div data-testid="prompt-preview-vars">
          <label className="mb-2 block text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
            Sample Variables
          </label>
          <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
            {variables.map((v) => (
              <div key={v} className="flex items-center gap-2">
                <span className="w-28 shrink-0 truncate text-right font-mono text-xs text-gray-500">
                  {v}:
                </span>
                <input
                  type="text"
                  value={customData[v] ?? sampleData[v] ?? ''}
                  onChange={(e) => setCustomData((prev) => ({ ...prev, [v]: e.target.value }))}
                  data-testid={`prompt-preview-var-${v}`}
                  placeholder={`(unset)`}
                  className="flex-1 rounded border border-gray-200 bg-white px-2 py-1 font-mono text-xs text-gray-900 placeholder:text-gray-300 focus:ring-1 focus:ring-amber-500 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-100"
                />
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Rendered output */}
      <div>
        <label className="mb-2 block text-xs font-medium tracking-wider text-gray-500 uppercase dark:text-gray-400">
          Rendered Output
        </label>
        <div
          className="rounded-lg border border-gray-200 bg-gray-50 p-4 dark:border-gray-600 dark:bg-gray-900/50"
          data-testid="prompt-preview-output"
        >
          <PreviewRenderedText template={template} data={mergedData} />
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// PreviewRenderedText — Highlights resolved/unresolved variables
// ---------------------------------------------------------------------------

function PreviewRenderedText({
  template,
  data,
}: {
  template: string;
  data: Record<string, string>;
}) {
  // Split the template into parts: text and variable references
  const parts: { type: 'text' | 'resolved' | 'unresolved'; content: string; varName?: string }[] =
    [];
  let lastIndex = 0;
  const regex = /\{\{(\w+)\}\}/g;
  let match: RegExpExecArray | null;

  while ((match = regex.exec(template)) !== null) {
    // Text before match
    if (match.index > lastIndex) {
      parts.push({ type: 'text', content: template.slice(lastIndex, match.index) });
    }
    const varName = match[1];
    const isResolved = data[varName] !== undefined && data[varName] !== '';
    parts.push({
      type: isResolved ? 'resolved' : 'unresolved',
      content: isResolved ? data[varName] : match[0],
      varName,
    });
    lastIndex = match.index + match[0].length;
  }
  // Trailing text
  if (lastIndex < template.length) {
    parts.push({ type: 'text', content: template.slice(lastIndex) });
  }

  return (
    <pre className="font-mono text-sm leading-relaxed break-words whitespace-pre-wrap text-gray-800 dark:text-gray-200">
      {parts.map((part, i) => {
        if (part.type === 'text') {
          return <span key={i}>{part.content}</span>;
        }
        if (part.type === 'resolved') {
          return (
            <span
              key={i}
              className="rounded bg-green-100 px-1 text-green-800 dark:bg-green-900/40 dark:text-green-300"
              title={`{{${part.varName}}} = ${part.content}`}
            >
              {part.content}
            </span>
          );
        }
        // unresolved
        return (
          <span
            key={i}
            className="rounded bg-red-100 px-1 text-red-600 dark:bg-red-900/40 dark:text-red-400"
            title={`Unresolved variable: ${part.varName}`}
          >
            {part.content}
          </span>
        );
      })}
    </pre>
  );
}
