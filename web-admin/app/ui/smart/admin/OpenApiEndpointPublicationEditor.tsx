import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { publicationRequest, PublicationApiError, type EndpointCandidate, type EndpointDescription, type EndpointPublicationView,
  type PublicEndpoint, type PublicationView, type ActionPublicationView, type QueryPublicationView } from '~/services/open-api-publications';
import { PublicationField, usePublicationConfirmation } from './PublicationControls';

const BUTTON = 'rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text hover:bg-hover disabled:opacity-50';
const CONTROL = 'w-full rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text disabled:opacity-50';
const initial = (): PublicEndpoint => ({ code: '', candidateCode: '', adapterCode: '', bindingCode: '', schemaVersion: 1,
  sourceFingerprint: '', scopes: { invoke: '' }, timeoutMs: 5000, maxConcurrent: 2 });

/** Platform configuration block for explicitly adapted internal routes; never an arbitrary URL proxy. */
export function OpenApiEndpointPublicationEditor({ onClose }: { onClose: () => void }) {
  const { t, locale } = useI18n();
  const text = (key: string, zh: string, en: string) => t(`openPlatform.endpoints.${key}`, undefined, locale.startsWith('zh') ? zh : en);
  const [views, setViews] = useState<EndpointPublicationView[]>([]); const [candidates, setCandidates] = useState<EndpointCandidate[]>([]);
  const [bindings, setBindings] = useState<{ code: string; kind: 'resource' | 'action' | 'query' }[]>([]);
  const [view, setView] = useState<EndpointPublicationView>(); const [draft, setDraft] = useState<PublicEndpoint>(initial);
  const [source, setSource] = useState<EndpointDescription>(); const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false); const [loading, setLoading] = useState(true); const [dirty, setDirty] = useState(false);
  const [catalogFailed, setCatalogFailed] = useState(false);
  const [denied, setDenied] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('');
  const [search, setSearch] = useState(''); const [confirmation, setConfirmation] = useState<'publish' | 'withdraw' | 'discard' | null>(null);
  const confirmationRef = useRef<HTMLDivElement>(null); const pending = useRef(false); const generation = useRef(0);
  const onConfirmationKey = usePublicationConfirmation(confirmation, confirmationRef, () => setConfirmation(null), busy);
  const candidate = candidates.find(row => row.code === draft.candidateCode);
  const bindingKind = draft.adapterCode.startsWith('resource.') ? 'resource' : draft.adapterCode.startsWith('action.') ? 'action' : 'query';
  const valid = !catalogFailed && !!source && source.fingerprint === draft.sourceFingerprint && source.candidate.code === draft.candidateCode
    && source.bindingCode === draft.bindingCode && /^[a-z][a-z0-9.-]{1,90}$/.test(draft.code)
    && /^[a-z][a-z0-9.:-]{1,159}$/.test(draft.scopes.invoke) && Number.isInteger(draft.schemaVersion) && draft.schemaVersion > 0
    && Number.isInteger(draft.timeoutMs) && draft.timeoutMs >= source.minTimeoutMs && draft.timeoutMs <= 30000
    && Number.isInteger(draft.maxConcurrent) && draft.maxConcurrent >= 1 && draft.maxConcurrent <= 32;
  const fail = (cause: unknown) => {
    if (cause instanceof PublicationApiError && [401, 403].includes(cause.status)) {
      setDenied(true); setSource(undefined); setError(text('denied', '需要当前租户的开放平台管理权限。输入已保留，恢复权限后请刷新。', 'Open Platform management permission is required. Your inputs are retained; refresh after access is restored.'));
    } else if (cause instanceof PublicationApiError && cause.status === 409) setError(text('conflict', '配置或来源已变化。输入已保留，请放弃修改并重新读取服务器版本。', 'Configuration or source changed. Inputs are retained; discard changes and reopen the server version.'));
    else setError(text('failed', '接口配置请求失败。请检查来源与绑定合同，修正后重试。', 'Endpoint configuration failed. Check the source and bound contract, correct the issue, then retry.'));
  };
  const load = async () => {
    const id = ++generation.current; setLoading(true); setError('');
    try {
      const [next, routes, resources, actions, queries] = await Promise.all([
        publicationRequest<EndpointPublicationView[]>('/api/open-platform/endpoint-publications'),
        publicationRequest<EndpointCandidate[]>(`/api/open-platform/endpoint-candidates?search=${encodeURIComponent(search)}`),
        publicationRequest<PublicationView[]>('/api/open-platform/publications'),
        publicationRequest<ActionPublicationView[]>('/api/open-platform/action-publications'),
        publicationRequest<QueryPublicationView[]>('/api/open-platform/query-publications'),
      ]);
      if (id !== generation.current) return;
      setViews(next); setCandidates(routes); setDenied(false); setCatalogFailed(false);
      setBindings([...resources.filter(row => row.status === 'published').map(row => ({ code: row.code, kind: 'resource' as const })),
        ...actions.filter(row => row.status === 'published').map(row => ({ code: row.code, kind: 'action' as const })),
        ...queries.filter(row => row.status === 'published').map(row => ({ code: row.code, kind: 'query' as const }))]);
    } catch (cause) { if (id === generation.current) { setCatalogFailed(true); fail(cause); } } finally { if (id === generation.current) setLoading(false); }
  };
  useEffect(() => { void load(); return () => { generation.current++; }; }, []);
  useEffect(() => { const listener = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', listener); return () => window.removeEventListener('beforeunload', listener); }, [dirty]);
  const change = (next: PublicEndpoint) => { setDraft(next); setDirty(true); setNotice(''); setError(''); };
  const open = (row?: EndpointPublicationView) => { setView(row); setDraft(row ? structuredClone(row.draft) : initial()); setSource(undefined); setEditing(true); setDirty(false); setNotice(''); setError(''); };
  const verify = async () => {
    if (pending.current || !draft.candidateCode || !draft.bindingCode) return;
    pending.current = true; setBusy(true); setError(''); setSource(undefined);
    try {
      const current = await publicationRequest<EndpointDescription>(`/api/open-platform/endpoint-source?candidateCode=${encodeURIComponent(draft.candidateCode)}&bindingCode=${encodeURIComponent(draft.bindingCode)}`);
      if (!current.candidate.available || current.candidate.adapterCode !== draft.adapterCode || view?.activeVersion != null && current.fingerprint !== draft.sourceFingerprint) { setError(text('changed', '已发布来源或数据合同已变化，请使用新地址重新审核发布；旧接口仍可停用。', 'Published source or data contract changed. Review and publish a new address; the old endpoint may still be withdrawn.')); return; }
      setSource(current); setDraft(value => ({ ...value, sourceFingerprint: current.fingerprint, timeoutMs: Math.max(value.timeoutMs, current.minTimeoutMs) }));
      if (current.fingerprint !== draft.sourceFingerprint) setDirty(true);
      setNotice(text('verified', '来源和绑定合同已校验；安装仍需接口及数据授权。', 'Source and bound contract validated. Installation endpoint and data grants are still required.'));
    } catch (cause) { fail(cause); } finally { pending.current = false; setBusy(false); }
  };
  const save = async (event: FormEvent) => {
    event.preventDefault(); if (!valid || pending.current || denied) return;
    pending.current = true; setBusy(true); setError('');
    try { const result = await publicationRequest<EndpointPublicationView>(`/api/open-platform/endpoint-publications/${encodeURIComponent(draft.code)}/draft`,
      { method: 'PUT', body: JSON.stringify({ expectedRevision: view?.draftRevision ?? 0, definition: draft }) });
      setView(result); setDraft(result.draft); setDirty(false); await load(); setNotice(text('saved', '接口草稿已保存。', 'Endpoint draft saved.'));
    } catch (cause) { fail(cause); } finally { pending.current = false; setBusy(false); }
  };
  const mutate = async (operation: 'publish' | 'withdraw') => {
    if (!view || dirty || pending.current || denied) return; pending.current = true; setBusy(true); setError('');
    try { const result = await publicationRequest<EndpointPublicationView>(`/api/open-platform/endpoint-publications/${encodeURIComponent(view.code)}/${operation}`,
      { method: 'POST', body: JSON.stringify({ expectedRevision: view.draftRevision }) });
      setView(result); setDraft(result.draft); setConfirmation(null); await load();
      setNotice(operation === 'publish' ? text('published', '接口已发布；请在应用安装中单独授权。', 'Endpoint published. Grant it separately in the application installation.') : text('withdrawn', '接口已停用，调用与合同下载已关闭。', 'Endpoint withdrawn. Calls and contract downloads are closed.'));
    } catch (cause) { fail(cause); } finally { pending.current = false; setBusy(false); }
  };
  const download = async (kind: 'openapi' | 'types') => {
    if (!view || pending.current) return; pending.current = true; setBusy(true); setError('');
    try { const response = await fetch(`/api/open-platform/endpoint-publications/${encodeURIComponent(view.code)}/${kind}`);
      if (!response.ok) throw new PublicationApiError(response.status, 'Endpoint contract download failed');
      const body = await response.text();
      if (!body.trim() || (kind === 'openapi' ? JSON.parse(body).openapi !== '3.1.0' || JSON.parse(body)['x-schema-version'] !== view.activeVersion : !body.includes('export type EndpointRequest') || !body.includes('export type EndpointResult'))) throw new Error('Invalid endpoint artifact');
      const url = URL.createObjectURL(new Blob([body], { type: kind === 'openapi' ? 'application/json' : 'text/plain;charset=UTF-8' }));
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${view.code}.${kind === 'openapi' ? 'openapi.json' : 'd.ts'}`; document.body.append(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (cause) { fail(cause); } finally { pending.current = false; setBusy(false); }
  };
  return <section className="space-y-5" data-testid="endpoint-publication-editor">
    <header className="flex flex-wrap items-start justify-between gap-3"><div><h1 className="text-xl font-semibold">{text('title', '内部接口对外发布', 'Publish internal endpoints')}</h1><p className="mt-2 max-w-3xl text-sm text-text-2">{text('help', '选择已登记机器适配的内部接口，绑定公开数据合同。接口授权与原有行、字段及业务权限共同生效；输入 URL 不会自动开放接口。', 'Select a developer-adapted internal route and bind its public data contract. Endpoint grants combine with existing rows, fields and business authority; entering a URL never opens an API.')}</p></div>
      <button className={BUTTON} type="button" disabled={busy || dirty} onClick={onClose}>{text('back', '返回业务接口', 'Back to business APIs')}</button></header>
    <div className="flex flex-wrap items-end gap-2"><PublicationField name={text('search', '查找内部接口', 'Find internal endpoint')} child={<input className={CONTROL} maxLength={200} value={search} disabled={busy || loading || dirty} placeholder="/api/dynamic" onChange={event => setSearch(event.target.value)} />} />
      <button className={BUTTON} type="button" disabled={busy || loading || dirty && !denied} onClick={() => void load()}>{text('refresh', '刷新接口目录', 'Refresh endpoint catalog')}</button>
      <button className={BUTTON} type="button" disabled={busy || loading || dirty || denied} onClick={() => open()}>{text('new', '新建外部接口', 'New external endpoint')}</button></div>
    {loading && <p role="status">{text('loading', '正在读取接口目录…', 'Loading endpoint catalog…')}</p>}
    {error && <p role="alert" className="rounded-control border border-status-red p-3 text-sm text-status-red">{error}</p>}
    {notice && <p role="status" className="text-sm text-status-green">{notice}</p>}
    {!loading && !denied && !editing && <><ul className="space-y-3">{views.map(row => <li className="flex flex-wrap items-center justify-between gap-3 rounded-control border border-border bg-panel p-3" key={row.code}>
      <div><strong>{row.code}</strong><p className="text-sm text-text-2">{row.status === 'published' ? text('live', '已发布', 'Published') : row.status === 'withdrawn' ? text('inactive', '已停用', 'Withdrawn') : text('draft', '草稿', 'Draft')}{row.activeVersion ? ` · v${row.activeVersion}` : ''}</p></div><button className={BUTTON} type="button" onClick={() => open(row)}>{text('edit', '编辑外部合同', 'Edit external contract')}</button></li>)}</ul>
      {!views.length && <p>{text('empty', '暂无外部接口，请选择已登记来源并新建合同。', 'No external endpoints. Select a registered source and create a contract.')}</p>}
      <details className="rounded-control border border-border bg-panel p-3"><summary>{text('inventory', '查看内部候选与未开放原因', 'Inspect internal candidates and eligibility')}</summary><ul className="mt-3 max-h-72 space-y-2 overflow-auto">{candidates.map(row => <li key={`${row.internalMethod}:${row.internalPath}`} className="break-all text-sm"><code>{row.internalMethod} {row.internalPath}</code> · {row.available ? text('eligible', '已登记机器适配，仍需发布和授权', 'Machine adaptation registered; publication and authority still required') : text('unsupported', '尚未登记机器适配，禁止直接开放', 'No machine adaptation; direct publication is blocked')}</li>)}</ul></details></>}
    {editing && <form data-testid="endpoint-publication-form" onSubmit={event => void save(event)} className="space-y-4 rounded-card border border-border bg-panel p-5">
      <div className="flex flex-wrap items-center justify-between gap-2"><h2 className="font-semibold">{text('contract', '外部接口合同', 'External endpoint contract')}</h2><button className={BUTTON} type="button" disabled={busy} onClick={() => dirty ? setConfirmation('discard') : setEditing(false)}>{text('close', '关闭接口编辑', 'Close endpoint editor')}</button></div>
      {!denied && <fieldset disabled={busy || loading || catalogFailed} className="space-y-4">
        <PublicationField name={text('code', '外部接口编码', 'External endpoint code')} help={text('codeHelp', '稳定公开名称，例如 office.devices.lookup；不填写内部 URL。', 'Stable public name, for example office.devices.lookup; do not enter an internal URL.')} child={<input className={CONTROL} required disabled={!!view} pattern="[a-z][a-z0-9.-]{1,90}" maxLength={91} value={draft.code} onChange={event => change({ ...draft, code: event.target.value })} />} />
        <PublicationField name={text('source', '内部来源接口', 'Internal source endpoint')} child={<select className={CONTROL} required disabled={view?.activeVersion != null} value={draft.candidateCode} onChange={event => { const row = candidates.find(item => item.code === event.target.value); setSource(undefined); change({ ...draft, candidateCode: event.target.value, adapterCode: row?.adapterCode ?? '', bindingCode: '', sourceFingerprint: '' }); }}><option value="">{text('chooseSource', '选择已登记来源', 'Choose a registered source')}</option>{candidates.filter(row => row.available).map(row => <option key={row.code} value={row.code!}>{row.localizedDisplayNames[locale.startsWith('zh') ? 'zh-CN' : 'en-US']} · {row.internalMethod} {row.internalPath}</option>)}{draft.candidateCode && !candidate && <option value={draft.candidateCode}>{text('missing', '原来源已不可用', 'Original source is unavailable')}</option>}</select>} />
        <PublicationField name={text('binding', '绑定公开合同', 'Bound public contract')} help={text('bindingHelp', '资源、查询或业务动作必须先发布；它的数据权限会继续生效。', 'Publish the resource, query or business action first; its data authority remains enforced.')} child={<select className={CONTROL} required disabled={!draft.candidateCode || view?.activeVersion != null} value={draft.bindingCode} onChange={event => { setSource(undefined); change({ ...draft, bindingCode: event.target.value, sourceFingerprint: '' }); }}><option value="">{text('chooseBinding', '选择已发布合同', 'Choose a published contract')}</option>{bindings.filter(row => row.kind === bindingKind).map(row => <option value={row.code} key={row.code}>{row.code}</option>)}{draft.bindingCode && !bindings.some(row => row.code === draft.bindingCode && row.kind === bindingKind) && <option value={draft.bindingCode}>{text('missingBinding', '原合同已不可用', 'Original contract is unavailable')}</option>}</select>} />
        <button className={BUTTON} type="button" disabled={!draft.candidateCode || !draft.bindingCode} onClick={() => void verify()}>{text('verify', '校验来源与合同', 'Validate source and contract')}</button>
        {candidate && <p className="break-all text-sm">{text('address', '外部地址', 'External address')}: <code>{candidate.externalMethod} /api/open/v1/endpoints/{draft.code || '…'}{candidate.targetRequired ? '/{recordPid}' : ''}</code></p>}
        <PublicationField name={text('scope', '接口调用 Scope', 'Endpoint invocation scope')} child={<input className={CONTROL} required disabled={view?.activeVersion != null} pattern="[a-z][a-z0-9.:-]{1,159}" value={draft.scopes.invoke} placeholder="office.devices.lookup.invoke" onChange={event => change({ ...draft, scopes: { invoke: event.target.value } })} />} />
        <div className="grid gap-4 md:grid-cols-3"><PublicationField name={text('version', '合同版本', 'Schema version')} child={<input className={CONTROL} type="number" min={(view?.activeVersion ?? 0) + 1} step={1} required value={draft.schemaVersion} onChange={event => change({ ...draft, schemaVersion: event.target.valueAsNumber })} />} />
          <PublicationField name={text('timeout', '执行超时（毫秒）', 'Execution timeout (ms)')} child={<input className={CONTROL} type="number" required min={source?.minTimeoutMs ?? 1} max={30000} step={1} value={draft.timeoutMs} onChange={event => change({ ...draft, timeoutMs: event.target.valueAsNumber })} />} />
          <PublicationField name={text('concurrency', '同时执行上限', 'Maximum concurrent executions')} child={<input className={CONTROL} type="number" min={1} max={32} step={1} required value={draft.maxConcurrent} onChange={event => change({ ...draft, maxConcurrent: event.target.valueAsNumber })} />} /></div>
      </fieldset>}
      <div className="sticky bottom-0 flex flex-wrap gap-2 border-t border-border bg-panel py-3"><button className={BUTTON} type="submit" disabled={busy || denied || loading || !valid || !dirty}>{text('save', '保存接口草稿', 'Save endpoint draft')}</button>
        <button className={BUTTON} type="button" disabled={busy || denied || dirty || !view || !valid || draft.schemaVersion <= (view.activeVersion ?? 0)} onClick={() => setConfirmation('publish')}>{text('publish', '发布外部接口', 'Publish external endpoint')}</button>
        <button className={BUTTON} type="button" disabled={busy || denied || dirty || view?.status !== 'published'} onClick={() => setConfirmation('withdraw')}>{text('withdraw', '停用外部接口', 'Withdraw external endpoint')}</button>
        <button className={BUTTON} type="button" disabled={busy || denied || dirty || view?.status !== 'published'} onClick={() => void download('openapi')}>{text('openapi', '下载接口 OpenAPI', 'Download endpoint OpenAPI')}</button>
        <button className={BUTTON} type="button" disabled={busy || denied || dirty || view?.status !== 'published'} onClick={() => void download('types')}>{text('types', '下载接口类型', 'Download endpoint types')}</button></div>
    </form>}
    {confirmation && <div ref={confirmationRef} tabIndex={-1} onKeyDown={onConfirmationKey} role="group" aria-label={text('confirmation', '外部接口操作确认', 'External endpoint confirmation')} className="space-y-3 rounded-control border border-border bg-bg p-3">
      <p>{confirmation === 'discard' ? text('discardHelp', '放弃未保存修改并关闭？服务器合同不变。', 'Discard unsaved changes and close? The server contract stays unchanged.') : confirmation === 'publish' ? text('publishHelp', '确认发布此 HTTP 合同？应用安装仍需单独授权。', 'Publish this HTTP contract? Application installations still require explicit grants.') : text('withdrawHelp', '确认停用？所有应用对此接口的调用将被拒绝。', 'Withdraw this endpoint? Calls from every application will be denied.')}</p>
      <button className={BUTTON} disabled={busy} type="button" onClick={() => confirmation === 'discard' ? (setEditing(false), setDirty(false), setConfirmation(null)) : void mutate(confirmation)}>{text('confirm', '确认', 'Confirm')}</button>
      <button className={BUTTON} disabled={busy} type="button" onClick={() => setConfirmation(null)}>{text('cancel', '取消', 'Cancel')}</button></div>}
  </section>;
}
