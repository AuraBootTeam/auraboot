/**
 * Cross-Tenant Grants admin page (C.2).
 *
 * Operator surface for the {@code ab_cross_tenant_grant} table — list active
 * grants, issue new grants, revoke existing grants, and drill into the audit
 * trail per grant.
 *
 * <p>Permission: backend gates via AdminRoleInterceptor (TENANT_ADMIN) plus
 * the controller's per-handler {@code platform_admin} guard. The UI does
 * NOT pre-flight a permission check — the API simply returns a 403 envelope
 * which we surface as a banner. This avoids splitting a single source of
 * truth across the wire.
 *
 * <p>States covered:
 * <ul>
 *   <li>loading — skeleton table</li>
 *   <li>error   — banner with retry</li>
 *   <li>empty   — empty-state CTA "create your first grant"</li>
 *   <li>data    — rows + actions</li>
 * </ul>
 */

import { useCallback, useEffect, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import {
  createGrant,
  listAudit,
  listGrants,
  revokeGrant,
  type CrossTenantGrantRecord,
  type CrossTenantSpawnAuditRecord,
} from '../services/crossTenantGrantsApi';

const PAGE_SIZE = 20;

function fmtTime(iso: string | null): string {
  if (!iso) return '-';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

function StatusBadge({ row }: { row: CrossTenantGrantRecord }) {
  if (row.revoked_at) {
    return (
      <span className="inline-flex items-center px-2 py-0.5 text-xs font-medium bg-red-100 text-red-700 rounded">
        revoked
      </span>
    );
  }
  if (row.expires_at && new Date(row.expires_at).getTime() < Date.now()) {
    return (
      <span className="inline-flex items-center px-2 py-0.5 text-xs font-medium bg-amber-100 text-amber-700 rounded">
        expired
      </span>
    );
  }
  return (
    <span className="inline-flex items-center px-2 py-0.5 text-xs font-medium bg-green-100 text-green-700 rounded">
      active
    </span>
  );
}

export default function CrossTenantGrantsPage() {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );

  const [rows, setRows] = useState<CrossTenantGrantRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [revokeTarget, setRevokeTarget] = useState<CrossTenantGrantRecord | null>(null);
  const [auditTarget, setAuditTarget] = useState<CrossTenantGrantRecord | null>(null);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    const result = await listGrants(1, PAGE_SIZE, false);
    if (result.success && result.data) {
      setRows(result.data.records);
    } else {
      setError(result.message || l('加载失败', 'Failed to load grants'));
    }
    setLoading(false);
  }, [l]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return (
    <div className="p-6 space-y-4" data-testid="cross-tenant-grants-page">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-semibold">
            {l('跨租户子代理授权', 'Cross-Tenant Sub-Agent Grants')}
          </h1>
          <p className="text-sm text-gray-500 mt-1">
            {l(
              '允许平台管理员授权某个租户在另一租户中派生子代理任务。默认拒绝。',
              'Authorise a tenant to spawn sub-agent runs inside another tenant. Default-deny.',
            )}
          </p>
        </div>
        <button
          type="button"
          onClick={() => setCreateOpen(true)}
          className="px-3 py-1.5 text-sm font-medium bg-blue-600 text-white rounded hover:bg-blue-700"
          data-testid="grant-create-button"
        >
          {l('新建授权', 'Grant access')}
        </button>
      </div>

      {error && (
        <div
          className="p-3 bg-red-50 border border-red-200 rounded text-sm text-red-700"
          data-testid="grants-error-banner"
        >
          {error}
          <button
            type="button"
            onClick={refresh}
            className="ml-3 underline"
          >
            {l('重试', 'Retry')}
          </button>
        </div>
      )}

      {loading && (
        <div data-testid="grants-loading" className="py-8 text-center text-sm text-gray-400">
          {l('加载中…', 'Loading…')}
        </div>
      )}

      {!loading && !error && rows.length === 0 && (
        <div
          data-testid="grants-empty"
          className="py-12 text-center border border-dashed rounded"
        >
          <p className="text-sm text-gray-500">
            {l('尚未创建任何跨租户授权。', 'No cross-tenant grants yet.')}
          </p>
          <button
            type="button"
            onClick={() => setCreateOpen(true)}
            className="mt-3 px-3 py-1.5 text-sm bg-blue-600 text-white rounded"
          >
            {l('创建首个授权', 'Create the first grant')}
          </button>
        </div>
      )}

      {!loading && rows.length > 0 && (
        <table className="w-full text-sm border-collapse" data-testid="grants-table">
          <thead className="bg-gray-50 text-left">
            <tr>
              <th className="px-3 py-2 font-medium">{l('父租户', 'Parent tenant')}</th>
              <th className="px-3 py-2 font-medium">{l('子租户', 'Child tenant')}</th>
              <th className="px-3 py-2 font-medium">{l('类型', 'Type')}</th>
              <th className="px-3 py-2 font-medium">{l('状态', 'Status')}</th>
              <th className="px-3 py-2 font-medium">{l('授权人', 'Granted by')}</th>
              <th className="px-3 py-2 font-medium">{l('授权时间', 'Granted at')}</th>
              <th className="px-3 py-2 font-medium">{l('过期时间', 'Expires at')}</th>
              <th className="px-3 py-2 font-medium text-right">{l('操作', 'Actions')}</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.id} className="border-t" data-testid={`grant-row-${r.id}`}>
                <td className="px-3 py-2 font-mono">{r.parent_tenant_id}</td>
                <td className="px-3 py-2 font-mono">{r.child_tenant_id}</td>
                <td className="px-3 py-2">{r.grant_type}</td>
                <td className="px-3 py-2"><StatusBadge row={r} /></td>
                <td className="px-3 py-2 font-mono">{r.granted_by}</td>
                <td className="px-3 py-2">{fmtTime(r.granted_at)}</td>
                <td className="px-3 py-2">{fmtTime(r.expires_at)}</td>
                <td className="px-3 py-2 text-right space-x-2">
                  <button
                    type="button"
                    onClick={() => setAuditTarget(r)}
                    className="text-blue-600 hover:underline text-xs"
                    data-testid={`grant-audit-button-${r.id}`}
                  >
                    {l('审计', 'Audit')}
                  </button>
                  {!r.revoked_at && (
                    <button
                      type="button"
                      onClick={() => setRevokeTarget(r)}
                      className="text-red-600 hover:underline text-xs"
                      data-testid={`grant-revoke-button-${r.id}`}
                    >
                      {l('撤销', 'Revoke')}
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {createOpen && (
        <GrantFormModal
          onClose={() => setCreateOpen(false)}
          onCreated={async () => {
            setCreateOpen(false);
            await refresh();
          }}
        />
      )}
      {revokeTarget && (
        <RevokeConfirmModal
          target={revokeTarget}
          onClose={() => setRevokeTarget(null)}
          onRevoked={async () => {
            setRevokeTarget(null);
            await refresh();
          }}
        />
      )}
      {auditTarget && (
        <AuditPanel
          target={auditTarget}
          onClose={() => setAuditTarget(null)}
        />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------

export function GrantFormModal({
  onClose,
  onCreated,
}: {
  onClose: () => void;
  onCreated: () => void;
}) {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );
  const [parentTenantId, setParentTenantId] = useState('');
  const [childTenantId, setChildTenantId] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [note, setNote] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    const result = await createGrant({
      parentTenantId: Number(parentTenantId),
      childTenantId: Number(childTenantId),
      expiresAt: expiresAt ? new Date(expiresAt).toISOString() : undefined,
      note: note || undefined,
    });
    setSubmitting(false);
    if (result.success) {
      onCreated();
    } else {
      setError(result.message || l('创建失败', 'Create failed'));
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" data-testid="grant-form-modal">
      <form
        onSubmit={onSubmit}
        className="bg-white rounded-lg shadow-xl w-full max-w-md p-6 space-y-4"
      >
        <h2 className="text-lg font-semibold">
          {l('新建跨租户授权', 'New cross-tenant grant')}
        </h2>
        <label className="block text-sm">
          <span className="block mb-1 text-gray-700">{l('父租户 ID', 'Parent tenant ID')}</span>
          <input
            type="number"
            value={parentTenantId}
            onChange={(e) => setParentTenantId(e.target.value)}
            className="w-full px-2 py-1 border rounded"
            required
            data-testid="grant-form-parent-tenant"
          />
        </label>
        <label className="block text-sm">
          <span className="block mb-1 text-gray-700">{l('子租户 ID', 'Child tenant ID')}</span>
          <input
            type="number"
            value={childTenantId}
            onChange={(e) => setChildTenantId(e.target.value)}
            className="w-full px-2 py-1 border rounded"
            required
            data-testid="grant-form-child-tenant"
          />
        </label>
        <label className="block text-sm">
          <span className="block mb-1 text-gray-700">{l('过期时间（可选）', 'Expires at (optional)')}</span>
          <input
            type="datetime-local"
            value={expiresAt}
            onChange={(e) => setExpiresAt(e.target.value)}
            className="w-full px-2 py-1 border rounded"
            data-testid="grant-form-expires-at"
          />
        </label>
        <label className="block text-sm">
          <span className="block mb-1 text-gray-700">{l('备注', 'Note')}</span>
          <textarea
            value={note}
            onChange={(e) => setNote(e.target.value)}
            className="w-full px-2 py-1 border rounded"
            rows={2}
            data-testid="grant-form-note"
          />
        </label>
        {error && (
          <div className="text-sm text-red-600" data-testid="grant-form-error">
            {error}
          </div>
        )}
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className="px-3 py-1.5 text-sm border rounded">
            {l('取消', 'Cancel')}
          </button>
          <button
            type="submit"
            disabled={submitting}
            className="px-3 py-1.5 text-sm bg-blue-600 text-white rounded disabled:opacity-50"
            data-testid="grant-form-submit"
          >
            {submitting ? l('提交中…', 'Submitting…') : l('创建', 'Create')}
          </button>
        </div>
      </form>
    </div>
  );
}

export function RevokeConfirmModal({
  target,
  onClose,
  onRevoked,
}: {
  target: CrossTenantGrantRecord;
  onClose: () => void;
  onRevoked: () => void;
}) {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const onConfirm = async () => {
    setSubmitting(true);
    const result = await revokeGrant(target.id);
    setSubmitting(false);
    if (result.success) {
      onRevoked();
    } else {
      setError(result.message || l('撤销失败', 'Revoke failed'));
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" data-testid="grant-revoke-modal">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-md p-6 space-y-4">
        <h2 className="text-lg font-semibold text-red-700">
          {l('撤销授权', 'Revoke grant')}
        </h2>
        <p className="text-sm text-gray-600">
          {l(
            `确定要撤销 ${target.parent_tenant_id} → ${target.child_tenant_id} 的授权吗？已运行的子任务不受影响,新派生请求将被拒绝。`,
            `Revoke grant ${target.parent_tenant_id} → ${target.child_tenant_id}? Existing child runs continue; new spawn requests will be denied.`,
          )}
        </p>
        {error && (
          <div className="text-sm text-red-600" data-testid="grant-revoke-error">{error}</div>
        )}
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className="px-3 py-1.5 text-sm border rounded">
            {l('取消', 'Cancel')}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={submitting}
            className="px-3 py-1.5 text-sm bg-red-600 text-white rounded disabled:opacity-50"
            data-testid="grant-revoke-confirm"
          >
            {submitting ? l('撤销中…', 'Revoking…') : l('确认撤销', 'Confirm revoke')}
          </button>
        </div>
      </div>
    </div>
  );
}

function AuditPanel({
  target,
  onClose,
}: {
  target: CrossTenantGrantRecord;
  onClose: () => void;
}) {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );
  const [rows, setRows] = useState<CrossTenantSpawnAuditRecord[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    listAudit(target.id, 1, 50).then((result) => {
      if (cancelled) return;
      if (result.success && result.data) {
        setRows(result.data.records);
      }
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [target.id]);

  return (
    <div
      className="fixed inset-y-0 right-0 z-40 w-full max-w-2xl bg-white shadow-2xl border-l overflow-y-auto"
      data-testid="grant-audit-panel"
    >
      <div className="p-6 space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">
            {l('授权审计', 'Grant audit')} #{target.id}
          </h2>
          <button type="button" onClick={onClose} className="text-gray-400 hover:text-gray-600">×</button>
        </div>
        {loading ? (
          <div className="text-sm text-gray-400">{l('加载中…', 'Loading…')}</div>
        ) : rows.length === 0 ? (
          <div className="text-sm text-gray-500">{l('暂无审计记录', 'No audit rows yet')}</div>
        ) : (
          <table className="w-full text-xs">
            <thead className="bg-gray-50 text-left">
              <tr>
                <th className="px-2 py-1">{l('时间', 'Time')}</th>
                <th className="px-2 py-1">{l('决定', 'Decision')}</th>
                <th className="px-2 py-1">{l('父任务', 'Parent run')}</th>
                <th className="px-2 py-1">{l('子任务', 'Child run')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className="border-t">
                  <td className="px-2 py-1">{fmtTime(r.spawn_at)}</td>
                  <td className="px-2 py-1 font-mono">{r.decision}</td>
                  <td className="px-2 py-1 font-mono truncate max-w-xs">{r.parent_run_pid}</td>
                  <td className="px-2 py-1 font-mono truncate max-w-xs">{r.child_run_pid ?? '-'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
