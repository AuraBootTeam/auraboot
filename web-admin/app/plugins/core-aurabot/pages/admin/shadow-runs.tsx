/**
 * D.5 Phase 1 — Shadow Runs comparison admin page.
 *
 * Surfaces the Learning Loop's per-draft fidelity / output-match / cost
 * aggregations in one place. Clicking a draft row opens a slide-in drawer
 * that lists the individual shadow runs for that draft side-by-side with
 * their production counterparts.
 *
 * Phase 1 scope (intentionally tight):
 *   - Aggregations table (one row per draft, sorted by latest-at)
 *   - 3 KPI columns: fidelity match rate / output match rate / cost delta
 *   - Drawer drilldown with prod-vs-shadow text diff
 *   - Empty / loading / error states
 *
 * Deferred to Phase 2:
 *   - Charts / sparklines
 *   - Date range / agent / model filters
 *   - CSV export, syntax-highlighted diff viewer, SSE live refresh
 */

import { useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import {
  listShadowRunAggregations,
  type ShadowRunAggregation,
} from '../../services/shadowRunsApi';
import ShadowRunDetailDrawer from '../../components-internal/ShadowRunDetailDrawer';
import { useI18n } from '~/contexts/I18nContext';

function fmtPct(n: number | null | undefined): string {
  if (n === null || n === undefined) return '-';
  return `${(n * 100).toFixed(0)}%`;
}

function fmtCostDelta(n: number | null | undefined): string {
  if (n === null || n === undefined) return '-';
  if (n < 0) return `-$${Math.abs(n).toFixed(4)}`;
  if (n > 0) return `+$${n.toFixed(4)}`;
  return `$${n.toFixed(4)}`;
}

function relativeTime(iso: string | null): string {
  if (!iso) return '-';
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return iso;
  const diff = Date.now() - t;
  const sec = Math.round(diff / 1000);
  if (sec < 60) return `${sec}s`;
  const min = Math.round(sec / 60);
  if (min < 60) return `${min}m`;
  const hr = Math.round(min / 60);
  if (hr < 24) return `${hr}h`;
  return `${Math.round(hr / 24)}d`;
}

function rateBadgeClass(rate: number | null | undefined): string {
  if (rate === null || rate === undefined) return 'text-gray-500';
  if (rate >= 0.9) return 'text-emerald-700';
  if (rate >= 0.6) return 'text-amber-700';
  return 'text-red-700';
}

function costBadgeClass(delta: number | null | undefined): string {
  if (delta === null || delta === undefined) return 'text-gray-500';
  if (delta > 0) return 'text-amber-700';
  return 'text-emerald-700';
}

export default function AdminShadowRunsPage() {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );

  const [searchParams, setSearchParams] = useSearchParams();
  const openDraftId = searchParams.get('draftId');

  const [rows, setRows] = useState<ShadowRunAggregation[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await listShadowRunAggregations();
      setRows(data);
    } catch (e) {
      setError((e as Error).message);
      setRows([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchData();
  }, [fetchData]);

  const openDraft = useCallback(
    (draftId: string) => {
      const next = new URLSearchParams(searchParams);
      next.set('draftId', draftId);
      setSearchParams(next);
    },
    [searchParams, setSearchParams],
  );
  const closeDraft = useCallback(() => {
    const next = new URLSearchParams(searchParams);
    next.delete('draftId');
    setSearchParams(next);
  }, [searchParams, setSearchParams]);

  const openSkillCode =
    rows?.find((r) => r.draftId === openDraftId)?.draftSkillCode ?? null;

  return (
    <div
      className="p-6 max-w-7xl mx-auto"
      data-testid="admin-shadow-runs-page"
    >
      <div className="mb-4 flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">
            {l('影子运行比对', 'Shadow Runs Comparison')}
          </h1>
          <div className="text-sm text-gray-500 mt-1">
            {l(
              'Learning Loop 录制的草稿 vs 生产指标聚合,每行对应一个 Skill 草稿',
              'Learning Loop draft-vs-production metrics aggregated per Skill Draft',
            )}
          </div>
        </div>
        <button
          type="button"
          onClick={fetchData}
          className="text-sm px-3 py-1 rounded border border-gray-300 hover:bg-gray-50"
          data-testid="refresh-button"
        >
          {l('刷新', 'Refresh')}
        </button>
      </div>

      {error && (
        <div
          className="mb-3 flex items-center justify-between gap-3 text-sm text-red-700 bg-red-50 border border-red-200 rounded px-3 py-2"
          data-testid="error-banner"
        >
          <span>{error}</span>
          <button
            type="button"
            onClick={fetchData}
            className="text-xs px-2 py-0.5 border border-red-300 rounded hover:bg-red-100"
            data-testid="error-retry"
          >
            {l('重试', 'Retry')}
          </button>
        </div>
      )}

      {loading && (
        <div data-testid="loading-state">
          {/* 5-row skeleton placeholder */}
          {[0, 1, 2, 3, 4].map((k) => (
            <div
              key={k}
              className="animate-pulse mb-2 h-10 bg-gray-100 rounded"
            />
          ))}
        </div>
      )}

      {!loading && !error && rows && rows.length === 0 && (
        <div
          className="text-sm text-gray-500 border border-dashed border-gray-300 rounded p-8 text-center"
          data-testid="empty-state"
        >
          <div className="font-medium text-gray-700 mb-1">
            {l(
              '暂无影子运行记录',
              'No shadow runs recorded yet',
            )}
          </div>
          <div>
            {l(
              'Learning Loop 尚未为任何草稿生成影子运行;待自动技能挖掘并触发 ShadowRunScheduler 后,聚合指标会出现在此处。',
              'Learning Loop has not produced any shadow runs yet. Once auto-discovered skill drafts trigger the ShadowRunScheduler, aggregated metrics will surface here.',
            )}
          </div>
        </div>
      )}

      {!loading && !error && rows && rows.length > 0 && (
        <div className="overflow-x-auto">
          <table
            className="w-full text-sm border-collapse"
            data-testid="aggregations-table"
          >
            <thead>
              <tr className="border-b border-gray-200 text-left text-gray-600">
                <th className="py-2 pr-3">{l('草稿', 'Draft')}</th>
                <th className="py-2 pr-3">{l('状态', 'Status')}</th>
                <th className="py-2 pr-3 text-right">
                  {l('运行数', 'Runs')}
                </th>
                <th className="py-2 pr-3 text-right">
                  {l('Fidelity 匹配率', 'Fidelity match rate')}
                </th>
                <th className="py-2 pr-3 text-right">
                  {l('输出匹配率', 'Output match rate')}
                </th>
                <th className="py-2 pr-3 text-right">
                  {l('成本差', 'Cost Δ')}
                </th>
                <th className="py-2 pr-3">{l('最近', 'Latest')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr
                  key={r.draftId}
                  className="border-b border-gray-100 hover:bg-gray-50 cursor-pointer"
                  data-testid={`draft-row-${r.draftId}`}
                  onClick={() => openDraft(r.draftId)}
                >
                  <td className="py-2 pr-3 font-mono text-xs">
                    {r.draftSkillCode ?? r.draftId}
                  </td>
                  <td className="py-2 pr-3 text-xs">
                    <span className="inline-block px-1.5 py-0.5 bg-gray-100 text-gray-700 rounded">
                      {r.draftStatus ?? '-'}
                    </span>
                  </td>
                  <td className="py-2 pr-3 text-xs tabular-nums text-right">
                    {r.runCount}
                  </td>
                  <td
                    className={`py-2 pr-3 text-xs tabular-nums text-right ${rateBadgeClass(r.fidelityMatchRate)}`}
                    data-testid={`fidelity-rate-${r.draftId}`}
                  >
                    {fmtPct(r.fidelityMatchRate)}
                  </td>
                  <td
                    className={`py-2 pr-3 text-xs tabular-nums text-right ${rateBadgeClass(r.outputMatchRate)}`}
                    data-testid={`output-rate-${r.draftId}`}
                  >
                    {fmtPct(r.outputMatchRate)}
                  </td>
                  <td
                    className={`py-2 pr-3 text-xs tabular-nums text-right ${costBadgeClass(r.costDelta)}`}
                    data-testid={`cost-delta-${r.draftId}`}
                  >
                    {fmtCostDelta(r.costDelta)}
                  </td>
                  <td className="py-2 pr-3 text-xs text-gray-500">
                    {relativeTime(r.latestAt)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <ShadowRunDetailDrawer
        draftId={openDraftId}
        draftSkillCode={openSkillCode}
        onClose={closeDraft}
      />
    </div>
  );
}
