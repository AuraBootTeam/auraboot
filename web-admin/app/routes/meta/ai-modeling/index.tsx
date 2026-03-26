/**
 * AI Natural Language Modeling Wizard
 *
 * Step-based wizard for generating AuraBoot DSL configurations from
 * natural language descriptions. Users describe their needs, AI generates
 * complete plugin configuration, then users can refine and deploy.
 *
 * Route: /meta/ai-modeling
 */

import { useState, useCallback, useRef, useEffect } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import {
  SparklesIcon,
  ArrowRightIcon,
  ArrowLeftIcon,
  CheckIcon,
  ArrowPathIcon,
  PaperAirplaneIcon,
  DocumentTextIcon,
  CubeIcon,
  TableCellsIcon,
  CommandLineIcon,
  RectangleGroupIcon,
  Bars3BottomLeftIcon,
  LanguageIcon,
} from '@heroicons/react/24/outline';

// ---------------------------------------------------------------------------
// Page meta
// ---------------------------------------------------------------------------

type MetaArgs = Record<string, unknown>;

export function meta({}: MetaArgs) {
  return [
    { title: 'AI Modeling - AuraBoot' },
    { name: 'description', content: 'AI Natural Language Modeling' },
  ];
}

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface Resources {
  models: any[];
  fields: any[];
  bindings: any[];
  commands: any[];
  pages: any[];
  menus: any[];
  i18n: any[];
  permissions: any[];
}

interface GenerateResponse {
  sessionId?: string;
  pluginCode: string;
  summary: string;
  resources: Resources;
  validationErrors?: string[];
  tokenUsage?: {
    inputTokens: number;
    outputTokens: number;
    estimatedCostUsd: number;
  };
}

interface GenerateOptions {
  generatePages: boolean;
  generateCommands: boolean;
  generateMenus: boolean;
  generateI18n: boolean;
  generateBindings: boolean;
}

// ---------------------------------------------------------------------------
// API helpers
// ---------------------------------------------------------------------------

async function apiGenerate(
  description: string,
  options: GenerateOptions,
): Promise<GenerateResponse> {
  const resp = await fetch('/api/agent/nl-modeling/generate', {
    method: 'post',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ description, options }),
  });
  if (!resp.ok) throw new Error(`Generate failed: ${resp.status}`);
  return resp.json();
}

async function apiRefine(
  sessionId: string | null,
  instruction: string,
  currentResources: Resources,
): Promise<GenerateResponse> {
  const resp = await fetch('/api/agent/nl-modeling/refine', {
    method: 'post',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, instruction, currentResources }),
  });
  if (!resp.ok) throw new Error(`Refine failed: ${resp.status}`);
  return resp.json();
}

async function apiApply(pluginCode: string, resources: Resources): Promise<any> {
  const resp = await fetch('/api/agent/nl-modeling/apply', {
    method: 'post',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ pluginCode, resources }),
  });
  if (!resp.ok) throw new Error(`Apply failed: ${resp.status}`);
  return resp.json();
}

// ---------------------------------------------------------------------------
// Step constants
// ---------------------------------------------------------------------------

const STEPS = [
  { key: 'describe', labelKey: 'nlModeling.step.describe' },
  { key: 'generating', labelKey: 'nlModeling.step.generating' },
  { key: 'preview', labelKey: 'nlModeling.step.preview' },
  { key: 'deploy', labelKey: 'nlModeling.step.deploy' },
] as const;

type StepKey = (typeof STEPS)[number]['key'];

// ---------------------------------------------------------------------------
// Example prompts
// ---------------------------------------------------------------------------

const EXAMPLE_PROMPTS = [
  {
    labelKey: 'nlModeling.example.customerMgmt',
    text: '我需要一个客户管理模块，包含公司名称、联系人姓名、联系电话、邮箱、地址、行业分类和跟进记录',
  },
  {
    labelKey: 'nlModeling.example.projectMgmt',
    text: '创建一个项目管理模块，需要项目名称、负责人、开始日期、结束日期、预算、状态（草稿/进行中/已完成/已取消）和项目描述',
  },
  {
    labelKey: 'nlModeling.example.assetMgmt',
    text: '设计一个资产管理模块，包含资产名称、资产编号、资产类型（电子设备/办公家具/交通工具）、购入日期、购入金额、当前状态（在用/维修/报废）、所属部门',
  },
];

// ---------------------------------------------------------------------------
// Main component
// ---------------------------------------------------------------------------

export default function AiModelingPage() {
  const { t } = useI18n();
  const [step, setStep] = useState<StepKey>('describe');
  const [description, setDescription] = useState('');
  const [options, setOptions] = useState<GenerateOptions>({
    generatePages: true,
    generateCommands: true,
    generateMenus: true,
    generateI18n: true,
    generateBindings: true,
  });
  const [result, setResult] = useState<GenerateResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [refineInput, setRefineInput] = useState('');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [applyResult, setApplyResult] = useState<any>(null);
  const [activeTab, setActiveTab] = useState<string>('models');

  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (step === 'describe' && textareaRef.current) {
      textareaRef.current.focus();
    }
  }, [step]);

  // ---- Handlers ----

  const handleGenerate = useCallback(async () => {
    if (!description.trim()) return;
    setError(null);
    setLoading(true);
    setStep('generating');

    try {
      const resp = await apiGenerate(description, options);
      setResult(resp);
      if (resp.sessionId) {
        setSessionId(resp.sessionId);
      }
      if (resp.validationErrors && resp.validationErrors.length > 0) {
        setError(resp.validationErrors.join('; '));
        setStep('describe');
      } else {
        setStep('preview');
      }
    } catch (e: any) {
      setError(e.message || 'Generation failed');
      setStep('describe');
    } finally {
      setLoading(false);
    }
  }, [description, options]);

  const handleRefine = useCallback(async () => {
    if (!refineInput.trim() || !result?.resources) return;
    setLoading(true);
    setError(null);

    try {
      const resp = await apiRefine(sessionId, refineInput, result.resources);
      if (resp.sessionId) {
        setSessionId(resp.sessionId);
      }
      if (resp.validationErrors && resp.validationErrors.length > 0) {
        setError(resp.validationErrors.join('; '));
      } else {
        setResult(resp);
        setRefineInput('');
      }
    } catch (e: any) {
      setError(e.message || 'Refinement failed');
    } finally {
      setLoading(false);
    }
  }, [refineInput, result, sessionId]);

  const handleApply = useCallback(async () => {
    if (!result?.pluginCode || !result?.resources) return;
    setLoading(true);
    setError(null);

    try {
      const resp = await apiApply(result.pluginCode, result.resources);
      setApplyResult(resp);
      setStep('deploy');
    } catch (e: any) {
      setError(e.message || 'Apply failed');
    } finally {
      setLoading(false);
    }
  }, [result]);

  const handleReset = useCallback(() => {
    setStep('describe');
    setDescription('');
    setResult(null);
    setError(null);
    setApplyResult(null);
    setRefineInput('');
    setSessionId(null);
  }, []);

  // ---- Render ----

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-900">
      {/* Header */}
      <div className="border-b border-gray-200 bg-white px-6 py-4 dark:border-gray-700 dark:bg-gray-800">
        <div className="flex items-center gap-3">
          <SparklesIcon className="h-7 w-7 text-indigo-500" />
          <div>
            <h1 className="text-xl font-semibold text-gray-900 dark:text-white">
              {t('nlModeling.title')}
            </h1>
            <p className="text-sm text-gray-500 dark:text-gray-400">{t('nlModeling.subtitle')}</p>
          </div>
        </div>

        {/* Step indicator */}
        <div className="mt-4 flex items-center gap-2">
          {STEPS.map((s, idx) => (
            <div key={s.key} className="flex items-center gap-2">
              <div
                className={`flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-medium ${
                  step === s.key
                    ? 'bg-indigo-100 text-indigo-700 dark:bg-indigo-900/40 dark:text-indigo-300'
                    : STEPS.findIndex((x) => x.key === step) > idx
                      ? 'bg-green-100 text-green-700 dark:bg-green-900/40 dark:text-green-300'
                      : 'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400'
                }`}
              >
                <span>{idx + 1}</span>
                <span>{t(s.labelKey)}</span>
              </div>
              {idx < STEPS.length - 1 && <ArrowRightIcon className="h-3 w-3 text-gray-400" />}
            </div>
          ))}
        </div>
      </div>

      {/* Content */}
      <div className="mx-auto max-w-6xl px-6 py-6">
        {error && (
          <div className="mb-4 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300">
            {error}
          </div>
        )}

        {/* Step 1: Describe */}
        {step === 'describe' && (
          <DescribeStep
            description={description}
            setDescription={setDescription}
            options={options}
            setOptions={setOptions}
            onGenerate={handleGenerate}
            loading={loading}
            textareaRef={textareaRef}
            t={t}
          />
        )}

        {/* Step 2: Generating */}
        {step === 'generating' && <GeneratingStep t={t} />}

        {/* Step 3: Preview & Refine */}
        {step === 'preview' && result && (
          <PreviewStep
            result={result}
            activeTab={activeTab}
            setActiveTab={setActiveTab}
            refineInput={refineInput}
            setRefineInput={setRefineInput}
            onRefine={handleRefine}
            onApply={handleApply}
            onReset={handleReset}
            loading={loading}
            t={t}
          />
        )}

        {/* Step 4: Deploy */}
        {step === 'deploy' && (
          <DeployStep
            applyResult={applyResult}
            pluginCode={result?.pluginCode}
            onReset={handleReset}
            t={t}
          />
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 1: Describe
// ---------------------------------------------------------------------------

function DescribeStep({
  description,
  setDescription,
  options,
  setOptions,
  onGenerate,
  loading,
  textareaRef,
  t,
}: {
  description: string;
  setDescription: (v: string) => void;
  options: GenerateOptions;
  setOptions: (v: GenerateOptions) => void;
  onGenerate: () => void;
  loading: boolean;
  textareaRef: React.RefObject<HTMLTextAreaElement | null>;
  t: (key: string) => string;
}) {
  return (
    <div className="space-y-6">
      {/* Main input */}
      <div className="rounded-xl border border-gray-200 bg-white p-6 dark:border-gray-700 dark:bg-gray-800">
        <label className="mb-2 block text-sm font-medium text-gray-700 dark:text-gray-300">
          {t('nlModeling.describeLabel')}
        </label>
        <textarea
          ref={textareaRef}
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder={t('nlModeling.describePlaceholder')}
          rows={6}
          className="w-full resize-none rounded-lg border border-gray-300 bg-white px-4 py-3 text-sm text-gray-900 focus:border-transparent focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          onKeyDown={(e) => {
            if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
              onGenerate();
            }
          }}
        />
        <p className="mt-1 text-xs text-gray-400">{t('nlModeling.describeHint')}</p>
      </div>

      {/* Example prompts */}
      <div>
        <h3 className="mb-2 text-sm font-medium text-gray-500 dark:text-gray-400">
          {t('nlModeling.examples')}
        </h3>
        <div className="grid grid-cols-1 gap-3 md:grid-cols-3">
          {EXAMPLE_PROMPTS.map((ex, idx) => (
            <button
              key={idx}
              onClick={() => setDescription(ex.text)}
              className="rounded-lg border border-gray-200 bg-white p-3 text-left text-xs text-gray-600 transition-colors hover:border-indigo-300 hover:bg-indigo-50 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-300 dark:hover:border-indigo-600 dark:hover:bg-indigo-900/20"
            >
              <span className="mb-1 block font-medium text-gray-900 dark:text-white">
                {t(ex.labelKey)}
              </span>
              <span className="line-clamp-2">{ex.text}</span>
            </button>
          ))}
        </div>
      </div>

      {/* Options */}
      <div className="rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800">
        <h3 className="mb-3 text-sm font-medium text-gray-700 dark:text-gray-300">
          {t('nlModeling.optionsTitle')}
        </h3>
        <div className="flex flex-wrap gap-4">
          {(
            [
              ['generatePages', 'nlModeling.option.pages'],
              ['generateCommands', 'nlModeling.option.commands'],
              ['generateMenus', 'nlModeling.option.menus'],
              ['generateI18n', 'nlModeling.option.i18n'],
              ['generateBindings', 'nlModeling.option.bindings'],
            ] as const
          ).map(([key, labelKey]) => (
            <label
              key={key}
              className="flex cursor-pointer items-center gap-2 text-sm text-gray-600 dark:text-gray-300"
            >
              <input
                type="checkbox"
                checked={options[key]}
                onChange={(e) => setOptions({ ...options, [key]: e.target.checked })}
                className="rounded border-gray-300 text-indigo-600 focus:ring-indigo-500"
              />
              {t(labelKey)}
            </label>
          ))}
        </div>
      </div>

      {/* Generate button */}
      <div className="flex justify-end">
        <button
          onClick={onGenerate}
          disabled={!description.trim() || loading}
          className="flex items-center gap-2 rounded-lg bg-indigo-600 px-6 py-2.5 text-sm font-medium text-white transition-colors hover:bg-indigo-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          <SparklesIcon className="h-4 w-4" />
          {t('nlModeling.generateBtn')}
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 2: Generating (loading)
// ---------------------------------------------------------------------------

function GeneratingStep({ t }: { t: (key: string) => string }) {
  return (
    <div className="flex flex-col items-center justify-center py-20">
      <div className="relative">
        <SparklesIcon className="h-16 w-16 animate-pulse text-indigo-500" />
        <div className="absolute inset-0 h-16 w-16 animate-spin rounded-full border-4 border-indigo-200 border-t-indigo-500 dark:border-indigo-800" />
      </div>
      <h2 className="mt-6 text-lg font-semibold text-gray-900 dark:text-white">
        {t('nlModeling.generating')}
      </h2>
      <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">
        {t('nlModeling.generatingHint')}
      </p>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 3: Preview & Refine
// ---------------------------------------------------------------------------

const RESOURCE_TABS = [
  { key: 'models', icon: CubeIcon, labelKey: 'nlModeling.tab.models' },
  { key: 'fields', icon: TableCellsIcon, labelKey: 'nlModeling.tab.fields' },
  { key: 'commands', icon: CommandLineIcon, labelKey: 'nlModeling.tab.commands' },
  { key: 'pages', icon: RectangleGroupIcon, labelKey: 'nlModeling.tab.pages' },
  { key: 'menus', icon: Bars3BottomLeftIcon, labelKey: 'nlModeling.tab.menus' },
  { key: 'i18n', icon: LanguageIcon, labelKey: 'nlModeling.tab.i18n' },
];

function PreviewStep({
  result,
  activeTab,
  setActiveTab,
  refineInput,
  setRefineInput,
  onRefine,
  onApply,
  onReset,
  loading,
  t,
}: {
  result: GenerateResponse;
  activeTab: string;
  setActiveTab: (v: string) => void;
  refineInput: string;
  setRefineInput: (v: string) => void;
  onRefine: () => void;
  onApply: () => void;
  onReset: () => void;
  loading: boolean;
  t: (key: string) => string;
}) {
  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
      {/* Left: Refine chat */}
      <div className="space-y-4 lg:col-span-1">
        <div className="rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800">
          <h3 className="mb-2 text-sm font-semibold text-gray-900 dark:text-white">
            {t('nlModeling.summary')}
          </h3>
          <p className="text-sm text-gray-600 dark:text-gray-300">{result.summary}</p>
          <div className="mt-3 flex flex-wrap gap-2">
            <ResourceBadge
              label={t('nlModeling.tab.models')}
              count={result.resources?.models?.length || 0}
            />
            <ResourceBadge
              label={t('nlModeling.tab.fields')}
              count={result.resources?.fields?.length || 0}
            />
            <ResourceBadge
              label={t('nlModeling.tab.commands')}
              count={result.resources?.commands?.length || 0}
            />
            <ResourceBadge
              label={t('nlModeling.tab.pages')}
              count={result.resources?.pages?.length || 0}
            />
          </div>
          {result.tokenUsage && (
            <p className="mt-2 text-xs text-gray-400">
              Tokens: {result.tokenUsage.inputTokens} in / {result.tokenUsage.outputTokens} out
            </p>
          )}
        </div>

        {/* Refine input */}
        <div className="rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800">
          <h3 className="mb-2 text-sm font-semibold text-gray-900 dark:text-white">
            {t('nlModeling.refineTitle')}
          </h3>
          <textarea
            value={refineInput}
            onChange={(e) => setRefineInput(e.target.value)}
            placeholder={t('nlModeling.refinePlaceholder')}
            rows={3}
            className="w-full resize-none rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm text-gray-900 focus:border-transparent focus:ring-2 focus:ring-indigo-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
                onRefine();
              }
            }}
          />
          <button
            onClick={onRefine}
            disabled={!refineInput.trim() || loading}
            className="mt-2 flex items-center gap-1.5 rounded-lg bg-indigo-50 px-3 py-1.5 text-sm font-medium text-indigo-700 transition-colors hover:bg-indigo-100 disabled:opacity-50 dark:bg-indigo-900/30 dark:text-indigo-300 dark:hover:bg-indigo-900/50"
          >
            <PaperAirplaneIcon className="h-3.5 w-3.5" />
            {t('nlModeling.refineBtn')}
          </button>
        </div>

        {/* Actions */}
        <div className="flex gap-2">
          <button
            onClick={onReset}
            className="flex flex-1 items-center justify-center gap-1.5 rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
          >
            <ArrowLeftIcon className="h-4 w-4" />
            {t('nlModeling.startOver')}
          </button>
          <button
            onClick={onApply}
            disabled={loading}
            className="flex flex-1 items-center justify-center gap-1.5 rounded-lg bg-green-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-green-700 disabled:opacity-50"
          >
            <CheckIcon className="h-4 w-4" />
            {t('nlModeling.applyBtn')}
          </button>
        </div>
      </div>

      {/* Right: Resource preview */}
      <div className="lg:col-span-2">
        <div className="overflow-hidden rounded-xl border border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
          {/* Tabs */}
          <div className="flex overflow-x-auto border-b border-gray-200 dark:border-gray-700">
            {RESOURCE_TABS.map((tab) => {
              const Icon = tab.icon;
              const count = (result.resources as any)?.[tab.key]?.length || 0;
              return (
                <button
                  key={tab.key}
                  onClick={() => setActiveTab(tab.key)}
                  className={`flex items-center gap-1.5 border-b-2 px-4 py-2.5 text-xs font-medium whitespace-nowrap transition-colors ${
                    activeTab === tab.key
                      ? 'border-indigo-500 text-indigo-600 dark:text-indigo-400'
                      : 'border-transparent text-gray-500 hover:text-gray-700 dark:text-gray-400'
                  }`}
                >
                  <Icon className="h-4 w-4" />
                  {t(tab.labelKey)}
                  {count > 0 && (
                    <span className="ml-1 rounded-full bg-gray-100 px-1.5 py-0.5 text-xs dark:bg-gray-700">
                      {count}
                    </span>
                  )}
                </button>
              );
            })}
          </div>

          {/* Tab content */}
          <div className="max-h-[600px] overflow-y-auto p-4">
            <ResourceJsonViewer data={(result.resources as any)?.[activeTab] || []} />
          </div>
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Step 4: Deploy
// ---------------------------------------------------------------------------

function DeployStep({
  applyResult,
  pluginCode,
  onReset,
  t,
}: {
  applyResult: any;
  pluginCode?: string;
  onReset: () => void;
  t: (key: string) => string;
}) {
  const success = applyResult?.data?.success;

  return (
    <div className="flex flex-col items-center justify-center py-20">
      {success ? (
        <>
          <div className="flex h-16 w-16 items-center justify-center rounded-full bg-green-100 dark:bg-green-900/30">
            <CheckIcon className="h-8 w-8 text-green-600 dark:text-green-400" />
          </div>
          <h2 className="mt-4 text-lg font-semibold text-gray-900 dark:text-white">
            {t('nlModeling.deploySuccess')}
          </h2>
          <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">
            {t('nlModeling.deploySuccessHint')}
          </p>
          {pluginCode && (
            <a
              href={`/dynamic/${pluginCode.replace(/_/g, '-')}`}
              className="mt-4 text-sm text-indigo-600 hover:underline dark:text-indigo-400"
            >
              {t('nlModeling.goToModule')}
            </a>
          )}
        </>
      ) : (
        <>
          <div className="flex h-16 w-16 items-center justify-center rounded-full bg-red-100 dark:bg-red-900/30">
            <DocumentTextIcon className="h-8 w-8 text-red-600 dark:text-red-400" />
          </div>
          <h2 className="mt-4 text-lg font-semibold text-gray-900 dark:text-white">
            {t('nlModeling.deployFailed')}
          </h2>
          <p className="mt-2 text-sm text-red-500">
            {applyResult?.message || applyResult?.data?.errorMessage || 'Unknown error'}
          </p>
        </>
      )}
      <button
        onClick={onReset}
        className="mt-6 flex items-center gap-2 rounded-lg border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:text-gray-300 dark:hover:bg-gray-700"
      >
        <ArrowPathIcon className="h-4 w-4" />
        {t('nlModeling.startOver')}
      </button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Helper components
// ---------------------------------------------------------------------------

function ResourceBadge({ label, count }: { label: string; count: number }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-600 dark:bg-gray-700 dark:text-gray-300">
      {label}: {count}
    </span>
  );
}

function ResourceJsonViewer({ data }: { data: any[] }) {
  if (!data || data.length === 0) {
    return <p className="py-8 text-center text-sm text-gray-400">No resources</p>;
  }

  return (
    <div className="space-y-3">
      {data.map((item: any, idx: number) => (
        <div
          key={idx}
          className="rounded-lg border border-gray-100 bg-gray-50 p-3 dark:border-gray-700 dark:bg-gray-900/50"
        >
          <div className="mb-1 flex items-center gap-2">
            <span className="font-mono text-xs text-indigo-600 dark:text-indigo-400">
              {item.code || item.pageKey || item.key || `#${idx + 1}`}
            </span>
            {item['displayName:zh-CN'] && (
              <span className="text-xs text-gray-500">{item['displayName:zh-CN']}</span>
            )}
            {item['name:zh-CN'] && (
              <span className="text-xs text-gray-500">{item['name:zh-CN']}</span>
            )}
            {item.dataType && (
              <span className="rounded bg-blue-100 px-1.5 py-0.5 text-[10px] text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
                {item.dataType}
              </span>
            )}
            {item.type && typeof item.type === 'string' && (
              <span className="rounded bg-purple-100 px-1.5 py-0.5 text-[10px] text-purple-700 dark:bg-purple-900/30 dark:text-purple-300">
                {item.type}
              </span>
            )}
          </div>
          <pre className="max-h-32 overflow-x-auto font-mono text-[11px] whitespace-pre-wrap text-gray-500 dark:text-gray-400">
            {JSON.stringify(item, null, 2)}
          </pre>
        </div>
      ))}
    </div>
  );
}
