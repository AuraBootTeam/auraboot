import React, { useState, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useToken as useAuthToken } from '~/contexts/AuthContext';
import {
  analyzeIntent,
  generatePlugin,
  deployPlugin,
  type IntentAnalysisResult,
  type PluginGenerateResult,
  type PluginDeployResult,
} from '~/shared/services/intent';

// ---- Step indicators ----
const STEPS = ['input', 'analysis', 'config', 'deploy'] as const;
type Step = (typeof STEPS)[number];

function StepIndicator({ current }: { current: Step }) {
  const { t } = useI18n();
  const labels: Record<Step, string> = {
    input: t('intent.step.input'),
    analysis: t('intent.step.analysis'),
    config: t('intent.step.config'),
    deploy: t('intent.step.deploy'),
  };

  return (
    <nav className="mb-8 flex items-center gap-2">
      {STEPS.map((step, idx) => {
        const isActive = step === current;
        const isPast = STEPS.indexOf(current) > idx;
        return (
          <React.Fragment key={step}>
            {idx > 0 && <div className={`h-0.5 w-8 ${isPast ? 'bg-blue-500' : 'bg-gray-300'}`} />}
            <div className="flex items-center gap-1.5">
              <div
                className={`flex h-7 w-7 items-center justify-center rounded-full text-sm font-medium ${isActive ? 'bg-blue-600 text-white' : isPast ? 'bg-blue-500 text-white' : 'bg-gray-200 text-gray-500'}`}
              >
                {idx + 1}
              </div>
              <span
                className={`text-sm ${isActive ? 'font-semibold text-gray-900' : 'text-gray-500'}`}
              >
                {labels[step]}
              </span>
            </div>
          </React.Fragment>
        );
      })}
    </nav>
  );
}

// ---- Step 1: Input ----

function InputStep({
  onAnalyze,
  loading,
}: {
  onAnalyze: (content: string, format: 'text' | 'markdown') => void;
  loading: boolean;
}) {
  const { t } = useI18n();
  const [content, setContent] = useState('');
  const [format, setFormat] = useState<'text' | 'markdown'>('text');

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold text-gray-900">{t('intent.input.title')}</h2>
      <p className="text-sm text-gray-600">{t('intent.input.description')}</p>

      <div className="flex gap-4">
        <label className="flex items-center gap-1.5">
          <input
            type="radio"
            value="text"
            checked={format === 'text'}
            onChange={() => setFormat('text')}
          />
          <span className="text-sm">{t('intent.input.formatText')}</span>
        </label>
        <label className="flex items-center gap-1.5">
          <input
            type="radio"
            value="markdown"
            checked={format === 'markdown'}
            onChange={() => setFormat('markdown')}
          />
          <span className="text-sm">{t('intent.input.formatMarkdown')}</span>
        </label>
      </div>

      <textarea
        className="h-64 w-full resize-none rounded-lg border p-3 font-mono text-sm focus:border-blue-500 focus:ring-2 focus:ring-blue-500"
        placeholder={t('intent.input.placeholder')}
        value={content}
        onChange={(e) => setContent(e.target.value)}
      />

      <button
        className="rounded-lg bg-blue-600 px-6 py-2 text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
        disabled={!content.trim() || loading}
        onClick={() => onAnalyze(content, format)}
      >
        {loading ? t('intent.input.analyzing') : t('intent.input.analyzeBtn')}
      </button>
    </div>
  );
}

// ---- Step 2: Analysis Preview ----

function AnalysisStep({
  analysis,
  onGenerate,
  onBack,
  loading,
}: {
  analysis: IntentAnalysisResult;
  onGenerate: (pluginCode: string, pluginName: string) => void;
  onBack: () => void;
  loading: boolean;
}) {
  const { t } = useI18n();
  const [pluginCode, setPluginCode] = useState('');
  const [pluginName, setPluginName] = useState('');

  return (
    <div className="space-y-6">
      <h2 className="text-lg font-semibold text-gray-900">{t('intent.analysis.title')}</h2>
      <p className="text-sm text-gray-600">{analysis.summary}</p>

      {/* Entities */}
      <div>
        <h3 className="text-md mb-2 font-medium text-gray-800">
          {t('intent.analysis.entities')} ({analysis.entities.length})
        </h3>
        <div className="grid gap-3">
          {analysis.entities.map((entity) => (
            <div key={entity.code} className="rounded-lg border bg-white p-3">
              <div className="font-medium text-gray-900">{entity.name}</div>
              <div className="mb-2 text-xs text-gray-500">
                {entity.code} - {entity.description}
              </div>
              <div className="flex flex-wrap gap-1.5">
                {entity.fields.map((f) => (
                  <span
                    key={f.code}
                    className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-700"
                    title={`${f.type}${f.required ? ' (required)' : ''}`}
                  >
                    {f.name}
                  </span>
                ))}
              </div>
            </div>
          ))}
        </div>
      </div>

      {/* Relationships */}
      {analysis.relationships.length > 0 && (
        <div>
          <h3 className="text-md mb-2 font-medium text-gray-800">
            {t('intent.analysis.relationships')} ({analysis.relationships.length})
          </h3>
          <div className="space-y-1">
            {analysis.relationships.map((rel, idx) => (
              <div key={idx} className="text-sm text-gray-700">
                {rel.fromEntity} → {rel.toEntity} ({rel.type})
              </div>
            ))}
          </div>
        </div>
      )}

      {/* State Machines */}
      {analysis.stateMachines.length > 0 && (
        <div>
          <h3 className="text-md mb-2 font-medium text-gray-800">
            {t('intent.analysis.stateMachines')} ({analysis.stateMachines.length})
          </h3>
          {analysis.stateMachines.map((sm, idx) => (
            <div key={idx} className="mb-2 rounded-lg border bg-white p-3">
              <div className="text-sm font-medium">
                {sm.entityCode}.{sm.fieldCode}
              </div>
              <div className="mt-1 flex gap-2">
                {sm.states.map((state) => (
                  <span
                    key={state}
                    className="rounded bg-blue-50 px-2 py-0.5 text-xs text-blue-700"
                  >
                    {state}
                  </span>
                ))}
              </div>
              <div className="mt-2 space-y-0.5">
                {sm.transitions.map((tr, ti) => (
                  <div key={ti} className="text-xs text-gray-600">
                    {tr.from} → {tr.to} ({tr.action})
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Business Rules */}
      {analysis.rules.length > 0 && (
        <div>
          <h3 className="text-md mb-2 font-medium text-gray-800">
            {t('intent.analysis.rules')} ({analysis.rules.length})
          </h3>
          {analysis.rules.map((rule, idx) => (
            <div key={idx} className="text-sm text-gray-700">
              [{rule.ruleType}] {rule.entityCode}: {rule.description}
            </div>
          ))}
        </div>
      )}

      {/* Plugin config input */}
      <div className="space-y-3 border-t pt-4">
        <h3 className="text-md font-medium text-gray-800">{t('intent.analysis.pluginConfig')}</h3>
        <div className="grid grid-cols-2 gap-4">
          <div>
            <label className="mb-1 block text-sm text-gray-600">
              {t('intent.analysis.pluginCode')}
            </label>
            <input
              type="text"
              className="w-full rounded-lg border px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500"
              placeholder="e.g. order-mgmt"
              value={pluginCode}
              onChange={(e) =>
                setPluginCode(e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '-'))
              }
            />
          </div>
          <div>
            <label className="mb-1 block text-sm text-gray-600">
              {t('intent.analysis.pluginName')}
            </label>
            <input
              type="text"
              className="w-full rounded-lg border px-3 py-2 text-sm focus:ring-2 focus:ring-blue-500"
              placeholder="e.g. Order Management"
              value={pluginName}
              onChange={(e) => setPluginName(e.target.value)}
            />
          </div>
        </div>
      </div>

      <div className="flex gap-3">
        <button
          className="rounded-lg border px-4 py-2 text-gray-700 hover:bg-gray-50"
          onClick={onBack}
        >
          {t('intent.common.back')}
        </button>
        <button
          className="rounded-lg bg-blue-600 px-6 py-2 text-white hover:bg-blue-700 disabled:opacity-50"
          disabled={!pluginCode.trim() || !pluginName.trim() || loading}
          onClick={() => onGenerate(pluginCode, pluginName)}
        >
          {loading ? t('intent.analysis.generating') : t('intent.analysis.generateBtn')}
        </button>
      </div>
    </div>
  );
}

// ---- Step 3: Config Review ----

function ConfigStep({
  generated,
  onDeploy,
  onBack,
  loading,
}: {
  generated: PluginGenerateResult;
  onDeploy: () => void;
  onBack: () => void;
  loading: boolean;
}) {
  const { t } = useI18n();
  const [expandedFile, setExpandedFile] = useState<string | null>(null);

  return (
    <div className="space-y-4">
      <h2 className="text-lg font-semibold text-gray-900">{t('intent.config.title')}</h2>
      <p className="text-sm text-gray-600">{generated.summary}</p>

      <div className="grid grid-cols-4 gap-3">
        <StatCard label={t('intent.config.models')} value={generated.modelCount} />
        <StatCard label={t('intent.config.fields')} value={generated.fieldCount} />
        <StatCard label={t('intent.config.commands')} value={generated.commandCount} />
        <StatCard label={t('intent.config.pages')} value={generated.pageCount} />
      </div>

      <div className="space-y-2">
        {Object.entries(generated.configs).map(([fileName, content]) => (
          <div key={fileName} className="overflow-hidden rounded-lg border">
            <button
              className="flex w-full items-center justify-between bg-gray-50 px-4 py-2 text-left text-sm font-medium hover:bg-gray-100"
              onClick={() => setExpandedFile(expandedFile === fileName ? null : fileName)}
            >
              <span>{fileName}</span>
              <span className="text-gray-400">{expandedFile === fileName ? '−' : '+'}</span>
            </button>
            {expandedFile === fileName && (
              <pre className="max-h-80 overflow-auto bg-gray-900 p-3 text-xs text-green-400">
                {JSON.stringify(content, null, 2)}
              </pre>
            )}
          </div>
        ))}
      </div>

      <div className="flex gap-3">
        <button
          className="rounded-lg border px-4 py-2 text-gray-700 hover:bg-gray-50"
          onClick={onBack}
        >
          {t('intent.common.back')}
        </button>
        <button
          className="rounded-lg bg-green-600 px-6 py-2 text-white hover:bg-green-700 disabled:opacity-50"
          disabled={loading}
          onClick={onDeploy}
        >
          {loading ? t('intent.config.deploying') : t('intent.config.deployBtn')}
        </button>
      </div>
    </div>
  );
}

function StatCard({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border bg-white p-3 text-center">
      <div className="text-2xl font-bold text-blue-600">{value}</div>
      <div className="text-xs text-gray-500">{label}</div>
    </div>
  );
}

// ---- Step 4: Deploy Result ----

function DeployStep({ result, onReset }: { result: PluginDeployResult; onReset: () => void }) {
  const { t } = useI18n();

  return (
    <div className="space-y-6 py-8 text-center">
      <div className={`text-5xl ${result.success ? 'text-green-500' : 'text-red-500'}`}>
        {result.success ? '✓' : '✗'}
      </div>
      <h2 className="text-xl font-semibold text-gray-900">
        {result.success ? t('intent.deploy.success') : t('intent.deploy.failure')}
      </h2>
      <p className="text-gray-600">{result.message}</p>

      {result.success && (
        <div className="flex justify-center gap-6 text-sm text-gray-500">
          <span>
            {t('intent.config.models')}: {result.modelsCreated}
          </span>
          <span>
            {t('intent.config.fields')}: {result.fieldsCreated}
          </span>
          <span>
            {t('intent.config.commands')}: {result.commandsCreated}
          </span>
          <span>
            {t('intent.config.pages')}: {result.pagesCreated}
          </span>
          <span>
            {t('intent.deploy.menus')}: {result.menusCreated}
          </span>
        </div>
      )}

      <button
        className="rounded-lg bg-blue-600 px-6 py-2 text-white hover:bg-blue-700"
        onClick={onReset}
      >
        {t('intent.deploy.newProject')}
      </button>
    </div>
  );
}

// ---- Main component ----

export default function IntentBuilder() {
  const { t } = useI18n();
  const token = useAuthToken();

  const [step, setStep] = useState<Step>('input');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [analysis, setAnalysis] = useState<IntentAnalysisResult | null>(null);
  const [generated, setGenerated] = useState<PluginGenerateResult | null>(null);
  const [deployResult, setDeployResult] = useState<PluginDeployResult | null>(null);

  const handleAnalyze = useCallback(
    async (content: string, format: 'text' | 'markdown') => {
      setLoading(true);
      setError(null);
      try {
        const result = await analyzeIntent({ content, format }, token ?? undefined);
        if (result) {
          setAnalysis(result);
          setStep('analysis');
        } else {
          setError(t('intent.error.analysisFailed'));
        }
      } catch (e) {
        setError(t('intent.error.analysisFailed'));
      } finally {
        setLoading(false);
      }
    },
    [token, t],
  );

  const handleGenerate = useCallback(
    async (pluginCode: string, pluginName: string) => {
      if (!analysis) return;
      setLoading(true);
      setError(null);
      try {
        const result = await generatePlugin(
          { analysis, pluginCode, pluginName },
          token ?? undefined,
        );
        if (result) {
          setGenerated(result);
          setStep('config');
        } else {
          setError(t('intent.error.generateFailed'));
        }
      } catch (e) {
        setError(t('intent.error.generateFailed'));
      } finally {
        setLoading(false);
      }
    },
    [analysis, token, t],
  );

  const handleDeploy = useCallback(async () => {
    if (!generated) return;
    setLoading(true);
    setError(null);
    try {
      const result = await deployPlugin(
        {
          pluginCode: generated.pluginCode,
          pluginName: generated.pluginName,
          configs: generated.configs,
        },
        token ?? undefined,
      );
      if (result) {
        setDeployResult(result);
        setStep('deploy');
      } else {
        setError(t('intent.error.deployFailed'));
      }
    } catch (e) {
      setError(t('intent.error.deployFailed'));
    } finally {
      setLoading(false);
    }
  }, [generated, token, t]);

  const handleReset = useCallback(() => {
    setStep('input');
    setAnalysis(null);
    setGenerated(null);
    setDeployResult(null);
    setError(null);
  }, []);

  return (
    <div className="mx-auto max-w-4xl p-6">
      <h1 className="mb-2 text-2xl font-bold text-gray-900">{t('intent.title')}</h1>
      <p className="mb-6 text-sm text-gray-500">{t('intent.subtitle')}</p>

      <StepIndicator current={step} />

      {error && (
        <div className="mb-4 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {step === 'input' && <InputStep onAnalyze={handleAnalyze} loading={loading} />}

      {step === 'analysis' && analysis && (
        <AnalysisStep
          analysis={analysis}
          onGenerate={handleGenerate}
          onBack={() => setStep('input')}
          loading={loading}
        />
      )}

      {step === 'config' && generated && (
        <ConfigStep
          generated={generated}
          onDeploy={handleDeploy}
          onBack={() => setStep('analysis')}
          loading={loading}
        />
      )}

      {step === 'deploy' && deployResult && (
        <DeployStep result={deployResult} onReset={handleReset} />
      )}
    </div>
  );
}
