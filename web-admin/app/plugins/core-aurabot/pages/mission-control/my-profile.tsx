/**
 * Mission Control — My Soul Profile (PR-79 Phase 5)
 *
 * Two tabs:
 *   - My Profile: rendered profile card per field (persona, preferences.*,
 *     habits, expertise, boundaries, language) with confidence bars,
 *     source-memory counts, Pin/Hide/Edit/Reset buttons. Top banner when
 *     stale_flagged_at is set. Footer with re-derive + export + forget.
 *   - History: collapsible list of superseded/archived versions.
 *
 * Backend: /api/user/soul-profile/** (PR-78, Phase 4). All writes are POSTed
 * to the editor endpoints; rate-limit on /derive-now is enforced server-side.
 *
 * Design: docs/plans/2026-04/2026-04-19-user-soul-profile-design.md §6-§7.
 */
import { useState, useEffect, useCallback } from 'react';
import { get, post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';

// ---------------------------------------------------------------------------
// Types — mirror UserSoulProfileController response shape (§4 plan)
// ---------------------------------------------------------------------------

interface FieldBlock {
  text: string | string[];
  source_memory_pids?: string[];
  confidence?: number | null;
  last_derived_at?: string | null;
  user_pinned?: boolean;
  hidden?: boolean;
}

interface RecurringAction {
  pattern: string;
  frequency: string;
  source_action_count?: number;
  last_seen?: string | null;
}

interface ExpertiseDomain {
  name: string;
  confidence?: number | null;
  evidence_count?: number;
}

interface ProfilePayload {
  schema_version?: string;
  persona?: FieldBlock;
  preferences?: Record<string, FieldBlock>;
  habits?: { recurring_actions?: RecurringAction[] };
  expertise?: { domains?: ExpertiseDomain[] };
  boundaries?: FieldBlock;
  language?: string;
  meta?: Record<string, unknown>;
}

interface SourceMemoryRef {
  pid: string;
  memory_title?: string | null;
  created_at?: string | null;
}

interface ProfileRow {
  pid: string;
  tenant_id: number;
  user_id: number;
  version: number;
  status: string;
  profile: ProfilePayload;
  derivation_confidence: number | null;
  source_memory_pids?: string[];
  source_memory_refs?: SourceMemoryRef[];
  edited_fields?: Record<string, string> | null;
  hidden_at?: string | null;
  created_at: string;
  activated_at?: string | null;
  superseded_at?: string | null;
  stale_flagged_at?: string | null;
  next_derivation_at?: string | null;
  last_manual_derive_at?: string | null;
}

type Tab = 'profile' | 'history';

// Canonical field list the UI renders, in display order.
// `path` is the dot-path in the profile JSONB; `key` is the backend editor key.
interface FieldDef {
  key: string;
  zh: string;
  en: string;
  path: string[];
}

const FIELD_DEFS: FieldDef[] = [
  { key: 'persona', zh: '人设', en: 'Persona', path: ['persona'] },
  {
    key: 'preferences.communication_style',
    zh: '沟通风格',
    en: 'Communication Style',
    path: ['preferences', 'communication_style'],
  },
  {
    key: 'preferences.domain_vocabulary',
    zh: '领域词汇',
    en: 'Domain Vocabulary',
    path: ['preferences', 'domain_vocabulary'],
  },
  {
    key: 'preferences.working_hours',
    zh: '工作时段',
    en: 'Working Hours',
    path: ['preferences', 'working_hours'],
  },
  { key: 'boundaries', zh: '边界', en: 'Boundaries', path: ['boundaries'] },
];

// Rate-limit hint: disable client button if last_manual_derive_at < 24h ago
const DERIVE_COOLDOWN_MS = 24 * 3_600_000;

// ---------------------------------------------------------------------------
// Utility components
// ---------------------------------------------------------------------------

function ConfidenceBar({ value }: { value: number | null | undefined }) {
  const pct = value == null ? 0 : Math.round(value * 100);
  const color =
    pct >= 80 ? 'bg-green-500' : pct >= 60 ? 'bg-amber-500' : 'bg-gray-400';
  return (
    <div className="flex items-center gap-2" data-testid="confidence-bar">
      <div className="h-2 w-24 overflow-hidden rounded bg-gray-100 dark:bg-gray-700">
        <div className={`h-full ${color}`} style={{ width: `${Math.max(2, pct)}%` }} />
      </div>
      <span
        className="text-xs tabular-nums text-gray-700 dark:text-gray-300"
        data-testid="confidence-value"
      >
        {value == null ? '—' : `${pct}%`}
      </span>
    </div>
  );
}

function renderFieldText(block: FieldBlock | undefined): string {
  if (!block) return '';
  const t = block.text;
  if (Array.isArray(t)) return t.join(', ');
  return t || '';
}

function getFieldBlock(profile: ProfilePayload, path: string[]): FieldBlock | undefined {
  let cur: unknown = profile;
  for (const p of path) {
    if (!cur || typeof cur !== 'object') return undefined;
    cur = (cur as Record<string, unknown>)[p];
  }
  return (cur as FieldBlock | undefined) ?? undefined;
}

function fmtTimestamp(ts: string | null | undefined): string {
  if (!ts) return '—';
  return ts.slice(0, 16).replace('T', ' ');
}

// ---------------------------------------------------------------------------
// Main page
// ---------------------------------------------------------------------------

export default function MyProfilePage() {
  const { locale } = useI18n();
  const l = useCallback(
    (zh: string, en: string) => (locale === 'zh-CN' ? zh : en),
    [locale],
  );

  const [activeTab, setActiveTab] = useState<Tab>('profile');
  const [toast, setToast] = useState<{ kind: 'ok' | 'err'; msg: string } | null>(null);

  return (
    <div className="p-6 max-w-5xl mx-auto" data-testid="my-profile-page">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-2xl font-semibold">
          {l('我的个人画像', 'My Profile')}
        </h1>
      </div>

      <div className="mb-4 flex border-b border-gray-200" data-testid="tabs">
        {(
          [
            ['profile', l('我的画像', 'My Profile')],
            ['history', l('历史版本', 'History')],
          ] as const
        ).map(([key, label]) => (
          <button
            key={key}
            onClick={() => setActiveTab(key as Tab)}
            className={`border-b-2 px-4 py-2 text-sm font-medium ${
              activeTab === key
                ? 'border-blue-600 text-blue-600'
                : 'border-transparent text-gray-500 hover:text-gray-700'
            }`}
            data-testid={`tab-${key}`}
          >
            {label}
          </button>
        ))}
      </div>

      {toast && (
        <div
          className={`mb-3 px-3 py-2 rounded text-sm ${
            toast.kind === 'ok'
              ? 'bg-green-50 text-green-800'
              : 'bg-red-50 text-red-800'
          }`}
          data-testid="toast"
          onClick={() => setToast(null)}
        >
          {toast.msg}
        </div>
      )}

      {activeTab === 'profile' && <ProfileTab l={l} setToast={setToast} />}
      {activeTab === 'history' && <HistoryTab l={l} setToast={setToast} />}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Profile tab
// ---------------------------------------------------------------------------

function ProfileTab({
  l,
  setToast,
}: {
  l: (zh: string, en: string) => string;
  setToast: (t: { kind: 'ok' | 'err'; msg: string } | null) => void;
}) {
  const [row, setRow] = useState<ProfileRow | null>(null);
  const [loading, setLoading] = useState(true);
  const [notFound, setNotFound] = useState(false);
  const [inFlight, setInFlight] = useState<string | null>(null);
  const [editTarget, setEditTarget] = useState<FieldDef | null>(null);
  const [editText, setEditText] = useState('');
  const [forgetOpen, setForgetOpen] = useState(false);
  const [expandedSources, setExpandedSources] = useState<Set<string>>(new Set());

  const fetchProfile = useCallback(async () => {
    setLoading(true);
    try {
      const r = await get<ProfileRow>('/api/user/soul-profile');
      if (ResultHelper.isSuccess(r) && r.data) {
        setRow(r.data);
        setNotFound(false);
      } else {
        // 404 or empty → treat as not-found (empty state)
        setRow(null);
        setNotFound(true);
      }
    } catch {
      setRow(null);
      setNotFound(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchProfile();
  }, [fetchProfile]);

  const callEditor = useCallback(
    async (
      action: 'pin' | 'hide' | 'edit' | 'reset' | 'derive-now',
      body: Record<string, unknown> = {},
      okMsg?: string,
    ) => {
      setInFlight(action + ':' + (body.field ?? ''));
      try {
        const url = `/api/user/soul-profile/${action}`;
        const r = await post(url, body);
        if (ResultHelper.isSuccess(r)) {
          setToast({ kind: 'ok', msg: okMsg ?? l('操作成功', 'Operation succeeded') });
          fetchProfile();
        } else {
          const code = (r as any)?.code;
          const msg = (r as any)?.message ?? l('操作失败', 'Operation failed');
          if (code === '429' || /rate.?limit|too many/i.test(String(msg))) {
            setToast({
              kind: 'err',
              msg: l('操作过于频繁，请稍后再试', 'Too many requests — try again later'),
            });
          } else {
            setToast({ kind: 'err', msg });
          }
        }
      } finally {
        setInFlight(null);
      }
    },
    [fetchProfile, l, setToast],
  );

  const onForget = useCallback(async () => {
    setInFlight('forget');
    try {
      const r = await post('/api/user/soul-profile/forget', {});
      if (ResultHelper.isSuccess(r)) {
        setToast({
          kind: 'ok',
          msg: l('画像已遗忘（GDPR）', 'Profile forgotten (GDPR)'),
        });
        setForgetOpen(false);
        fetchProfile();
      } else {
        setToast({
          kind: 'err',
          msg: (r as any)?.message ?? l('操作失败', 'Operation failed'),
        });
      }
    } finally {
      setInFlight(null);
    }
  }, [fetchProfile, l, setToast]);

  const exportJson = useCallback(async () => {
    // GDPR data portability: hit the server-side export endpoint which
    // returns Content-Disposition: attachment with the full JSON dump
    // (all versions, including archived tombstones). The browser handles
    // the download; we still surface a toast on completion/failure.
    setInFlight('export');
    try {
      const resp = await fetch('/api/user/soul-profile/export', {
        method: 'GET',
        credentials: 'include',
      });
      if (!resp.ok) {
        setToast({
          kind: 'err',
          msg: l('导出失败', 'Export failed') + ` (${resp.status})`,
        });
        return;
      }
      const blob = await resp.blob();
      const disposition = resp.headers.get('content-disposition') ?? '';
      const m = disposition.match(/filename="?([^";]+)"?/i);
      const filename = m?.[1] ?? `user-soul-profile-${Date.now()}.json`;
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
      setToast({ kind: 'ok', msg: l('导出成功', 'Export succeeded') });
    } finally {
      setInFlight(null);
    }
  }, [l, setToast]);

  if (loading) {
    return (
      <div className="text-sm text-gray-500" data-testid="profile-loading">
        {l('加载中…', 'Loading…')}
      </div>
    );
  }

  if (notFound || !row) {
    return (
      <div
        className="rounded border border-dashed border-gray-300 bg-gray-50 p-8 text-center"
        data-testid="profile-empty"
      >
        <div className="mb-2 text-3xl">🪞</div>
        <div className="text-sm text-gray-700">
          {l(
            '尚未生成画像 — 继续使用 AuraBot，当积累足够高重要性记忆后，画像将自动生成。',
            'No profile yet — keep using AuraBot; once enough high-importance memories accumulate, your profile will be derived automatically.',
          )}
        </div>
      </div>
    );
  }

  const editedFields = row.edited_fields ?? {};
  const sourceRefs = row.source_memory_refs ?? [];
  const refByPid: Record<string, SourceMemoryRef> = Object.fromEntries(
    sourceRefs.map((m) => [m.pid, m]),
  );

  const stale = !!row.stale_flagged_at;
  const lastManual = row.last_manual_derive_at
    ? new Date(row.last_manual_derive_at).getTime()
    : 0;
  const deriveDisabled = lastManual > 0 && Date.now() - lastManual < DERIVE_COOLDOWN_MS;

  return (
    <div data-testid="profile-tab">
      {/* Stale banner */}
      {stale && (
        <div
          className="mb-4 flex items-start gap-2 rounded border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900"
          data-testid="stale-banner"
          role="alert"
        >
          <span>⚠️</span>
          <div className="flex-1">
            <div>
              {l(
                '你的画像可能已过时 — 最近的记忆与画像相矛盾。',
                'Your profile may be outdated — recent memories conflict with it.',
              )}
            </div>
            <div className="mt-0.5 text-xs text-amber-700">
              {l('标记于: ', 'Flagged at: ')}
              {fmtTimestamp(row.stale_flagged_at)}
            </div>
          </div>
          <button
            onClick={() => callEditor('derive-now', {}, l('已触发重新派生', 'Re-derive triggered'))}
            disabled={deriveDisabled || inFlight === 'derive-now:'}
            className="rounded bg-amber-600 px-2 py-1 text-xs text-white disabled:opacity-50"
            data-testid="stale-rederive-btn"
          >
            {l('立即重新派生', 'Re-derive now')}
          </button>
        </div>
      )}

      {/* Field cards */}
      <ul className="space-y-3" data-testid="profile-field-list">
        {FIELD_DEFS.map((def) => {
          const block = getFieldBlock(row.profile, def.path);
          const override = editedFields[def.key];
          const isHidden = override === 'hidden';
          if (isHidden) return null; // hidden fields disappear from the card list
          if (!block) return null;

          const text = renderFieldText(block);
          const srcPids = block.source_memory_pids ?? [];
          const expanded = expandedSources.has(def.key);
          const pinned = block.user_pinned || override === 'locked';
          const isEdit = override && override !== 'hidden' && override !== 'locked';

          return (
            <li
              key={def.key}
              className="rounded border border-gray-200 bg-white p-4"
              data-testid={`field-${def.key}`}
              data-pinned={pinned ? 'true' : 'false'}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-3">
                    <h3
                      className="text-sm font-semibold text-gray-900"
                      data-testid="field-title"
                    >
                      {l(def.zh, def.en)}
                    </h3>
                    {pinned && (
                      <span
                        className="rounded bg-blue-100 px-1.5 py-0.5 text-xs text-blue-700"
                        data-testid="pinned-badge"
                      >
                        📌 {l('已固定', 'Pinned')}
                      </span>
                    )}
                    {isEdit && (
                      <span
                        className="rounded bg-purple-100 px-1.5 py-0.5 text-xs text-purple-700"
                        data-testid="edited-badge"
                      >
                        ✏️ {l('已编辑', 'Edited')}
                      </span>
                    )}
                    <div className="ml-auto">
                      <ConfidenceBar value={block.confidence} />
                    </div>
                  </div>

                  <div
                    className="mt-2 text-sm text-gray-800 whitespace-pre-wrap"
                    data-testid="field-text"
                  >
                    {text || l('（暂无）', '(empty)')}
                  </div>

                  <div className="mt-2 flex items-center gap-3 text-xs text-gray-500">
                    <button
                      onClick={() => {
                        setExpandedSources((prev) => {
                          const next = new Set(prev);
                          if (next.has(def.key)) next.delete(def.key);
                          else next.add(def.key);
                          return next;
                        });
                      }}
                      className="text-blue-600 hover:underline"
                      data-testid="source-toggle"
                    >
                      {l('来源: ', 'Source: ')}
                      {srcPids.length}
                      {l(' 条记忆', ' memories')} {expanded ? '▲' : '▼'}
                    </button>
                  </div>

                  {expanded && (
                    <ul
                      className="mt-2 space-y-1 rounded border border-gray-100 bg-gray-50 p-2 text-xs text-gray-700"
                      data-testid="source-list"
                    >
                      {srcPids.length === 0 && (
                        <li className="text-gray-400">
                          {l('暂无来源记忆', 'No source memories')}
                        </li>
                      )}
                      {srcPids.map((pid) => {
                        const ref = refByPid[pid];
                        return (
                          <li key={pid} data-testid="source-item">
                            <span className="font-mono text-gray-500">{pid}</span>
                            {ref?.memory_title && (
                              <span className="ml-2 text-gray-700">{ref.memory_title}</span>
                            )}
                            {ref?.created_at && (
                              <span className="ml-2 text-gray-400">
                                {fmtTimestamp(ref.created_at)}
                              </span>
                            )}
                          </li>
                        );
                      })}
                    </ul>
                  )}
                </div>
              </div>

              {/* Action buttons */}
              <div className="mt-3 flex flex-wrap items-center gap-2">
                <button
                  onClick={() =>
                    callEditor('pin', { field: def.key }, l('已固定', 'Pinned'))
                  }
                  disabled={inFlight === `pin:${def.key}`}
                  className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-50 disabled:opacity-50"
                  data-testid="pin-btn"
                  aria-label={`pin ${def.key}`}
                >
                  📌 {l('固定', 'Pin')}
                </button>
                <button
                  onClick={() =>
                    callEditor('hide', { field: def.key }, l('已隐藏', 'Hidden'))
                  }
                  disabled={inFlight === `hide:${def.key}`}
                  className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-50 disabled:opacity-50"
                  data-testid="hide-btn"
                  aria-label={`hide ${def.key}`}
                >
                  👁 {l('隐藏', 'Hide')}
                </button>
                <button
                  onClick={() => {
                    setEditTarget(def);
                    setEditText(text);
                  }}
                  className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-50"
                  data-testid="edit-btn"
                  aria-label={`edit ${def.key}`}
                >
                  ✏️ {l('编辑', 'Edit')}
                </button>
                <button
                  onClick={() =>
                    callEditor('reset', { field: def.key }, l('已重置', 'Reset'))
                  }
                  disabled={inFlight === `reset:${def.key}`}
                  className="rounded border border-gray-300 px-2 py-1 text-xs hover:bg-gray-50 disabled:opacity-50"
                  data-testid="reset-btn"
                  aria-label={`reset ${def.key}`}
                >
                  ↩ {l('重置', 'Reset')}
                </button>
              </div>
            </li>
          );
        })}
      </ul>

      {/* Habits / Expertise / Language — read-only metadata */}
      <ExtraSections l={l} row={row} />

      {/* Footer */}
      <div
        className="mt-6 rounded border border-gray-200 bg-gray-50 p-4"
        data-testid="profile-footer"
      >
        <div className="flex flex-wrap items-center gap-3 text-xs text-gray-600">
          <span data-testid="footer-version">
            v{row.version}
          </span>
          <span>·</span>
          <span data-testid="footer-last-derived">
            {l('最后派生: ', 'Last derived: ')}
            {fmtTimestamp(row.activated_at ?? row.created_at)}
          </span>
          {row.next_derivation_at && (
            <>
              <span>·</span>
              <span data-testid="footer-next-derived">
                {l('下一次派生: ', 'Next derivation: ')}
                {fmtTimestamp(row.next_derivation_at)}
              </span>
            </>
          )}
          <span>·</span>
          <span data-testid="footer-overall-confidence">
            {l('整体置信度: ', 'Overall confidence: ')}
            {row.derivation_confidence == null
              ? '—'
              : `${Math.round(row.derivation_confidence * 100)}%`}
          </span>
        </div>

        <div className="mt-3 flex flex-wrap gap-2">
          <button
            onClick={() =>
              callEditor(
                'derive-now',
                {},
                l('已触发重新派生', 'Re-derive triggered'),
              )
            }
            disabled={deriveDisabled || inFlight === 'derive-now:'}
            className="rounded bg-blue-600 px-3 py-1 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
            data-testid="derive-now-btn"
            title={
              deriveDisabled
                ? l('每 24 小时仅可手动派生一次', 'Manual re-derive limited to once per 24h')
                : ''
            }
          >
            {l('立即重新派生', 'Re-derive now')}
          </button>
          {deriveDisabled && (
            <span className="text-xs text-gray-500" data-testid="derive-cooldown-hint">
              {l(
                '每 24 小时仅可手动派生一次',
                'Manual re-derive limited to once per 24h',
              )}
            </span>
          )}
          <button
            onClick={exportJson}
            className="rounded border border-gray-300 px-3 py-1 text-sm hover:bg-gray-100"
            data-testid="export-btn"
          >
            {l('导出 JSON', 'Export JSON')}
          </button>
          <div className="ml-auto">
            <button
              onClick={() => setForgetOpen(true)}
              className="rounded bg-red-600 px-3 py-1 text-sm text-white hover:bg-red-700"
              data-testid="forget-btn"
            >
              {l('遗忘画像 (GDPR)', 'Forget profile (GDPR)')}
            </button>
          </div>
        </div>
      </div>

      {/* Edit modal */}
      {editTarget && (
        <EditModal
          l={l}
          def={editTarget}
          text={editText}
          onTextChange={setEditText}
          onCancel={() => {
            setEditTarget(null);
            setEditText('');
          }}
          onSubmit={async () => {
            await callEditor(
              'edit',
              { field: editTarget.key, text: editText },
              l('已保存', 'Saved'),
            );
            setEditTarget(null);
            setEditText('');
          }}
          inFlight={inFlight === `edit:${editTarget.key}`}
        />
      )}

      {/* Forget modal */}
      {forgetOpen && (
        <ForgetModal
          l={l}
          onCancel={() => setForgetOpen(false)}
          onConfirm={onForget}
          inFlight={inFlight === 'forget'}
        />
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Extra sections — read-only habits / expertise / language
// ---------------------------------------------------------------------------

function ExtraSections({
  l,
  row,
}: {
  l: (zh: string, en: string) => string;
  row: ProfileRow;
}) {
  const habits = row.profile.habits?.recurring_actions ?? [];
  const expertise = row.profile.expertise?.domains ?? [];
  const language = row.profile.language;

  if (habits.length === 0 && expertise.length === 0 && !language) return null;

  return (
    <div className="mt-4 grid gap-3 md:grid-cols-2" data-testid="extra-sections">
      {habits.length > 0 && (
        <section
          className="rounded border border-gray-200 bg-white p-4"
          data-testid="habits-section"
        >
          <h3 className="text-sm font-semibold text-gray-900">
            {l('习惯', 'Habits')}
          </h3>
          <ul className="mt-2 space-y-1 text-sm text-gray-700">
            {habits.map((h, idx) => (
              <li key={idx} data-testid="habit-item">
                <span className="font-medium">{h.pattern}</span>
                <span className="ml-2 text-xs text-gray-500">
                  {h.frequency}
                  {h.source_action_count != null && ` · ${h.source_action_count}x`}
                  {h.last_seen && ` · ${h.last_seen}`}
                </span>
              </li>
            ))}
          </ul>
        </section>
      )}
      {expertise.length > 0 && (
        <section
          className="rounded border border-gray-200 bg-white p-4"
          data-testid="expertise-section"
        >
          <h3 className="text-sm font-semibold text-gray-900">
            {l('专长', 'Expertise')}
          </h3>
          <ul className="mt-2 space-y-1 text-sm text-gray-700">
            {expertise.map((d, idx) => (
              <li
                key={idx}
                className="flex items-center gap-2"
                data-testid="expertise-item"
              >
                <span className="font-medium">{d.name}</span>
                <ConfidenceBar value={d.confidence} />
                {d.evidence_count != null && (
                  <span className="ml-1 text-xs text-gray-500">
                    {d.evidence_count} {l('条证据', 'evidence')}
                  </span>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}
      {language && (
        <section
          className="rounded border border-gray-200 bg-white p-4 md:col-span-2"
          data-testid="language-section"
        >
          <h3 className="text-sm font-semibold text-gray-900">
            {l('语言偏好', 'Language Preference')}
          </h3>
          <div className="mt-1 text-sm text-gray-700" data-testid="language-value">
            {language}
          </div>
        </section>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// History tab
// ---------------------------------------------------------------------------

interface HistoryEntry {
  pid: string;
  version: number;
  status: string;
  derivation_confidence: number | null;
  activated_at?: string | null;
  superseded_at?: string | null;
  created_at: string;
  persona_text?: string | null; // only present for SUPERSEDED; null for ARCHIVED
  archived_at?: string | null;
}

function HistoryTab({
  l,
  setToast,
}: {
  l: (zh: string, en: string) => string;
  setToast: (t: { kind: 'ok' | 'err'; msg: string } | null) => void;
}) {
  const [entries, setEntries] = useState<HistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    get<HistoryEntry[]>('/api/user/soul-profile/history', { limit: '20' })
      .then((r) => {
        if (cancelled) return;
        if (ResultHelper.isSuccess(r)) {
          setEntries((r.data as HistoryEntry[]) ?? []);
        } else {
          setToast({
            kind: 'err',
            msg: (r as any)?.message ?? l('加载失败', 'Failed to load'),
          });
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [l, setToast]);

  if (loading) {
    return (
      <div className="text-sm text-gray-500" data-testid="history-loading">
        {l('加载中…', 'Loading…')}
      </div>
    );
  }

  if (entries.length === 0) {
    return (
      <div
        className="rounded border border-dashed border-gray-300 bg-gray-50 p-6 text-center text-sm text-gray-600"
        data-testid="history-empty"
      >
        {l('暂无历史版本。', 'No superseded versions yet.')}
      </div>
    );
  }

  return (
    <ul className="space-y-3" data-testid="history-list">
      {entries.map((e, idx) => {
        const next = entries[idx - 1];
        const isArchived = e.status?.toLowerCase() === 'archived';
        const isExpanded = expanded.has(e.pid);
        return (
          <li
            key={e.pid}
            className="rounded border border-gray-200 bg-white p-4"
            data-testid={`history-${e.pid}`}
            data-status={e.status}
          >
            <div className="flex items-center gap-3">
              <span
                className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-700"
                data-testid="version-badge"
              >
                v{e.version}
              </span>
              <StatusPill status={e.status} l={l} />
              <span className="text-xs text-gray-500">
                {fmtTimestamp(e.activated_at ?? e.created_at)}
                {e.superseded_at && ' → ' + fmtTimestamp(e.superseded_at)}
              </span>
              <span className="ml-auto text-xs text-gray-500">
                {l('置信度: ', 'Confidence: ')}
                {e.derivation_confidence == null
                  ? '—'
                  : `${Math.round(e.derivation_confidence * 100)}%`}
              </span>
            </div>

            {isArchived ? (
              <div
                className="mt-2 text-xs italic text-gray-500"
                data-testid="archived-placeholder"
              >
                {l('已归档于 ', 'Archived at ')}
                {fmtTimestamp(e.archived_at ?? e.superseded_at)}
                {l(' — 内容不再保留', ' — content no longer retained')}
              </div>
            ) : (
              <div className="mt-2">
                <button
                  onClick={() => {
                    setExpanded((prev) => {
                      const n = new Set(prev);
                      if (n.has(e.pid)) n.delete(e.pid);
                      else n.add(e.pid);
                      return n;
                    });
                  }}
                  className="text-xs text-blue-600 hover:underline"
                  data-testid="history-expand"
                >
                  {isExpanded ? l('收起', 'Collapse') : l('展开差异', 'Expand diff')}
                </button>
                {isExpanded && (
                  <div
                    className="mt-2 rounded bg-gray-50 p-2 text-xs text-gray-700"
                    data-testid="history-diff"
                  >
                    <div className="font-medium">
                      {l('此版本 Persona: ', 'This version Persona: ')}
                    </div>
                    <div className="mt-1 whitespace-pre-wrap">
                      {e.persona_text ?? l('（无）', '(none)')}
                    </div>
                    {next && next.persona_text != null && (
                      <>
                        <div className="mt-2 font-medium">
                          {l('后续版本 Persona: ', 'Next version Persona: ')}
                        </div>
                        <div className="mt-1 whitespace-pre-wrap">
                          {next.persona_text}
                        </div>
                      </>
                    )}
                  </div>
                )}
              </div>
            )}
          </li>
        );
      })}
    </ul>
  );
}

function StatusPill({
  status,
  l,
}: {
  status: string;
  l: (zh: string, en: string) => string;
}) {
  const s = status?.toLowerCase();
  const label =
    s === 'active'
      ? l('已启用', 'ACTIVE')
      : s === 'superseded'
        ? l('已被取代', 'SUPERSEDED')
        : s === 'archived'
          ? l('已归档', 'ARCHIVED')
          : status;
  const color =
    s === 'active'
      ? 'bg-green-100 text-green-800'
      : s === 'superseded'
        ? 'bg-gray-100 text-gray-700'
        : s === 'archived'
          ? 'bg-gray-200 text-gray-500'
          : 'bg-gray-100 text-gray-700';
  return (
    <span className={`inline-block rounded px-2 py-0.5 text-xs ${color}`} data-testid="status-pill">
      {label}
    </span>
  );
}

// ---------------------------------------------------------------------------
// Modals
// ---------------------------------------------------------------------------

function EditModal({
  l,
  def,
  text,
  onTextChange,
  onCancel,
  onSubmit,
  inFlight,
}: {
  l: (zh: string, en: string) => string;
  def: FieldDef;
  text: string;
  onTextChange: (v: string) => void;
  onCancel: () => void;
  onSubmit: () => void;
  inFlight: boolean;
}) {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/30"
      data-testid="edit-modal"
    >
      <div className="w-[520px] rounded bg-white p-5">
        <h3 className="text-lg font-semibold">
          {l('编辑字段', 'Edit field')}: {l(def.zh, def.en)}
        </h3>
        <p className="mt-1 text-xs text-gray-600">
          {l(
            '你的编辑将在后续派生中保留，除非点击"重置"。',
            'Your edit will persist across re-derivations until you click Reset.',
          )}
        </p>
        <textarea
          value={text}
          onChange={(e) => onTextChange(e.target.value)}
          rows={5}
          className="mt-3 w-full rounded border border-gray-300 p-2 text-sm"
          data-testid="edit-textarea"
        />
        <div className="mt-3 flex justify-end gap-2">
          <button
            onClick={onCancel}
            className="rounded border border-gray-300 px-3 py-1 text-sm"
            data-testid="edit-cancel"
          >
            {l('取消', 'Cancel')}
          </button>
          <button
            onClick={onSubmit}
            disabled={inFlight}
            className="rounded bg-blue-600 px-3 py-1 text-sm text-white disabled:opacity-50"
            data-testid="edit-submit"
          >
            {l('保存', 'Save')}
          </button>
        </div>
      </div>
    </div>
  );
}

function ForgetModal({
  l,
  onCancel,
  onConfirm,
  inFlight,
}: {
  l: (zh: string, en: string) => string;
  onCancel: () => void;
  onConfirm: () => void;
  inFlight: boolean;
}) {
  const [typed, setTyped] = useState('');
  const canSubmit = typed.trim().toLowerCase() === 'forget';
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/30"
      data-testid="forget-modal"
    >
      <div className="w-[520px] rounded bg-white p-5">
        <h3 className="text-lg font-semibold text-red-700">
          {l('遗忘画像 (GDPR)', 'Forget profile (GDPR)')}
        </h3>
        <p className="mt-2 text-sm text-gray-700">
          {l(
            '此操作不可逆。所有版本将被软删除，后续派生也将停止。',
            'This action cannot be undone. All versions will be soft-deleted and future derivation will be disabled.',
          )}
        </p>
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
          data-testid="forget-input"
          placeholder="forget"
        />
        <div className="mt-3 flex justify-end gap-2">
          <button
            onClick={onCancel}
            className="rounded border border-gray-300 px-3 py-1 text-sm"
            data-testid="forget-cancel"
          >
            {l('取消', 'Cancel')}
          </button>
          <button
            onClick={onConfirm}
            disabled={!canSubmit || inFlight}
            className="rounded bg-red-600 px-3 py-1 text-sm text-white disabled:opacity-50"
            data-testid="forget-confirm"
          >
            {l('确认遗忘', 'Confirm forget')}
          </button>
        </div>
      </div>
    </div>
  );
}
