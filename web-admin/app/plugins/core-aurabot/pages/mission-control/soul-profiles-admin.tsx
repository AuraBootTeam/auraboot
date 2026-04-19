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
import { get, post } from '~/shared/services/http-client';
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
  const [toast, setToast] = useState<{ kind: 'ok' | 'err'; msg: string } | null>(
    null,
  );
  const [forgetTarget, setForgetTarget] = useState<AdminRow | null>(null);
  const [reloadTick, setReloadTick] = useState(0);

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
  }, [l, reloadTick]);

  const onAdminForget = useCallback(
    async (userId: number, reason: string) => {
      const r = await post('/api/admin/user-soul-profiles/forget', {
        userId: String(userId),
        reason,
      });
      if (ResultHelper.isSuccess(r)) {
        setToast({
          kind: 'ok',
          msg: l('已执行管理员遗忘', 'Admin forget executed'),
        });
        setForgetTarget(null);
        setReloadTick((t) => t + 1);
      } else {
        const code = (r as any)?.code;
        const msg = (r as any)?.message ?? l('操作失败', 'Operation failed');
        if (String(code) === '409') {
          setToast({
            kind: 'err',
            msg: l('权限不足：非管理员', 'Forbidden: not an admin'),
          });
        } else {
          setToast({ kind: 'err', msg });
        }
      }
    },
    [l],
  );

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

      {toast && (
        <div
          className={`mb-3 px-3 py-2 rounded text-sm ${
            toast.kind === 'ok'
              ? 'bg-green-50 text-green-800'
              : 'bg-red-50 text-red-800'
          }`}
          data-testid="admin-toast"
          onClick={() => setToast(null)}
        >
          {toast.msg}
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

      {forgetTarget && (
        <AdminForgetModal
          l={l}
          target={forgetTarget}
          onCancel={() => setForgetTarget(null)}
          onSubmit={onAdminForget}
        />
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
                <th className="px-3 py-2">{l('操作', 'Actions')}</th>
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
                  <td className="px-3 py-2 text-xs">
                    <button
                      onClick={() => setForgetTarget(r)}
                      className="rounded bg-red-600 px-2 py-1 text-xs text-white hover:bg-red-700"
                      data-testid={`admin-forget-btn-${r.user_id}`}
                      aria-label={`admin forget user ${r.user_id}`}
                    >
                      {l('遗忘', 'Forget')}
                    </button>
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

// ---------------------------------------------------------------------------
// Admin forget modal (Phase 10)
// ---------------------------------------------------------------------------

const ADMIN_FORGET_REASONS: Array<{ code: string; zh: string; en: string }> = [
  { code: 'gdpr_request', zh: 'GDPR 请求', en: 'GDPR request' },
  { code: 'account_closed', zh: '账户关闭', en: 'Account closed' },
  { code: 'policy_violation', zh: '政策违规', en: 'Policy violation' },
  { code: 'other', zh: '其他', en: 'Other' },
];

function AdminForgetModal({
  l,
  target,
  onCancel,
  onSubmit,
}: {
  l: (zh: string, en: string) => string;
  target: AdminRow;
  onCancel: () => void;
  onSubmit: (userId: number, reason: string) => Promise<void>;
}) {
  const [reason, setReason] = useState<string>(ADMIN_FORGET_REASONS[0].code);
  const [typed, setTyped] = useState('');
  const [inFlight, setInFlight] = useState(false);
  const canSubmit =
    typed.trim().toLowerCase() === 'forget' && !!reason && !inFlight;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/30"
      data-testid="admin-forget-modal"
    >
      <div className="w-[560px] rounded bg-white p-5">
        <h3 className="text-lg font-semibold text-red-700">
          {l('管理员遗忘用户画像 (GDPR)', 'Admin-forget user profile (GDPR)')}
        </h3>
        <p className="mt-2 text-sm text-gray-700">
          {l(
            '此操作不可逆。目标用户的全部画像版本将被软删除，派生停止。',
            'This is irreversible. All versions of the target user profile will be soft-deleted and derivation will stop.',
          )}
        </p>

        <label className="mt-3 block text-xs text-gray-600">
          {l('目标用户 ID', 'Target user id')}
        </label>
        <input
          type="text"
          readOnly
          value={String(target.user_id)}
          className="mt-1 w-full rounded border border-gray-300 bg-gray-50 p-2 text-sm font-mono"
          data-testid="admin-forget-user-id"
        />

        <label className="mt-3 block text-xs text-gray-600">
          {l('原因', 'Reason')}
        </label>
        <select
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          className="mt-1 w-full rounded border border-gray-300 p-2 text-sm"
          data-testid="admin-forget-reason"
        >
          {ADMIN_FORGET_REASONS.map((r) => (
            <option key={r.code} value={r.code}>
              {l(r.zh, r.en)}
            </option>
          ))}
        </select>

        <label className="mt-3 block text-xs text-gray-600">
          {l('输入 ', 'Type ')}
          <code className="rounded bg-gray-100 px-1 font-mono">forget</code>
          {l(' 以确认', ' to confirm')}
        </label>
        <input
          type="text"
          value={typed}
          onChange={(e) => setTyped(e.target.value)}
          className="mt-1 w-full rounded border border-gray-300 p-2 text-sm"
          data-testid="admin-forget-input"
          placeholder="forget"
        />

        <div className="mt-4 flex justify-end gap-2">
          <button
            onClick={onCancel}
            disabled={inFlight}
            className="rounded border border-gray-300 px-3 py-1 text-sm disabled:opacity-50"
            data-testid="admin-forget-cancel"
          >
            {l('取消', 'Cancel')}
          </button>
          <button
            onClick={async () => {
              setInFlight(true);
              try {
                await onSubmit(target.user_id, reason);
              } finally {
                setInFlight(false);
              }
            }}
            disabled={!canSubmit}
            className="rounded bg-red-600 px-3 py-1 text-sm text-white disabled:opacity-50"
            data-testid="admin-forget-submit"
          >
            {l('确认遗忘', 'Confirm forget')}
          </button>
        </div>
      </div>
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
