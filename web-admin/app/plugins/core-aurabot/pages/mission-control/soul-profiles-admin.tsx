/**
 * Mission Control — Soul Profiles Admin dashboard (PR-79 Phase 5)
 *
 * Tenant-scoped metadata-only view. Admin CANNOT read any user's profile
 * content — this page shows only per-user version / status / confidence /
 * staleness. Content field is never fetched or rendered here.
 *
 * Backend:
 *   GET /api/admin/user-soul-profiles         — list (metadata only)
 *   GET /api/admin/user-soul-profiles/stats   — tenant aggregate stats
 *
 * Design: docs/plans/2026-04/2026-04-19-user-soul-profile-design.md §6, §7.
 */
import { useEffect, useState, useCallback } from 'react';
import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface AdminRow {
  user_id: number;
  user_email?: string | null;
  user_name?: string | null;
  version: number;
  status: string;
  activated_at?: string | null;
  derivation_confidence: number | null;
  stale_flagged_at?: string | null;
  hidden_at?: string | null;
  // NOTE: no `profile` / `profile_content` ever — admin cannot read content
}

interface AdminStats {
  total_users_with_profile: number;
  total_active_users?: number;
  coverage_rate?: number; // 0..1
  stale_count: number;
  avg_confidence: number | null;
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function fmtTimestamp(ts: string | null | undefined): string {
  if (!ts) return '—';
  return ts.slice(0, 16).replace('T', ' ');
}

function fmtPct(v: number | null | undefined): string {
  if (v == null) return '—';
  return `${Math.round(v * 100)}%`;
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export default function SoulProfilesAdminPage() {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );

  const [rows, setRows] = useState<AdminRow[]>([]);
  const [stats, setStats] = useState<AdminStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([
      get<AdminRow[]>('/api/admin/user-soul-profiles'),
      get<AdminStats>('/api/admin/user-soul-profiles/stats'),
    ])
      .then(([listR, statsR]) => {
        if (cancelled) return;
        if (ResultHelper.isSuccess(listR)) {
          setRows((listR.data as AdminRow[]) ?? []);
        } else {
          setError((listR as any)?.message ?? l('加载失败', 'Failed to load'));
        }
        if (ResultHelper.isSuccess(statsR)) {
          setStats(statsR.data as AdminStats);
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [l]);

  return (
    <div className="p-6 max-w-6xl mx-auto" data-testid="soul-profiles-admin-page">
      <div className="mb-4">
        <h1 className="text-2xl font-semibold">
          {l('Soul Profiles 管理', 'Soul Profiles (Admin)')}
        </h1>
        <p className="mt-1 text-xs text-gray-500" data-testid="admin-metadata-notice">
          {l(
            '仅展示元数据。管理员无法查看任何用户画像的内容。',
            'Metadata only. Admins cannot view any user profile content.',
          )}
        </p>
      </div>

      {/* Stats */}
      {stats && (
        <div
          className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4"
          data-testid="admin-stats-cards"
        >
          <StatCard
            testId="stat-total"
            label={l('已有画像用户', 'Users with profile')}
            value={String(stats.total_users_with_profile)}
          />
          <StatCard
            testId="stat-coverage"
            label={l('覆盖率', 'Coverage rate')}
            value={fmtPct(stats.coverage_rate)}
          />
          <StatCard
            testId="stat-stale"
            label={l('过时画像数', 'Stale count')}
            value={String(stats.stale_count)}
            color={stats.stale_count > 0 ? 'amber' : 'gray'}
          />
          <StatCard
            testId="stat-avg-confidence"
            label={l('平均置信度', 'Avg confidence')}
            value={fmtPct(stats.avg_confidence)}
          />
        </div>
      )}

      {loading && (
        <div className="text-sm text-gray-500" data-testid="admin-loading">
          {l('加载中…', 'Loading…')}
        </div>
      )}

      {error && (
        <div
          className="rounded border border-red-200 bg-red-50 p-3 text-sm text-red-800"
          data-testid="admin-error"
        >
          {error}
        </div>
      )}

      {!loading && !error && rows.length === 0 && (
        <div
          className="rounded border border-dashed border-gray-300 bg-gray-50 p-6 text-center text-sm text-gray-600"
          data-testid="admin-empty"
        >
          {l('本租户暂无画像记录。', 'No profiles in this tenant yet.')}
        </div>
      )}

      {!loading && rows.length > 0 && (
        <div className="overflow-hidden rounded border border-gray-200 bg-white">
          <table className="w-full text-sm" data-testid="admin-table">
            <thead>
              <tr className="border-b border-gray-200 bg-gray-50 text-left text-gray-500">
                <th className="px-3 py-2">{l('用户 ID', 'User ID')}</th>
                <th className="px-3 py-2">{l('用户', 'User')}</th>
                <th className="px-3 py-2">{l('版本', 'Version')}</th>
                <th className="px-3 py-2">{l('状态', 'Status')}</th>
                <th className="px-3 py-2">{l('启用时间', 'Activated')}</th>
                <th className="px-3 py-2">{l('置信度', 'Confidence')}</th>
                <th className="px-3 py-2">{l('过时', 'Stale?')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr
                  key={r.user_id}
                  className="border-b border-gray-100"
                  data-testid={`admin-row-${r.user_id}`}
                >
                  <td className="px-3 py-2 font-mono text-xs text-gray-600">
                    {r.user_id}
                  </td>
                  <td className="px-3 py-2 text-xs text-gray-700">
                    {r.user_name || r.user_email || '—'}
                  </td>
                  <td className="px-3 py-2 text-xs" data-testid="cell-version">
                    v{r.version}
                  </td>
                  <td className="px-3 py-2 text-xs" data-testid="cell-status">
                    {r.status}
                  </td>
                  <td className="px-3 py-2 text-xs text-gray-600">
                    {fmtTimestamp(r.activated_at)}
                  </td>
                  <td className="px-3 py-2 text-xs" data-testid="cell-confidence">
                    {fmtPct(r.derivation_confidence)}
                  </td>
                  <td className="px-3 py-2 text-xs" data-testid="cell-stale">
                    {r.stale_flagged_at ? (
                      <span
                        className="rounded bg-amber-100 px-1.5 py-0.5 text-amber-800"
                        data-testid="stale-badge"
                      >
                        ⚠️ {l('过时', 'stale')}
                      </span>
                    ) : (
                      '—'
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function StatCard({
  testId,
  label,
  value,
  color = 'blue',
}: {
  testId: string;
  label: string;
  value: string;
  color?: 'blue' | 'amber' | 'gray';
}) {
  const colorCls =
    color === 'amber'
      ? 'text-amber-700'
      : color === 'gray'
        ? 'text-gray-700'
        : 'text-blue-700';
  return (
    <div className="rounded border border-gray-200 bg-white p-3" data-testid={testId}>
      <div className="text-xs text-gray-500">{label}</div>
      <div className={`mt-1 text-xl font-semibold ${colorCls}`}>{value}</div>
    </div>
  );
}
