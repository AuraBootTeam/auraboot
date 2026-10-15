import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { publicationRequest, PublicationApiError } from '~/services/open-api-publications';
import { PublicationField } from './PublicationControls';

export interface IntegrationTokenView {
  pid: string; name: string; status: 'active' | 'rotating' | 'replaced' | 'expired' | 'revoked';
  expirationMode: 'fixed' | 'never'; issuedAt: string; expiresAt: string | null;
  lastUsedAt: string | null; acceptUntil: string | null; rotatedAt: string | null; replacedByPid: string | null;
}
interface TokenSecret { appId: string; installationPid: string; token: string; metadata: IntegrationTokenView }
interface Rotation { credential: TokenSecret; replacedTokenAcceptUntil: string }
const BUTTON = 'rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text hover:bg-hover disabled:opacity-50';
const PRIMARY = BUTTON.replace('bg-panel', 'bg-accent').replace('hover:bg-hover', 'hover:bg-accent-hover').replace('text-text', 'text-white');
const DANGER = BUTTON.replace('text-text', 'text-status-red');
const CONTROL = 'w-full rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text disabled:opacity-50';

/** Human-only token operations. Secret material is held only in this mounted panel. */
export function IntegrationTokenPanel({ installationPid, appId, active, onClose }: {
  installationPid: string; appId: string; active: boolean; onClose: () => void;
}) {
  const { t, locale } = useI18n();
  const text = (key: string, zh: string, en: string) => t(`openPlatform.tokens.${key}`, undefined, locale.startsWith('zh') ? zh : en);
  const [rows, setRows] = useState<IntegrationTokenView[]>([]);
  const [loading, setLoading] = useState(true); const [busy, setBusy] = useState(false);
  const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [denied, setDenied] = useState(false);
  const [operation, setOperation] = useState<{ kind: 'create' | 'rotate' | 'revoke'; row?: IntegrationTokenView } | null>(null);
  const [name, setName] = useState(''); const [mode, setMode] = useState<'fixed' | 'never'>('fixed');
  const [days, setDays] = useState(30); const [grace, setGrace] = useState(1440);
  const [secret, setSecret] = useState<TokenSecret | null>(null); const [cutoff, setCutoff] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const key = useRef(''); const pending = useRef(false); const generation = useRef(0);
  const base = `/api/open-platform/installations/${encodeURIComponent(installationPid)}/integration-tokens`;
  const fail = (cause: unknown) => {
    if (cause instanceof PublicationApiError && [401, 403].includes(cause.status)) {
      setDenied(true); setSecret(null);
      setError(text('denied', '需要此应用的运行管理权限。', 'Application runtime-management permission is required.'));
    } else setError(cause instanceof Error ? cause.message : text('failed', '请求失败，请查看清单确认结果。', 'Request failed. Inspect the list to confirm the result.'));
  };
  const load = async () => {
    const id = ++generation.current; setLoading(true); setError('');
    try { const result = await publicationRequest<IntegrationTokenView[]>(base);
      if (generation.current === id) { setRows(result); setDenied(false); }
    } catch (cause) { if (generation.current === id) fail(cause); }
    finally { if (generation.current === id) setLoading(false); }
  };
  useEffect(() => { void load(); return () => { generation.current++; }; }, [installationPid]);
  const begin = (kind: 'create' | 'rotate' | 'revoke', row?: IntegrationTokenView) => {
    setError(''); setNotice(''); setName(''); setMode('fixed'); setDays(30); setGrace(1440);
    setSecret(null); setCutoff(null); setCopied(false); key.current = crypto.randomUUID(); setOperation({ kind, row });
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault(); if (!operation || pending.current || denied) return;
    if (operation.kind !== 'revoke' && (!active || (mode === 'fixed' && (!Number.isFinite(days) || days < 1 || days > 90)))) return;
    if (operation.kind === 'rotate' && (!Number.isInteger(grace) || grace < 0 || grace > 10080)) return;
    pending.current = true; setBusy(true); setError(''); setNotice('');
    try {
      if (operation.kind === 'revoke') {
        await publicationRequest(`${base}/${encodeURIComponent(operation.row!.pid)}`, { method: 'DELETE' });
        setNotice(text('revoked', 'Token 已撤销，后续请求将被拒绝。', 'Token revoked. Subsequent requests are rejected.'));
      } else {
        const expiry = { expirationMode: mode, ...(mode === 'fixed' ? { expiresAt: new Date(Date.now() + days * 86400000).toISOString() } : {}) };
        const path = operation.kind === 'rotate' ? `${base}/${encodeURIComponent(operation.row!.pid)}/rotate` : base;
        const result = await publicationRequest<TokenSecret | Rotation>(path, { method: 'POST', headers: { 'Idempotency-Key': key.current },
          body: JSON.stringify(operation.kind === 'rotate' ? { ...expiry, graceMinutes: grace } : { ...expiry, name: name.trim() }) });
        if ('credential' in result) { setSecret(result.credential); setCutoff(result.replacedTokenAcceptUntil); }
        else { setSecret(result); setCutoff(null); }
      }
      setOperation(null); await load();
    } catch (cause) { fail(cause); }
    finally { pending.current = false; setBusy(false); }
  };
  const date = (value: string | null) => value ? new Date(value).toLocaleString(locale) : text('unused', '尚未使用', 'Not used yet');
  const status = (value: IntegrationTokenView['status']) => ({
    active: text('active', '有效', 'Active'), rotating: text('rotating', '轮换宽限中', 'Rotation grace'),
    replaced: text('replaced', '已替换', 'Replaced'), expired: text('expired', '已到期', 'Expired'), revoked: text('revokedStatus', '已撤销', 'Revoked'),
  })[value];
  return <section className="mt-4 space-y-4 rounded-card border border-border bg-panel p-4 text-text" data-testid="integration-token-panel">
    <div className="flex flex-wrap items-center justify-between gap-3"><h3 className="font-semibold">{text('title', '快捷接入 Token', 'Quick integration tokens')}</h3>
      <button className={BUTTON} type="button" disabled={busy} onClick={() => { setSecret(null); onClose(); }}>{text('close', '关闭 Token 管理', 'Close token manager')}</button></div>
    <p className="text-sm text-text-2">{text('help', '直接用 Bearer Token 调用已授权接口，无需换取 OAuth Token。权限始终以安装当前授权为准。', 'Call authorized APIs directly with a Bearer token, without OAuth exchange. Current installation authority always applies.')}</p>
    <PublicationField name="appId" help={text('appIdHelp', '应用标识保持不变；认证使用 Token，不需要额外的 appId 请求头。', 'Stable application identity. Authenticate with the token; an extra appId header is unnecessary.')} child={<input className={CONTROL} value={appId} readOnly />} />
    <div className="flex flex-wrap gap-2"><button className={BUTTON} type="button" disabled={busy || loading} onClick={() => void load()}>{text('refresh', '刷新清单', 'Refresh token list')}</button>
      <button className={PRIMARY} type="button" disabled={!active || busy || loading || denied || !!operation || !!secret} onClick={() => begin('create')}>{text('create', '生成 Token', 'Generate token')}</button></div>
    {!active && <p role="status">{text('inactive', '应用或安装已停用，只能查看或撤销。', 'Application or installation is inactive. View or revoke existing tokens only.')}</p>}
    {error && <p role="alert" className="text-sm text-status-red">{error}</p>}{notice && <p role="status" className="text-sm">{notice}</p>}
    {loading && <p role="status">{text('loading', '正在读取 Token…', 'Loading tokens…')}</p>}
    {!loading && !denied && !rows.length && <p>{text('empty', '还没有快捷接入 Token。', 'No integration tokens yet.')}</p>}
    {secret && <div className="space-y-3 rounded-control border border-border-strong p-4" data-testid="integration-token-secret">
      <p className="font-medium">{text('once', '仅显示一次，请立即保存。关闭或刷新页面后无法再次读取。', 'Shown once. Save it now; closing or reloading the page removes this secret.')}</p>
      <PublicationField name={text('secret', '新 Token', 'New token')} child={<textarea className={`${CONTROL} font-mono break-all`} rows={3} readOnly value={secret.token} spellCheck={false} autoComplete="off" />} />
      <p>{secret.metadata.expirationMode === 'never' ? text('permanent', '永久有效（仍可撤销和轮换）', 'Never expires (revocation and rotation still apply)') : `${text('expiresAt', '到期时间', 'Expires at')}: ${date(secret.metadata.expiresAt)}`}</p>
      {cutoff && <p>{text('oldCutoff', '旧 Token 接受截止', 'Old token accepted until')}: {date(cutoff)}</p>}
      <div className="flex flex-wrap gap-2"><button className={PRIMARY} type="button" onClick={async () => {
        try { await navigator.clipboard.writeText(secret.token); setCopied(true); }
        catch { setError(text('copyFailed', '复制失败，请手动选择并保存 Token。', 'Copy failed. Select and save the token manually.')); }
      }}>{text('copy', '复制 Token', 'Copy token')}</button><button className={BUTTON} type="button" onClick={() => { setSecret(null); setCopied(false); setCutoff(null); }}>{text('saved', '已保存，关闭原文', 'Saved; close secret')}</button></div>
      {copied && <p role="status">{text('copied', '已复制。', 'Copied.')}</p>}
    </div>}
    {operation && <form className="space-y-3 rounded-control border border-border-strong p-4" onSubmit={event => void submit(event)} data-testid={`integration-token-${operation.kind}`}>
      <h4 className="font-medium">{operation.kind === 'create' ? text('create', '生成 Token', 'Generate token') : operation.kind === 'rotate' ? text('rotate', '轮换 Token', 'Rotate token') : text('revoke', '撤销 Token', 'Revoke token')}{operation.row ? ` · ${operation.row.name}` : ''}</h4>
      {operation.kind === 'revoke' ? <p>{text('revokeWarning', '撤销后使用此 Token 的新请求立即失败，其他 Token 不受影响。此操作不可恢复。', 'New requests using this token immediately fail. Other tokens are unaffected. This cannot be undone.')}</p> : <>
        {operation.kind === 'create' && <PublicationField name={text('name', 'Token 名称', 'Token name')} child={<input className={CONTROL} required maxLength={160} value={name} disabled={busy} onChange={event => setName(event.target.value)} />} />}
        <PublicationField name={text('mode', '有效期模式', 'Expiration mode')} child={<select className={CONTROL} value={mode} disabled={busy} onChange={event => setMode(event.target.value as 'fixed' | 'never')}><option value="fixed">{text('fixed', '定期', 'Fixed expiry')}</option><option value="never">{text('never', '永久', 'Never expires')}</option></select>} />
        {mode === 'fixed' ? <PublicationField name={text('days', '有效天数', 'Valid for days')} help={text('daysHelp', '默认 30 天，可选 1–90 天；服务端校验并返回实际到期时间。', 'Default 30 days; choose 1–90 days. The server validates and returns the authoritative expiry.')} child={<input className={CONTROL} type="number" min={1} max={90} step={1} required value={days} disabled={busy} onChange={event => setDays(event.target.valueAsNumber)} />} /> : <p>{text('neverWarning', '永久 Token 不会自然到期；请妥善保存，定期轮换，泄漏时立即撤销。', 'Permanent tokens have no natural expiry. Store securely, rotate periodically and revoke immediately if exposed.')}</p>}
        {operation.kind === 'rotate' && <PublicationField name={text('grace', '旧 Token 宽限分钟数', 'Old token grace minutes')} help={text('graceHelp', '0 为立即切换，默认 1440，最多 10080；不会延长原到期时间。', '0 switches immediately; default 1440, maximum 10080. Original expiry is never extended.')} child={<input className={CONTROL} type="number" min={0} max={10080} step={1} required value={grace} disabled={busy} onChange={event => setGrace(event.target.valueAsNumber)} />} />}
      </>}
      <div className="flex flex-wrap gap-2"><button className={BUTTON} type="button" disabled={busy} onClick={() => { setOperation(null); setError(''); }}>{text('cancel', '取消', 'Cancel')}</button><button className={operation.kind === 'revoke' ? DANGER : PRIMARY} type="submit" disabled={busy || denied || (operation.kind !== 'revoke' && !active)}>{busy ? text('working', '处理中…', 'Working…') : text('confirm', '确认', 'Confirm')}</button></div>
    </form>}
    {!denied && <ul className="space-y-3">{rows.map(row => <li key={row.pid} className="space-y-2 rounded-control border border-border p-3" data-testid="integration-token-row">
      <div className="flex flex-wrap justify-between gap-2"><strong className="break-words">{row.name}</strong><span>{status(row.status)}</span></div>
      <p className="text-sm">{row.expirationMode === 'never' ? text('permanent', '永久有效（仍可撤销和轮换）', 'Never expires (revocation and rotation still apply)') : `${text('expiresAt', '到期时间', 'Expires at')}: ${date(row.expiresAt)}`}</p>
      {row.acceptUntil && <p className="text-sm">{text('oldCutoff', '旧 Token 接受截止', 'Old token accepted until')}: {date(row.acceptUntil)}</p>}
      <p className="text-sm text-text-2">{text('lastUsed', '最近使用', 'Last used')}: {date(row.lastUsedAt)}</p>
      <div className="flex flex-wrap gap-2"><button className={BUTTON} type="button" disabled={!active || row.status !== 'active' || busy || !!operation || !!secret} onClick={() => begin('rotate', row)}>{text('rotate', '轮换 Token', 'Rotate token')}</button>
        <button className={DANGER} type="button" disabled={row.status === 'revoked' || busy || !!operation || !!secret} onClick={() => begin('revoke', row)}>{text('revoke', '撤销 Token', 'Revoke token')}</button></div>
    </li>)}</ul>}
  </section>;
}
