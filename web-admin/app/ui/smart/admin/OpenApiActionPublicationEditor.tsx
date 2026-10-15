import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import { publicationRequest, PublicationApiError, type ActionPublicationView, type ActionSource, type PublicationView, type PublicAction } from '~/services/open-api-publications';
import { PublicationField, usePublicationConfirmation } from './PublicationControls';

const BUTTON = 'rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text hover:bg-hover disabled:opacity-50';
const PRIMARY = BUTTON.replace('bg-panel', 'bg-accent').replace('hover:bg-hover', 'hover:bg-accent-hover').replace('text-text', 'text-white');
const CONTROL = 'w-full rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text disabled:opacity-50';
const initial = (): PublicAction => ({ code: '', resourceCode: '', sourceKind: 'command', sourceCode: '', schemaVersion: 1,
  sourceFingerprint: '', permission: null, scopes: { execute: '' }, parameters: {}, inputFields: {}, effectFields: [], outputFields: {}, timeoutMs: 5000, maxConcurrent: 2 });

/** Typed business API publication block hosted by the platform administration DSL. */
export function OpenApiActionPublicationEditor() {
  const { t, locale } = useI18n();
  const text = (key: string, zh: string, en: string) => t(`openPlatform.actions.${key}`, undefined, locale.startsWith('zh') ? zh : en);
  const [views, setViews] = useState<ActionPublicationView[]>([]); const [resources, setResources] = useState<PublicationView[]>([]);
  const [sources, setSources] = useState<ActionSource[]>([]); const [view, setView] = useState<ActionPublicationView>();
  const [draft, setDraft] = useState<PublicAction>(initial); const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true); const [sourceLoading, setSourceLoading] = useState(false);
  const [busy, setBusy] = useState(false); const [dirty, setDirty] = useState(false); const [denied, setDenied] = useState(false);
  const [error, setError] = useState(''); const [notice, setNotice] = useState('');
  const [confirmation, setConfirmation] = useState<'publish' | 'withdraw' | 'discard' | null>(null);
  const confirmationRef = useRef<HTMLDivElement>(null); const pending = useRef(false); const generation = useRef(0);
  const onConfirmationKey = usePublicationConfirmation(confirmation, confirmationRef, () => setConfirmation(null), busy);
  const selected = sources.find(source => source.sourceKind === draft.sourceKind && source.sourceCode === draft.sourceCode);
  const current = !!selected && selected.available && selected.fingerprint === draft.sourceFingerprint;
  const minTimeoutMs = selected ? selected.minTimeoutMs : 1;
  const immutable = view?.activeVersion != null;
  const valid = current && /^[a-z][a-z0-9.-]{1,92}$/.test(draft.code) && /^[a-z][a-z0-9.:-]{1,159}$/.test(draft.scopes.execute)
    && Number.isInteger(draft.schemaVersion) && draft.schemaVersion > 0 && Number.isInteger(draft.timeoutMs) && draft.timeoutMs >= minTimeoutMs && draft.timeoutMs <= 30000
    && Number.isInteger(draft.maxConcurrent) && draft.maxConcurrent >= 1 && draft.maxConcurrent <= 32;
  const fail = (cause: unknown) => {
    if (cause instanceof PublicationApiError && [401, 403].includes(cause.status)) {
      setDenied(true); setError(text('denied', '需要当前租户的开放平台管理权限。', 'Open Platform administrator permission in this tenant is required.'));
    } else if (cause instanceof PublicationApiError && cause.status === 409) setError(text('conflict', '配置已改变，你的修改已保留。放弃本地修改并重新打开后再继续。', 'Configuration changed. Your edits are retained; discard them and reopen before continuing.'));
    else setError(cause instanceof Error ? cause.message : text('failed', '请求失败，请重试。', 'Request failed. Please retry.'));
  };
  const load = async () => {
    setLoading(true); setError(''); setDenied(false);
    try { const [actions, publications] = await Promise.all([
      publicationRequest<ActionPublicationView[]>('/api/open-platform/action-publications'), publicationRequest<PublicationView[]>('/api/open-platform/publications'),
    ]); setViews(actions); setResources(publications.filter(row => row.status === 'published')); }
    catch (cause) { fail(cause); } finally { setLoading(false); }
  };
  const loadSources = async (resourceCode: string) => {
    const id = ++generation.current; setSources([]); if (!resourceCode) { setSourceLoading(false); return; }
    setSourceLoading(true);
    try { const result = await publicationRequest<ActionSource[]>(`/api/open-platform/action-sources?resourceCode=${encodeURIComponent(resourceCode)}`);
      if (id === generation.current) setSources(result);
    } catch (cause) { if (id === generation.current) fail(cause); }
    finally { if (id === generation.current) setSourceLoading(false); }
  };
  useEffect(() => { void load(); return () => { generation.current++; }; }, []);
  useEffect(() => { const listener = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', listener); return () => window.removeEventListener('beforeunload', listener);
  }, [dirty]);
  const change = (next: PublicAction) => { setDraft(next); setDirty(true); setError(''); setNotice(''); };
  const open = (row?: ActionPublicationView) => {
    setView(row); setDraft(row ? structuredClone(row.draft) : initial()); setEditing(true); setDirty(false); setError(''); setNotice('');
    setConfirmation(null); void loadSources(row?.draft.resourceCode ?? '');
  };
  const save = async (event: FormEvent) => {
    event.preventDefault(); if (!valid || pending.current || denied) return;
    pending.current = true; setBusy(true); setError(''); setNotice('');
    try { const saved = await publicationRequest<ActionPublicationView>(`/api/open-platform/action-publications/${encodeURIComponent(draft.code)}/draft`,
      { method: 'PUT', body: JSON.stringify({ expectedRevision: view?.draftRevision ?? 0, definition: draft }) });
      setView(saved); setDraft(saved.draft); setDirty(false); await load(); setNotice(text('saved', '业务接口草稿已保存并通过来源校验。', 'Business API draft saved and source validated.'));
    } catch (cause) { fail(cause); } finally { pending.current = false; setBusy(false); }
  };
  const mutate = async (operation: 'publish' | 'withdraw') => {
    if (!view || dirty || pending.current || denied) return;
    pending.current = true; setBusy(true); setError(''); setNotice('');
    try { const result = await publicationRequest<ActionPublicationView>(`/api/open-platform/action-publications/${encodeURIComponent(view.code)}/${operation}`,
      { method: 'POST', body: JSON.stringify({ expectedRevision: view.draftRevision }) }); setView(result); setDraft(result.draft); setConfirmation(null); await load();
      setNotice(operation === 'publish' ? text('published', '业务接口已发布；安装仍需单独授权。', 'Business API published. Installations still require explicit authority.')
        : text('withdrawn', '业务接口已停用，外部调用及合同下载已关闭。', 'Business API withdrawn. External calls and contract downloads are closed.'));
    } catch (cause) { fail(cause); } finally { pending.current = false; setBusy(false); }
  };
  const download = async (kind: 'openapi' | 'types') => {
    if (!view || pending.current) return; pending.current = true; setBusy(true); setError('');
    try {
      const response = await fetch(`/api/open-platform/action-publications/${encodeURIComponent(view.code)}/${kind}`);
      if (!response.ok) { const body = await response.json(); throw new PublicationApiError(response.status, body.message); }
      const content = await response.text();
      if (!content.trim() || kind === 'openapi' && (JSON.parse(content).openapi !== '3.1.0' || JSON.parse(content)['x-schema-version'] !== view.activeVersion)
          || kind === 'types' && (!content.includes('export type ActionRequest') || !content.includes('export type ActionResult')))
        throw new Error(text('invalidArtifact', '合同文件无效，请重新下载。', 'Invalid contract file. Download again.'));
      const url = URL.createObjectURL(new Blob([content], { type: kind === 'openapi' ? 'application/json' : 'text/plain;charset=UTF-8' }));
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${view.code}.${kind === 'openapi' ? 'openapi.json' : 'd.ts'}`;
      document.body.append(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (cause) { fail(cause); } finally { pending.current = false; setBusy(false); }
  };
  return <div className="space-y-5 text-text" data-testid="action-publication-editor">
    <header className="flex flex-wrap items-start justify-between gap-3"><div><h1 className="text-xl font-semibold">{text('title', '公共业务接口', 'Public business APIs')}</h1>
      <p className="mt-2 text-sm text-text-secondary">{text('help', '将现有状态流转命令或已注册只读 Handler 绑定到公共资源。外部调用始终受安装 Scope、行范围、字段及业务变更授权约束。', 'Bind a state-transition command or registered read-only handler to a public resource. Installation scopes, rows, fields and controlled effects remain enforced.')}</p></div>
      <Link className={BUTTON} to="/settings/api-docs">{text('console', '返回开放平台', 'Back to Open Platform')}</Link></header>
    {error && <p role="alert" className="rounded-control border border-danger bg-danger-weak p-3 text-sm text-danger">{error}</p>}
    {notice && <p role="status" className="text-sm text-success">{notice}</p>}
    {!editing && <section className="space-y-4 rounded-card border border-border bg-panel p-5">
      <div className="flex flex-wrap gap-2"><button className={BUTTON} disabled={loading || busy} type="button" onClick={() => void load()}>{text('refresh', '刷新目录', 'Refresh catalog')}</button>
        <button className={PRIMARY} disabled={loading || busy || denied} type="button" onClick={() => open()}>{text('new', '新建业务接口', 'New business API')}</button></div>
      {loading && <p role="status">{text('loading', '正在读取业务接口…', 'Loading business APIs…')}</p>}
      {!loading && !denied && !views.length && <p>{text('empty', '还没有公共业务接口，请先发布一个资源合同。', 'No public business APIs. Publish a resource contract first.')}</p>}
      {!loading && !denied && <ul className="space-y-3">{views.map(row => <li key={row.code} className="flex flex-wrap items-center justify-between gap-3 rounded-control border border-border p-3">
        <div><strong>{row.code}</strong><p className="text-sm text-text-secondary">{row.status === 'published' ? text('live', '已发布', 'Published') : row.status === 'withdrawn' ? text('inactive', '已停用', 'Withdrawn') : text('draft', '草稿', 'Draft')}{row.activeVersion ? ` · v${row.activeVersion}` : ''}</p></div>
        <button className={BUTTON} type="button" onClick={() => open(row)}>{text('edit', '编辑业务合同', 'Edit business contract')}</button></li>)}</ul>}
    </section>}
    {editing && <form onSubmit={event => void save(event)} className="space-y-4 rounded-card border border-border bg-panel p-5" data-testid="action-publication-form">
      <div className="flex flex-wrap items-center justify-between gap-3"><h2 className="font-semibold">{text('contract', '业务接口合同', 'Business API contract')}</h2><button className={BUTTON} type="button" disabled={busy} onClick={() => dirty ? setConfirmation('discard') : setEditing(false)}>{text('close', '关闭编辑', 'Close editor')}</button></div>
      <fieldset disabled={busy || denied} className="space-y-4">
        <PublicationField name={text('code', '公共接口地址', 'Public API address')} child={<input className={CONTROL} required pattern="[a-z][a-z0-9.-]{1,92}" maxLength={93} value={draft.code} disabled={!!view} placeholder="office.devices.assign" onChange={event => change({ ...draft, code: event.target.value })} />} />
        <PublicationField name={text('resource', '绑定公共资源', 'Bound public resource')} help={text('resourceHelp', '数据授权和 ETag 均来自此资源。命令需要修改授权，只读 Handler 需要列表授权。', 'Data authority and ETags come from this resource. Commands require UPDATE authority; read-only handlers require LIST authority.')} child={<select className={CONTROL} required value={draft.resourceCode} disabled={immutable} onChange={event => {
          change({ ...draft, resourceCode: event.target.value, sourceCode: '', sourceFingerprint: '', parameters: {}, inputFields: {}, effectFields: [], outputFields: {} }); void loadSources(event.target.value);
        }}><option value="">{text('chooseResource', '选择已发布资源', 'Choose a published resource')}</option>{resources.map(row => <option key={row.code} value={row.code}>{row.code}</option>)}
          {draft.resourceCode && !resources.some(row => row.code === draft.resourceCode) && <option value={draft.resourceCode}>{text('unavailableResource', '当前绑定资源已不可用', 'Current bound resource is unavailable')}</option>}</select>} />
        {sourceLoading && <p role="status">{text('loadingSource', '正在读取可用实现…', 'Loading available implementations…')}</p>}
        {draft.resourceCode && !sourceLoading && !sources.length && !error && <p>{text('noSource', '此资源暂无可用实现。状态命令需要修改操作和对应字段，只读 Handler 需要列表操作。', 'No supported implementation for this resource. State commands need UPDATE and their declared fields; read-only handlers need LIST.')}</p>}
        <PublicationField name={text('implementation', '已注册实现', 'Registered implementation')} child={<select className={CONTROL} required disabled={immutable || sourceLoading || !draft.resourceCode} value={draft.sourceCode ? `${draft.sourceKind}:${draft.sourceCode}` : ''} onChange={event => {
          const source = sources.find(item => `${item.sourceKind}:${item.sourceCode}` === event.target.value); if (!source?.available) return;
          change({ ...draft, sourceKind: source.sourceKind, sourceCode: source.sourceCode, sourceFingerprint: source.fingerprint, permission: source.permission,
            parameters: source.parameters, inputFields: source.inputFields, effectFields: source.effectFields, outputFields: source.outputFields, timeoutMs: Math.max(draft.timeoutMs, source.minTimeoutMs) });
        }}><option value="">{text('chooseSource', '选择业务命令或只读 Handler', 'Choose a command or read-only handler')}</option>{sources.map(source => <option disabled={!source.available} key={`${source.sourceKind}:${source.sourceCode}`} value={`${source.sourceKind}:${source.sourceCode}`}>{source.name} · {source.available ? source.sourceKind === 'command' ? text('command', '状态变更命令', 'State-transition command') : text('handler', '只读 Handler', 'Read-only handler') : text('unavailable', '不满足公开条件', 'Not eligible for publication')}</option>)}</select>} />
        {draft.sourceCode && !sourceLoading && !current && <p role="alert" className="text-danger">{text('sourceChanged', '来源已变化或不可用，不能保存或发布。已发布合同请使用新地址重新发布；仍可停用旧接口。', 'Source changed or is unavailable. Saving and publishing are blocked. Use a new address for a published contract; the old API can still be withdrawn.')}</p>}
        <PublicationField name={text('scope', '接口调用 Scope', 'Execution scope')} child={<input className={CONTROL} required disabled={immutable} pattern="[a-z][a-z0-9.:-]{1,159}" value={draft.scopes.execute} placeholder="office.devices.assign.execute" onChange={event => change({ ...draft, scopes: { execute: event.target.value } })} />} />
        <div className="grid gap-4 md:grid-cols-3"><PublicationField name={text('version', '合同版本', 'Schema version')} child={<input className={CONTROL} type="number" required min={(view?.activeVersion ?? 0) + 1} step={1} value={draft.schemaVersion} onChange={event => change({ ...draft, schemaVersion: event.target.valueAsNumber })} />} />
          <PublicationField name={text('timeout', '执行超时（毫秒）', 'Execution timeout (ms)')} help={text('timeoutHelp', '只读实现的预算须覆盖绑定资源的已发布查询预算；最小值以当前来源为准。', 'A read-only implementation budget must include its published resource query budget; the current source defines the minimum.')} child={<input className={CONTROL} required type="number" min={minTimeoutMs} max={30000} step={1} value={draft.timeoutMs} onChange={event => change({ ...draft, timeoutMs: event.target.valueAsNumber })} />} />
          <PublicationField name={text('concurrency', '同时执行上限', 'Maximum concurrent executions')} child={<input className={CONTROL} required type="number" min={1} max={32} step={1} value={draft.maxConcurrent} onChange={event => change({ ...draft, maxConcurrent: event.target.valueAsNumber })} />} /></div>
        {!!draft.sourceCode && <section className="space-y-3 rounded-control border border-border p-3"><h3 className="font-medium">{text('signature', '已固定的输入与变更', 'Pinned inputs and effects')}</h3>
          <ul className="space-y-1 text-sm">{Object.entries(draft.parameters).map(([alias, parameter]) => <li key={alias}>{alias} · {parameter.type} · {parameter.required ? text('required', '必填', 'Required') : text('optional', '可选', 'Optional')}</li>)}</ul>
          {draft.sourceKind === 'command' ? <><p className="text-sm">{text('effects', '受控变更字段', 'Controlled effect fields')}: {draft.effectFields.join(', ')}</p><p className="text-sm text-text-secondary">{text('preconditions', '必须提供目标资源的 If-Match 与幂等键。状态字段变更须在安装中显式授权，不会开放通用字段修改。', 'A target resource If-Match and idempotency key are required. Controlled state effects require an explicit installation grant and do not enable generic field writes.')}</p></>
            : <p className="text-sm">{text('outputs', '只读输出', 'Read-only output')}: {Object.keys(draft.outputFields).join(', ')}</p>}</section>}
      </fieldset>
      <div className="sticky bottom-0 flex flex-wrap gap-2 border-t border-border bg-panel py-3">
        <button className={PRIMARY} type="submit" disabled={busy || denied || sourceLoading || !valid || !dirty}>{text('save', '保存并校验业务草稿', 'Save and validate business draft')}</button>
        <button className={BUTTON} type="button" disabled={busy || denied || dirty || !view || !current || draft.schemaVersion <= (view.activeVersion ?? 0)} onClick={() => setConfirmation('publish')}>{text('publish', '发布业务接口', 'Publish business API')}</button>
        <button className={BUTTON} type="button" disabled={busy || denied || dirty || view?.status !== 'published'} onClick={() => setConfirmation('withdraw')}>{text('withdraw', '停用业务接口', 'Withdraw business API')}</button>
        <button className={BUTTON} type="button" disabled={busy || denied || dirty || view?.status !== 'published'} onClick={() => void download('openapi')}>{text('openapi', '下载业务 OpenAPI', 'Download business OpenAPI')}</button>
        <button className={BUTTON} type="button" disabled={busy || denied || dirty || view?.status !== 'published'} onClick={() => void download('types')}>{text('types', '下载业务类型声明', 'Download business types')}</button>
      </div>
    </form>}
    {confirmation && <div ref={confirmationRef} tabIndex={-1} onKeyDown={onConfirmationKey} role="group" aria-label={text('confirmation', '业务接口操作确认', 'Business API operation confirmation')} className="space-y-3 rounded-card border border-border bg-panel p-4">
      <p>{confirmation === 'discard' ? text('discardHelp', '放弃未保存的修改并关闭？', 'Discard unsaved edits and close?') : confirmation === 'withdraw'
        ? text('withdrawHelp', '停用会立即关闭此接口的后续外部调用和合同下载。确认停用？', 'Withdrawal immediately closes subsequent external calls and contract downloads. Withdraw?')
        : text('publishHelp', '发布会固定实现、输入、受控字段与执行预算。安装还需分别授予资源和业务接口权限。确认发布？', 'Publication pins the implementation, inputs, effects and budgets. Installations still need both resource and business API grants. Publish?')}</p>
      <div className="flex flex-wrap gap-2"><button className={confirmation === 'withdraw' ? BUTTON.replace('text-text', 'text-danger') : PRIMARY} type="button" disabled={busy} onClick={() => {
        if (confirmation === 'discard') { setConfirmation(null); setDirty(false); setEditing(false); void load(); } else void mutate(confirmation);
      }}>{text('confirm', '确认', 'Confirm')}</button><button className={BUTTON} type="button" disabled={busy} onClick={() => setConfirmation(null)}>{text('cancel', '取消', 'Cancel')}</button></div>
    </div>}
  </div>;
}
export default OpenApiActionPublicationEditor;
