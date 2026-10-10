import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import { publicationRequest, PublicationApiError, publicValueType, queryOutput, readOnlyQueryOutput, queryParameterItem,
  type PublicQuery, type QueryPublicationView, type QuerySource, type QuerySourceChoice, type PublicField, type PublicType } from '~/services/open-api-publications';
import { PublicationField, type LabeledControl } from './PublicationControls';
import { PublicValueSchemaEditor } from './PublicValueSchemaEditor';

const CONTROL = 'w-full rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text focus:outline-none focus:ring-2 focus:ring-accent-weak disabled:opacity-50';
const BUTTON = 'rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text hover:bg-hover focus:outline-none focus:ring-2 focus:ring-accent-weak disabled:opacity-50';
const PRIMARY = BUTTON.replace('bg-panel', 'bg-accent').replace('hover:bg-hover', 'hover:bg-accent-hover').replace('text-text', 'text-white');
const TYPES: PublicType[] = ['string', 'pid', 'integer', 'decimal', 'boolean', 'date', 'datetime', 'array'];
const initial = (): PublicQuery => ({ code: '', queryCode: '', schemaVersion: 1, sourceFingerprint: '', scopes: { execute: '' },
  sourceResources: {}, parameters: {}, fields: {}, maxPageSize: 20, maxRows: 100, timeoutMs: 5000, maxConcurrent: 2 });

/** Publishes approved query semantics through the existing administration DSL host. */
export function OpenApiQueryPublicationEditor() {
  const { t, locale } = useI18n();
  const label = (key: string, cn: string, en: string) => t(`openPlatform.queryPublications.${key}`, undefined, locale.startsWith('zh') ? cn : en);
  const [views, setViews] = useState<QueryPublicationView[]>([]);
  const [choices, setChoices] = useState<QuerySourceChoice[]>([]);
  const [search, setSearch] = useState(''); const [catalogPage, setCatalogPage] = useState(1); const [total, setTotal] = useState(0);
  const [view, setView] = useState<QueryPublicationView>(); const [draft, setDraft] = useState<PublicQuery>(initial);
  const [source, setSource] = useState<QuerySource>(); const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true); const [sourceLoading, setSourceLoading] = useState(false);
  const [dirty, setDirty] = useState(false); const [busy, setBusy] = useState(false); const [denied, setDenied] = useState(false);
  const [error, setError] = useState(''); const [notice, setNotice] = useState('');
  const [confirm, setConfirm] = useState<'publish' | 'withdraw' | 'discard' | null>(null);
  const sourceRequest = useRef(0); const catalogRequest = useRef(0); const confirmationRef = useRef<HTMLDivElement>(null);
  const fail = (cause: unknown) => {
    const forbidden = cause instanceof PublicationApiError && cause.status === 403;
    if (forbidden) setDenied(true);
    setError(cause instanceof PublicationApiError && cause.status === 409
      ? label('conflict', '草稿已被其他管理员更新。你的修改已保留，请放弃本地修改后重新打开。', 'Another administrator updated the draft. Your edits are retained; discard them and reopen.')
      : forbidden ? label('denied', '当前账号没有开放平台管理权限。', 'Your account does not have Open Platform administrator permission.')
        : cause instanceof Error ? cause.message : label('failed', '请求失败，请重试。', 'Request failed. Please retry.'));
  };
  const load = async () => { setLoading(true); setError(''); setDenied(false);
    try { setViews(await publicationRequest<QueryPublicationView[]>('/api/open-platform/query-publications')); }
    catch (cause) { fail(cause); } finally { setLoading(false); }
  };
  const loadChoices = async (page = 1) => { const id = ++catalogRequest.current;
    try { const data = await publicationRequest<{ records: QuerySourceChoice[]; total: number }>(`/api/open-platform/query-sources?page=${page}&keyword=${encodeURIComponent(search)}`);
      if (id === catalogRequest.current) { setChoices(data.records); setTotal(data.total); setCatalogPage(page); }
    } catch (cause) { if (id === catalogRequest.current) fail(cause); }
  };
  useEffect(() => { void load(); void loadChoices(); return () => { sourceRequest.current++; catalogRequest.current++; }; }, []);
  useEffect(() => { const listener = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', listener); return () => window.removeEventListener('beforeunload', listener);
  }, [dirty]);
  useEffect(() => { if (confirm) { confirmationRef.current?.scrollIntoView({ block: 'nearest' }); confirmationRef.current?.querySelector<HTMLButtonElement>('button')?.focus(); } }, [confirm]);
  const change = (next: PublicQuery) => { setDraft(next); setDirty(true); setError(''); setNotice(''); };
  const chooseSource = async (code: string, reset = true) => { const id = ++sourceRequest.current; setSource(undefined); setSourceLoading(true); setError('');
    try { const next = await publicationRequest<QuerySource>(`/api/open-platform/query-sources/${encodeURIComponent(code)}`);
      if (id !== sourceRequest.current) return;
      setSource(next);
      if (reset) {
        const parameters = Object.fromEntries(next.parameters.map(name => [name, { type: name === next.rootPidParameter ? 'pid' as const : 'string' as const,
          required: true, nullable: false, items: null }]));
        change({ ...draft, queryCode: code, sourceFingerprint: next.fingerprint, fields: {}, parameters,
          sourceResources: Object.fromEntries(next.sources.map(model => [model.modelCode, ''])),
          maxRows: Math.min(100, next.maxRows), maxPageSize: Math.min(20, next.maxRows), timeoutMs: Math.min(5000, next.timeoutMs) });
      }
    } catch (cause) { if (id === sourceRequest.current) fail(cause); }
    finally { if (id === sourceRequest.current) setSourceLoading(false); }
  };
  const select = (next?: QueryPublicationView) => { sourceRequest.current++; setSource(undefined); setView(next); setDraft(next?.draft ?? initial());
    setDirty(false); setError(''); setNotice(''); setEditing(true); if (next) void chooseSource(next.draft.queryCode, false);
  };
  const mutate = async (action: 'save' | 'publish' | 'withdraw') => { setBusy(true); setError(''); setNotice('');
    try { const code = action === 'save' ? draft.code : view!.code;
      const next = await publicationRequest<QueryPublicationView>(`/api/open-platform/query-publications/${encodeURIComponent(code)}/${action === 'save' ? 'draft' : action}`,
        { method: action === 'save' ? 'PUT' : 'POST', body: JSON.stringify(action === 'save' ? { expectedRevision: view?.draftRevision ?? 0, definition: draft } : { expectedRevision: view!.draftRevision }) });
      setView(next); setDraft(next.draft); setDirty(false); setConfirm(null);
      setViews(current => [...current.filter(row => row.code !== next.code), next].sort((a, b) => a.code.localeCompare(b.code)));
      setNotice(action === 'save' ? label('saved', '查询草稿已保存并通过来源校验。', 'Query draft saved and source validation passed.')
        : action === 'publish' ? label('published', '已发布查询。安装仍需获得查询及所有来源资源的授权。', 'Query published. Installations still need query and every source resource grant.')
          : label('withdrawn', '查询已停用，外部执行与合同下载已关闭。', 'Query withdrawn. External execution and contract downloads are closed.'));
    } catch (cause) { fail(cause); } finally { setBusy(false); }
  };
  const download = async (kind: 'openapi' | 'types') => { if (!view) return; setBusy(true); setError('');
    try { const response = await fetch(`/api/open-platform/query-publications/${encodeURIComponent(view.code)}/${kind}`);
      if (!response.ok) { const body = await response.json(); throw new PublicationApiError(response.status, body.message); }
      const content = await response.text();
      if (!content.trim() || kind === 'openapi' && (JSON.parse(content).openapi !== '3.1.0' || JSON.parse(content)['x-schema-version'] !== view.activeVersion)
          || kind === 'types' && (!content.includes('export type QueryParameters') || !content.includes('export type QueryItem'))) throw new Error(label('invalidDownload', '查询合同文件无效，请重试。', 'Invalid query contract file. Please retry.'));
      const url = URL.createObjectURL(new Blob([content], { type: kind === 'openapi' ? 'application/json' : 'text/plain;charset=UTF-8' }));
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${view.code}.${kind === 'openapi' ? 'openapi.json' : 'd.ts'}`;
      document.body.append(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (cause) { fail(cause); } finally { setBusy(false); }
  };
  const input = (name: string, child: LabeledControl, help?: string) => <PublicationField name={name} child={child} help={help} />;
  const section = (title: string, children: ReactNode) => <section className="space-y-4 rounded-card border border-border bg-panel p-5"><h2 className="text-base font-semibold text-text">{title}</h2>{children}</section>;
  const sourceCurrent = source && source.fingerprint === draft.sourceFingerprint;
  const fieldChange = (alias: string, field: PublicField) => change({ ...draft, fields: { ...draft.fields, [alias]: readOnlyQueryOutput(field) } });
  const feedback = <>{error && <div role="alert" className="rounded-control border border-danger bg-danger-weak p-3 text-sm text-danger">{error}</div>}{notice && <p role="status" className="text-sm text-success">{notice}</p>}</>;
  return <div data-testid="query-publication-editor" className="space-y-5">
    <header className="flex flex-wrap items-start justify-between gap-4"><p className="text-sm text-text-2">{label('intro', '发布已批准的命名查询，固定来源、参数、输出白名单与执行预算。', 'Publish approved NamedQueries with pinned sources, parameters, output whitelists and execution budgets.')}</p>
      {dirty ? <button className={BUTTON} disabled>{label('resources', '公共资源发布', 'Public resource publications')}</button> : <Link className={BUTTON} to="/p/c/open_platform_publications">{label('resources', '公共资源发布', 'Public resource publications')}</Link>}</header>
    {(!editing || denied) && feedback}
    {loading ? <p role="status">{label('loading', '正在加载查询发布…', 'Loading query publications…')}</p>
      : denied ? <button type="button" className={BUTTON} onClick={() => void load()}>{label('reload', '重新加载', 'Reload')}</button>
        : <div className="space-y-4"><div className="flex flex-wrap justify-between gap-3"><h2 className="text-base font-semibold text-text">{label('catalog', '查询发布目录', 'Query publication catalog')}</h2>
          <div className="flex gap-2"><button type="button" className={BUTTON} disabled={busy || dirty} onClick={() => { setEditing(false); void load(); }}>{label('refresh', '刷新目录', 'Reload catalog')}</button>
            <button type="button" className={BUTTON} disabled={busy || dirty} onClick={() => select()}>{label('new', '新建公共查询', 'New public query')}</button></div></div>
          {views.length === 0 ? <p className="rounded-card border border-border bg-subtle p-5 text-sm text-text-2">{label('empty', '尚未公开命名查询。保存草稿不会开放外部执行。', 'No NamedQueries are public yet. Saving a draft does not enable external execution.')}</p>
            : <ul className="divide-y divide-border rounded-card border border-border bg-panel">{views.map(row => <li key={row.code} className="flex items-center justify-between gap-3 p-4"><div><p className="font-medium text-text">{row.code}</p>
              <p className="text-sm text-text-2">{row.status === 'published' ? label('live', '已发布', 'Published') : row.status === 'withdrawn' ? label('disabled', '已停用', 'Withdrawn') : label('draft', '草稿', 'Draft')} · {label('version', '当前发布版本', 'Active version')} {row.activeVersion ?? '—'} · {label('revision', '草稿修订', 'Draft revision')} {row.draftRevision}</p></div>
              <button type="button" className={BUTTON} disabled={busy || dirty} onClick={() => select(row)}>{label('edit', '编辑查询合同', 'Edit query contract')}</button></li>)}</ul>}</div>}
    {editing && !denied && <form className="space-y-4" onSubmit={event => { event.preventDefault(); void mutate('save'); }}><fieldset disabled={busy || sourceLoading} className="space-y-4">
      {section(label('identity', '来源与公共查询地址', 'Source and public query address'), <div className="grid gap-4 md:grid-cols-2">
        {input(label('code', '公共查询地址', 'Public query address'), <input required pattern="[a-z][a-z0-9.\-]{1,93}" className={CONTROL} value={draft.code} disabled={!!view} placeholder="sales.order-totals" onChange={event => change({ ...draft, code: event.target.value })} />,
          label('codeHelp', '用于第三方查询地址；来源SQL不会出现在公共合同中。', 'Used in external query paths. Source SQL is omitted from public contracts.'))}
        {input(label('schemaVersion', '合同版本', 'Schema version'), <input required type="number" min={1} step={1} className={CONTROL} value={draft.schemaVersion} onChange={event => change({ ...draft, schemaVersion: Number(event.target.value) })} />)}
        <div className="space-y-3 md:col-span-2"><div className="flex items-end gap-2">{input(label('searchSource', '查找命名查询', 'Find NamedQuery'), <input className={CONTROL} value={search} onChange={event => setSearch(event.target.value)} />)}
          <button type="button" className={BUTTON} onClick={() => void loadChoices()}>{label('search', '查找', 'Search')}</button></div>
          {input(label('source', '命名查询来源', 'NamedQuery source'), <select required className={CONTROL} value={draft.queryCode} disabled={!!view?.activeVersion} onChange={event => void chooseSource(event.target.value)}>
            <option value="">{label('choose', '选择已发布的SQL读取查询', 'Choose a published SQL read query')}</option>
            {source && !choices.some(choice => choice.code === source.queryCode) && <option value={source.queryCode}>{source.name}</option>}
            {choices.map(choice => <option key={choice.code} value={choice.code} disabled={!choice.available}>{choice.name || label('unnamed', '未命名查询', 'Unnamed query')}{!choice.available && ` (${label('unavailable', '不支持公开执行', 'Not eligible')})`}</option>)}</select>)}
          <div className="flex gap-2"><button type="button" className={BUTTON} disabled={catalogPage <= 1} onClick={() => void loadChoices(catalogPage - 1)}>{label('previous', '上一页', 'Previous')}</button>
            <button type="button" className={BUTTON} disabled={catalogPage * 50 >= total} onClick={() => void loadChoices(catalogPage + 1)}>{label('next', '下一页', 'Next')}</button></div></div>
        {input(label('scope', '查询执行权限', 'Query execution scope'), <input required pattern="[a-z][a-z0-9.:\-]{1,159}" className={CONTROL} value={draft.scopes.execute} placeholder="sales.orders:query" onChange={event => change({ ...draft, scopes: { execute: event.target.value } })} />)}
      </div>)}
      {sourceLoading && <p role="status">{label('sourceLoading', '正在读取查询来源…', 'Loading query source…')}</p>}
      {source && !sourceCurrent && <p role="alert" className="text-danger">{label('drift', '查询来源已变更，当前合同不能覆盖新来源。请建立新的公共查询合同。', 'Query semantics changed. Create a new public query contract for the new source.')}</p>}
      {source && section(label('bindings', '每个数据来源的公共资源绑定', 'Public resource bindings for every source'), <div className="space-y-3"><p className="text-sm text-text-2">{label('bindingsHelp', '每个来源必须绑定当前已发布且允许列表读取的公共资源。安装仍需取得所有来源的行与字段授权。', 'Every source needs a published LIST resource. Installations still need row and field grants for every source.')}</p>
        {source.sources.map(model => <div key={model.modelCode} data-testid={`query-source-${model.modelCode}`}>{input(model.name || label('unnamedModel', '未命名业务模型', 'Unnamed business model'), <select required className={CONTROL} value={draft.sourceResources[model.modelCode] ?? ''} onChange={event => change({ ...draft, sourceResources: { ...draft.sourceResources, [model.modelCode]: event.target.value } })}>
          <option value="">{label('chooseResource', '选择公共资源', 'Choose public resource')}</option>{model.resources.map(resource => <option key={resource.code} value={resource.code}>{resource.code} · v{resource.schemaVersion}</option>)}</select>,
          model.resources.length ? undefined : label('missingSource', '此来源还没有可用公共资源，请先发布资源并启用列表读取。', 'Publish a LIST resource for this source first.'))}</div>)}</div>)}
      {source && section(label('parameters', '公开输入参数', 'Public input parameters'), <div className="space-y-3">{source.parameters.length === 0 && <p className="text-sm text-text-2">{label('noParameters', '此查询不需要外部输入参数。', 'This query has no external parameters.')}</p>}
        {source.parameters.map(name => { const parameter = draft.parameters[name]; if (!parameter) return null; const root = name === source.rootPidParameter;
          return <div key={name} data-testid={`query-parameter-${name}`} className="space-y-3 rounded-control border border-border p-3"><h3 className="font-medium text-text">{label('parameter', '参数', 'Parameter')} {name}</h3>
            {input(label('parameterType', '参数类型', 'Parameter type'), <select className={CONTROL} value={parameter.type} disabled={root} onChange={event => { const type = event.target.value as PublicType; change({ ...draft, parameters: { ...draft.parameters, [name]: { ...parameter, type, items: type === 'array' ? queryParameterItem('string') : null } } }); }}>
              {TYPES.map(type => <option key={type} value={type}>{type}</option>)}</select>)}
            <div className="flex gap-4"><label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={parameter.required} disabled={root} onChange={event => change({ ...draft, parameters: { ...draft.parameters, [name]: { ...parameter, required: event.target.checked } } })} />{label('required', '必填参数', 'Required parameter')}</label>
              <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={parameter.nullable} disabled={root} onChange={event => change({ ...draft, parameters: { ...draft.parameters, [name]: { ...parameter, nullable: event.target.checked } } })} />{label('nullable', '接受空值', 'Nullable')}</label></div>
            {parameter.type === 'array' && parameter.items && input(label('itemType', '数组元素类型', 'Array item type'), <select className={CONTROL} value={parameter.items.type} onChange={event => change({ ...draft, parameters: { ...draft.parameters, [name]: { ...parameter, items: queryParameterItem(event.target.value as PublicType) } } })}>
              {TYPES.filter(type => type !== 'array').map(type => <option key={type} value={type}>{type}</option>)}</select>)}
          </div>; })}</div>)}
      {source && section(label('outputs', '只读公共输出白名单', 'Read-only public output whitelist'), <div className="space-y-3"><p className="text-sm text-text-2">{label('outputHelp', '只返回选择的输出，公共查询不能写入业务数据。', 'Only selected outputs are returned. Public queries cannot write business data.')}</p>
        {source.fields.map((meta, index) => { const entry = Object.entries(draft.fields).find(([, field]) => field.source === meta.code); const alias = entry?.[0]; const field = entry?.[1];
          return <div key={meta.code} data-testid={`query-output-${meta.code}`} className="space-y-3 rounded-control border border-border p-3"><label className="flex items-center gap-2 font-medium text-text"><input type="checkbox" checked={!!field} onChange={event => {
            const fields = { ...draft.fields }; if (event.target.checked) { let number = index + 1; while (Object.hasOwn(fields, `field${number}`)) number++;
              const type = meta.types.includes('string') ? 'string' : meta.types[0]; fields[`field${number}`] = readOnlyQueryOutput(publicValueType(queryOutput(meta.code, type), type));
            } else if (alias) delete fields[alias]; change({ ...draft, fields }); }} />{meta.name || meta.code}</label>
            {field && alias && <><div className="grid gap-3 md:grid-cols-2">{input(label('alias', '对外输出名称', 'Public output name'), <input required pattern="[a-zA-Z][a-zA-Z0-9]{0,99}" className={CONTROL} value={alias} onChange={event => {
              if (Object.hasOwn(draft.fields, event.target.value)) return; change({ ...draft, fields: Object.fromEntries(Object.entries(draft.fields).map(([key, value]) => [key === alias ? event.target.value : key, value])) }); }} />)}
              {input(label('outputType', '输出类型', 'Output type'), <select className={CONTROL} value={field.type} onChange={event => fieldChange(alias, publicValueType(field, event.target.value as PublicType))}>{meta.types.map(type => <option key={type} value={type}>{type}</option>)}</select>)}</div>
              <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={field.nullable} onChange={event => fieldChange(alias, { ...field, nullable: event.target.checked })} />{label('nullable', '接受空值', 'Nullable')}</label>
              <PublicValueSchemaEditor field={field} readOnlyOutput onChange={value => fieldChange(alias, value)} /></>}
          </div>; })}</div>)}
      {source && section(label('budgets', '执行预算', 'Execution budgets'), <div className="grid gap-4 md:grid-cols-2">
        {input(label('pageSize', '单页上限', 'Maximum page size'), <input className={CONTROL} required type="number" min={1} max={Math.min(100, draft.maxRows)} step={1} value={draft.maxPageSize} onChange={event => change({ ...draft, maxPageSize: Number(event.target.value) })} />)}
        {input(label('maxRows', '结果总量上限', 'Maximum result rows'), <input className={CONTROL} required type="number" min={draft.maxPageSize} max={Math.min(10000, source.maxRows)} step={1} value={draft.maxRows} onChange={event => change({ ...draft, maxRows: Number(event.target.value) })} />)}
        {input(label('timeout', '查询超时（毫秒）', 'Query timeout (ms)'), <input className={CONTROL} required type="number" min={1} max={Math.min(30000, source.timeoutMs)} step={1} value={draft.timeoutMs} onChange={event => change({ ...draft, timeoutMs: Number(event.target.value) })} />)}
        {input(label('concurrent', '同时执行上限', 'Maximum concurrent executions'), <input className={CONTROL} required type="number" min={1} max={32} step={1} value={draft.maxConcurrent} onChange={event => change({ ...draft, maxConcurrent: Number(event.target.value) })} />)}
      </div>)}
      <div className="sticky bottom-0 space-y-3 border-t border-border bg-panel py-3">{feedback}<div className="flex flex-wrap gap-2">
        <button type="submit" className={PRIMARY} disabled={!sourceCurrent || !dirty || !Object.keys(draft.fields).length}>{busy ? label('working', '正在处理…', 'Working…') : label('save', '保存并校验查询草稿', 'Save and validate query draft')}</button>
        <button type="button" className={BUTTON} disabled={!view || dirty || !sourceCurrent || view.draft.schemaVersion <= (view.activeVersion ?? 0)} onClick={() => setConfirm('publish')}>{label('publish', '发布查询', 'Publish query')}</button>
        <button type="button" className={BUTTON} disabled={view?.status !== 'published'} onClick={() => void download('openapi')}>{label('openapi', '下载查询 OpenAPI', 'Download query OpenAPI')}</button>
        <button type="button" className={BUTTON} disabled={view?.status !== 'published'} onClick={() => void download('types')}>{label('types', '下载查询类型声明', 'Download query types')}</button>
        <button type="button" className={BUTTON.replace('text-text', 'text-danger')} disabled={view?.status !== 'published' || dirty} onClick={() => setConfirm('withdraw')}>{label('withdraw', '停用查询', 'Withdraw query')}</button>
        <button type="button" className={BUTTON} onClick={() => dirty ? setConfirm('discard') : setEditing(false)}>{label('close', '关闭编辑', 'Close editor')}</button></div></div>
    </fieldset>
    {confirm && <div ref={confirmationRef} role="group" aria-label={label('confirmation', '查询操作确认', 'Confirm query operation')} className="space-y-3 rounded-control border border-border-strong bg-subtle p-4">
      <p className="text-sm text-text">{confirm === 'discard' ? label('discardConfirm', '放弃未保存的修改并重新读取查询目录？', 'Discard unsaved edits and reload the query catalog?')
        : confirm === 'withdraw' ? label('withdrawConfirm', '停用会立即关闭此公共查询的外部执行和合同下载。确认停用？', 'Withdrawal immediately closes external execution and contract downloads. Withdraw?')
          : label('publishConfirm', '发布将校验来源与兼容性并固定不可变快照。确认发布查询？', 'Publishing validates source semantics and compatibility and pins an immutable snapshot. Publish?')}</p>
      <div className="flex gap-2"><button type="button" className={BUTTON} disabled={busy} onClick={() => { if (confirm === 'discard') { setDirty(false); setEditing(false); setConfirm(null); void load(); } else void mutate(confirm); }}>{label('confirm', '确认', 'Confirm')}</button>
        <button type="button" className={BUTTON} disabled={busy} onClick={() => setConfirm(null)}>{label('cancel', '取消', 'Cancel')}</button></div></div>}
    </form>}
  </div>;
}
export default OpenApiQueryPublicationEditor;
