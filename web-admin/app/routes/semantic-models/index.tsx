/**
 * /semantic/models — Semantic model catalog + authoring console.
 *
 * Three jobs on one page (all against the governed /api/semantic/* surface,
 * which requires meta.semantic.use / meta.semantic.publish):
 *   1. Catalog — list published models + inspect their metrics & dimensions.
 *   2. Query   — pick metrics/dimensions and run a governed query (proves the
 *                model end-to-end: authored YAML → published → real rows).
 *   3. Author  — a YAML editor with Validate + Publish + a one-click starter,
 *                so a model can be created without hand-rolling curl.
 *
 * This is a bespoke authoring tool (YAML editor + validate/publish/run loop),
 * not a DSL CRUD page — hence a dedicated React route rather than a pages.json
 * entry.
 */

import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router';
import { toast } from 'sonner';
import { useI18n } from '~/contexts/I18nContext';
import {
  fetchSemanticMeta,
  validateSemanticYaml,
  publishSemanticYaml,
  runSemanticQuery,
  EXAMPLE_SEMANTIC_YAML,
  type ModelMeta,
  type MetricMeta,
  type DimensionMeta,
  type SemanticQueryResult,
} from '~/plugins/core-semantic/api/semanticApi';

type Tab = 'browse' | 'author';

function localize(
  label: Record<string, string> | undefined,
  fallback: string,
  locale: string,
): string {
  if (!label) return fallback;
  return label[locale] || label['zh-CN'] || label['en-US'] || fallback;
}

export default function SemanticModelsPage() {
  const { t, locale } = useI18n();
  const [models, setModels] = useState<ModelMeta[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('browse');

  async function reload() {
    setLoading(true);
    setLoadError(null);
    try {
      const meta = await fetchSemanticMeta();
      setModels(meta.models || []);
      if (!selectedCode && meta.models?.length) {
        setSelectedCode(meta.models[0].code);
      }
    } catch (e) {
      setLoadError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void reload();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const selected = useMemo(
    () => models.find((m) => m.code === selectedCode) || null,
    [models, selectedCode],
  );

  return (
    <div
      data-testid="semantic-models-page"
      className="flex h-full flex-col overflow-hidden"
    >
      <header className="flex flex-shrink-0 items-center justify-between border-b border-gray-200 bg-white px-4 py-3 dark:border-gray-700 dark:bg-gray-900">
        <h1 className="text-sm font-semibold text-gray-800 dark:text-gray-100">
          {t('semantic.models.title', undefined, '语义模型')}
        </h1>
        <div className="flex items-center gap-2">
          <Link
            to="/semantic/lineage"
            data-testid="semantic-models-lineage-link"
            className="rounded border border-gray-200 px-2 py-1 text-xs text-gray-600 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300"
          >
            {t('semantic.models.open_lineage', undefined, '数据血缘')}
          </Link>
          <button
            type="button"
            data-testid="semantic-models-reload"
            onClick={() => void reload()}
            className="rounded border border-gray-200 px-2 py-1 text-xs text-gray-600 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300"
          >
            {t('common.refresh', undefined, '刷新')}
          </button>
        </div>
      </header>

      <div className="flex min-h-0 flex-1">
        {/* Left — model list */}
        <aside className="flex w-64 flex-shrink-0 flex-col border-r border-gray-200 bg-gray-50 dark:border-gray-700 dark:bg-gray-950">
          <div className="border-b border-gray-200 px-3 py-2 text-xs font-medium text-gray-500 dark:border-gray-700">
            {t('semantic.models.published', undefined, '已发布模型')}
            {!loading && (
              <span className="ml-1 text-gray-400">({models.length})</span>
            )}
          </div>
          <div className="min-h-0 flex-1 overflow-auto">
            {loading && (
              <div
                data-testid="semantic-models-loading"
                className="px-3 py-3 text-xs text-gray-400"
              >
                {t('common.loading', undefined, '加载中…')}
              </div>
            )}
            {loadError && (
              <div
                data-testid="semantic-models-error"
                className="px-3 py-3 text-xs text-red-500"
              >
                {t('semantic.models.load_failed', undefined, '加载失败')}: {loadError}
              </div>
            )}
            {!loading && !loadError && models.length === 0 && (
              <div
                data-testid="semantic-models-empty"
                className="px-3 py-4 text-xs text-gray-400"
              >
                {t(
                  'semantic.models.empty',
                  undefined,
                  '暂无已发布模型。切到「编辑/发布」用示例创建第一个。',
                )}
              </div>
            )}
            {models.map((m) => (
              <button
                type="button"
                key={m.code}
                data-testid={`semantic-model-item-${m.code}`}
                onClick={() => {
                  setSelectedCode(m.code);
                  setTab('browse');
                }}
                className={`block w-full border-b border-gray-100 px-3 py-2 text-left text-sm dark:border-gray-800 ${
                  m.code === selectedCode
                    ? 'bg-blue-50 font-medium text-blue-700 dark:bg-blue-950 dark:text-blue-300'
                    : 'text-gray-700 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-900'
                }`}
              >
                <div>{localize(m.label, m.code, locale)}</div>
                <div className="text-[11px] text-gray-400">
                  {m.code} · {m.metrics?.length ?? 0}
                  {' '}
                  {t('semantic.models.metrics_short', undefined, '指标')} · {m.dimensions?.length ?? 0}
                  {' '}
                  {t('semantic.models.dims_short', undefined, '维度')}
                </div>
              </button>
            ))}
          </div>
        </aside>

        {/* Right — tabs */}
        <section className="flex min-w-0 flex-1 flex-col">
          <div className="flex flex-shrink-0 gap-4 border-b border-gray-200 px-4 dark:border-gray-700">
            {(['browse', 'author'] as Tab[]).map((tk) => (
              <button
                type="button"
                key={tk}
                data-testid={`semantic-tab-${tk}`}
                onClick={() => setTab(tk)}
                className={`-mb-px border-b-2 py-2 text-sm ${
                  tab === tk
                    ? 'border-blue-500 font-medium text-blue-600 dark:text-blue-400'
                    : 'border-transparent text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'
                }`}
              >
                {tk === 'browse'
                  ? t('semantic.models.tab_browse', undefined, '浏览 / 查询')
                  : t('semantic.models.tab_author', undefined, '编辑 / 发布')}
              </button>
            ))}
          </div>

          <div className="min-h-0 flex-1 overflow-auto p-4">
            {tab === 'browse' ? (
              <BrowsePanel model={selected} locale={locale} t={t} />
            ) : (
              <AuthorPanel t={t} onPublished={() => void reload()} />
            )}
          </div>
        </section>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Browse + query runner
// ---------------------------------------------------------------------------

function BrowsePanel({
  model,
  locale,
  t,
}: {
  model: ModelMeta | null;
  locale: string;
  t: ReturnType<typeof useI18n>['t'];
}) {
  const [pickedMetrics, setPickedMetrics] = useState<string[]>([]);
  const [pickedDims, setPickedDims] = useState<string[]>([]);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<SemanticQueryResult | null>(null);
  const [queryError, setQueryError] = useState<string | null>(null);

  // Reset picks when the model changes.
  useEffect(() => {
    setPickedMetrics(model?.metrics?.[0] ? [model.metrics[0].code] : []);
    setPickedDims([]);
    setResult(null);
    setQueryError(null);
  }, [model?.code]);

  if (!model) {
    return (
      <div
        data-testid="semantic-browse-empty"
        className="flex h-full items-center justify-center text-sm text-gray-400"
      >
        {t('semantic.models.pick_model', undefined, '从左侧选择一个模型')}
      </div>
    );
  }

  function toggle(list: string[], code: string): string[] {
    return list.includes(code) ? list.filter((c) => c !== code) : [...list, code];
  }

  async function run() {
    if (pickedMetrics.length === 0) {
      toast.error(t('semantic.models.pick_metric', undefined, '至少选择一个指标'));
      return;
    }
    setRunning(true);
    setQueryError(null);
    setResult(null);
    try {
      const q = await runSemanticQuery({
        metrics: pickedMetrics.map((c) => `${model!.code}.${c}`),
        dimensions: pickedDims.map((c) => `${model!.code}.${c}`),
        limit: 100,
      });
      setResult(q);
    } catch (e) {
      setQueryError(e instanceof Error ? e.message : String(e));
    } finally {
      setRunning(false);
    }
  }

  const columns = result?.rows?.length ? Object.keys(result.rows[0]) : [];

  return (
    <div className="space-y-4">
      <div>
        <h2 className="text-sm font-semibold text-gray-800 dark:text-gray-100">
          {localize(model.label, model.code, locale)}
          <span className="ml-2 text-xs font-normal text-gray-400">{model.code}</span>
        </h2>
      </div>

      {/* Metrics */}
      <div>
        <div className="mb-1 text-xs font-medium text-gray-500">
          {t('semantic.models.metrics', undefined, '指标')}
        </div>
        <div className="flex flex-wrap gap-2">
          {(model.metrics || []).map((m: MetricMeta) => (
            <label
              key={m.code}
              data-testid={`semantic-metric-${m.code}`}
              className={`cursor-pointer rounded border px-2 py-1 text-xs ${
                pickedMetrics.includes(m.code)
                  ? 'border-blue-400 bg-blue-50 text-blue-700 dark:bg-blue-950 dark:text-blue-300'
                  : 'border-gray-200 text-gray-600 dark:border-gray-700 dark:text-gray-300'
              }`}
            >
              <input
                type="checkbox"
                className="mr-1 align-middle"
                checked={pickedMetrics.includes(m.code)}
                onChange={() => setPickedMetrics((l) => toggle(l, m.code))}
              />
              {localize(m.label, m.code, locale)}
            </label>
          ))}
          {(model.metrics || []).length === 0 && (
            <span className="text-xs text-gray-400">
              {t('semantic.models.no_metrics', undefined, '（无指标）')}
            </span>
          )}
        </div>
      </div>

      {/* Dimensions */}
      <div>
        <div className="mb-1 text-xs font-medium text-gray-500">
          {t('semantic.models.dimensions', undefined, '维度')}
        </div>
        <div className="flex flex-wrap gap-2">
          {(model.dimensions || []).map((d: DimensionMeta) => (
            <label
              key={d.code}
              data-testid={`semantic-dim-${d.code}`}
              className={`cursor-pointer rounded border px-2 py-1 text-xs ${
                pickedDims.includes(d.code)
                  ? 'border-emerald-400 bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300'
                  : 'border-gray-200 text-gray-600 dark:border-gray-700 dark:text-gray-300'
              }`}
            >
              <input
                type="checkbox"
                className="mr-1 align-middle"
                checked={pickedDims.includes(d.code)}
                onChange={() => setPickedDims((l) => toggle(l, d.code))}
              />
              {localize(d.label, d.code, locale)}
            </label>
          ))}
          {(model.dimensions || []).length === 0 && (
            <span className="text-xs text-gray-400">
              {t('semantic.models.no_dims', undefined, '（无维度）')}
            </span>
          )}
        </div>
      </div>

      <button
        type="button"
        data-testid="semantic-run-query"
        onClick={() => void run()}
        disabled={running}
        className="rounded bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
      >
        {running
          ? t('semantic.models.running', undefined, '查询中…')
          : t('semantic.models.run', undefined, '运行查询')}
      </button>

      {queryError && (
        <div
          data-testid="semantic-query-error"
          className="rounded border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-600 dark:border-red-900 dark:bg-red-950"
        >
          {queryError}
        </div>
      )}

      {result && (
        <div data-testid="semantic-query-result" className="space-y-2">
          <div className="text-xs text-gray-500">
            {result.rowcount} {t('semantic.models.rows', undefined, '行')} · {result.durationMs}ms
          </div>
          {result.rows.length > 0 ? (
            <div className="overflow-auto rounded border border-gray-200 dark:border-gray-700">
              <table className="min-w-full text-xs">
                <thead className="bg-gray-50 dark:bg-gray-900">
                  <tr>
                    {columns.map((c) => (
                      <th
                        key={c}
                        className="border-b border-gray-200 px-3 py-1.5 text-left font-medium text-gray-600 dark:border-gray-700 dark:text-gray-300"
                      >
                        {c}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {result.rows.map((row, i) => (
                    <tr key={i} className="odd:bg-white even:bg-gray-50 dark:odd:bg-gray-950 dark:even:bg-gray-900">
                      {columns.map((c) => (
                        <td
                          key={c}
                          className="border-b border-gray-100 px-3 py-1 text-gray-700 dark:border-gray-800 dark:text-gray-300"
                        >
                          {String(row[c] ?? '')}
                        </td>
                      ))}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="text-xs text-gray-400">
              {t('semantic.models.no_rows', undefined, '（无数据）')}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Author (YAML editor + validate + publish)
// ---------------------------------------------------------------------------

function AuthorPanel({
  t,
  onPublished,
}: {
  t: ReturnType<typeof useI18n>['t'];
  onPublished: () => void;
}) {
  const [yaml, setYaml] = useState('');
  const [pluginCode, setPluginCode] = useState('semantic-console');
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState<
    { kind: 'ok' | 'err'; text: string } | null
  >(null);

  async function validate() {
    setBusy(true);
    setStatus(null);
    try {
      const r = await validateSemanticYaml(yaml);
      setStatus({
        kind: 'ok',
        text: t('semantic.models.validate_ok', undefined, '校验通过')
          + `: ${r.modelCode} · ${r.metricCount} ${t('semantic.models.metrics_short', undefined, '指标')} · ${r.dimensionCount} ${t('semantic.models.dims_short', undefined, '维度')}`,
      });
    } catch (e) {
      setStatus({ kind: 'err', text: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  }

  async function publish() {
    setBusy(true);
    setStatus(null);
    try {
      const r = await publishSemanticYaml(yaml, pluginCode);
      setStatus({
        kind: 'ok',
        text: t('semantic.models.publish_ok', undefined, '发布成功') + `: ${r.pid}`,
      });
      toast.success(t('semantic.models.publish_ok', undefined, '发布成功'));
      onPublished();
    } catch (e) {
      setStatus({ kind: 'err', text: e instanceof Error ? e.message : String(e) });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex h-full flex-col gap-3">
      <div className="flex items-center gap-2">
        <button
          type="button"
          data-testid="semantic-load-example"
          onClick={() => setYaml(EXAMPLE_SEMANTIC_YAML)}
          className="rounded border border-gray-200 px-2 py-1 text-xs text-gray-600 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-300"
        >
          {t('semantic.models.load_example', undefined, '载入示例')}
        </button>
        <label className="flex items-center gap-1 text-xs text-gray-500">
          {t('semantic.models.plugin_code', undefined, '归属命名空间')}
          <input
            data-testid="semantic-plugin-code"
            value={pluginCode}
            onChange={(e) => setPluginCode(e.target.value)}
            className="w-40 rounded border border-gray-200 px-2 py-1 text-xs dark:border-gray-700 dark:bg-gray-900"
          />
        </label>
      </div>

      <textarea
        data-testid="semantic-yaml-editor"
        value={yaml}
        onChange={(e) => setYaml(e.target.value)}
        spellCheck={false}
        placeholder={t(
          'semantic.models.yaml_placeholder',
          undefined,
          '在此粘贴 semantic.yml，或点「载入示例」…',
        )}
        className="min-h-0 flex-1 resize-none rounded border border-gray-200 bg-gray-50 p-3 font-mono text-xs text-gray-800 focus:border-blue-400 focus:outline-none dark:border-gray-700 dark:bg-gray-950 dark:text-gray-200"
      />

      <div className="flex items-center gap-2">
        <button
          type="button"
          data-testid="semantic-validate"
          onClick={() => void validate()}
          disabled={busy || !yaml.trim()}
          className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700 hover:bg-gray-50 disabled:opacity-50 dark:border-gray-600 dark:text-gray-200"
        >
          {t('semantic.models.validate', undefined, '校验')}
        </button>
        <button
          type="button"
          data-testid="semantic-publish"
          onClick={() => void publish()}
          disabled={busy || !yaml.trim() || !pluginCode.trim()}
          className="rounded bg-blue-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {t('semantic.models.publish', undefined, '发布')}
        </button>
      </div>

      {status && (
        <div
          data-testid={status.kind === 'ok' ? 'semantic-author-ok' : 'semantic-author-error'}
          className={`rounded border px-3 py-2 text-xs ${
            status.kind === 'ok'
              ? 'border-green-200 bg-green-50 text-green-700 dark:border-green-900 dark:bg-green-950'
              : 'border-red-200 bg-red-50 text-red-600 dark:border-red-900 dark:bg-red-950'
          }`}
        >
          {status.text}
        </div>
      )}
    </div>
  );
}
