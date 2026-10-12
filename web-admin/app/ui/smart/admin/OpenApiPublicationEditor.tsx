import { useEffect, useRef, useState, type ReactNode } from 'react';
import { PublicationField, type LabeledControl } from './PublicationControls';
import { Link } from 'react-router';
import { useI18n } from '~/contexts/I18nContext';
import { publicationRequest, PublicationApiError, identityField, sourceField, publicValueType, type PublicationView,
  type PublicationSource, type ModelChoice, type PublicResource, type PublicField, type PublicOperation } from '~/services/open-api-publications';
import { PublicValueSchemaEditor } from './PublicValueSchemaEditor';

const CONTROL = 'w-full rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text focus:outline-none focus:ring-2 focus:ring-accent-weak disabled:opacity-50';
const BUTTON = 'rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text hover:bg-hover focus:outline-none focus:ring-2 focus:ring-accent-weak disabled:opacity-50';
const OPERATIONS: PublicOperation[] = ['list', 'get', 'create', 'update', 'delete'];
const initial = (): PublicResource => ({ code: '', modelCode: '', schemaVersion: 1, operations: ['list'], scopes: { list: '' },
  dataPolicy: 'installation_filter', fields: { pid: identityField() }, maxPageSize: 50, maxConditions: 8, timeoutMs: 5000, events: [], sourceResources: {} });

/** Public contract editing is a typed custom block inside the administration DSL page. */
export function OpenApiPublicationEditor() {
  const { t, locale } = useI18n();
  const label = (key: string, cn: string, en: string) => t(`openPlatform.publications.${key}`, undefined, locale.startsWith('zh') ? cn : en);
  const opLabel = (op: PublicOperation) => ({ list: label('list', '列表查询', 'List'), get: label('get', '详情读取', 'Get'),
    create: label('create', '创建记录', 'Create'), update: label('update', '修改记录', 'Update'), delete: label('delete', '删除记录', 'Delete') })[op];
  const [views, setViews] = useState<PublicationView[]>([]);
  const [models, setModels] = useState<ModelChoice[]>([]);
  const [modelPage, setModelPage] = useState(1);
  const [modelTotal, setModelTotal] = useState(0);
  const [search, setSearch] = useState('');
  const [source, setSource] = useState<PublicationSource>();
  const [view, setView] = useState<PublicationView>();
  const [draft, setDraft] = useState<PublicResource>(initial);
  const [editing, setEditing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [catalogReady, setCatalogReady] = useState(false);
  const [sourceLoading, setSourceLoading] = useState(false);
  const [sourceValidationAttempts, setSourceValidationAttempts] = useState(0);
  const sourceForm = useRef<HTMLFormElement>(null);
  const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [denied, setDenied] = useState(false);
  const [confirm, setConfirm] = useState<'publish' | 'withdraw' | 'discard' | null>(null);
  const confirmationRef = useRef<HTMLDivElement>(null);
  useEffect(() => { if (confirm) { confirmationRef.current?.scrollIntoView({ block: 'nearest' }); confirmationRef.current?.querySelector<HTMLButtonElement>('button')?.focus(); } }, [confirm]);
  useEffect(() => {
    if (sourceValidationAttempts > 0) sourceForm.current?.querySelector<HTMLElement>('input:invalid, select:invalid, textarea:invalid')?.focus();
  }, [sourceValidationAttempts]);
  const requestId = useRef(0);
  const modelRequestId = useRef(0);
  const errorText = (cause: unknown) => cause instanceof PublicationApiError && cause.status === 409
    ? label('conflict', '草稿已被其他管理员更新。你的修改已保留，请放弃本地修改后重新选择。', 'Another administrator updated this draft. Your edits are retained; discard them and reopen the resource.')
    : cause instanceof PublicationApiError && cause.status === 403
      ? label('denied', '当前账号没有开放平台管理权限。', 'Your account does not have Open Platform administrator permission.')
      : cause instanceof PublicationApiError && cause.status === 400 && cause.message.startsWith('Existing public field contract cannot change: ')
        ? label('incompatibleField', '已发布的资源字段不能修改。请使用新的公共资源地址发布新合同，原合同将保持有效。',
          'Published resource fields cannot change. Publish a new contract at a new public resource address; the original contract stays active.')
      : cause instanceof Error ? cause.message : label('failed', '请求失败，请重试。', 'Request failed. Please retry.');
  const fail = (cause: unknown) => { setError(errorText(cause)); if (cause instanceof PublicationApiError && cause.status === 403) setDenied(true); };
  const load = async () => {
    setLoading(true); setCatalogReady(false); setError(''); setDenied(false);
    try { setViews(await publicationRequest<PublicationView[]>('/api/open-platform/publications')); setCatalogReady(true); }
    catch (cause) { fail(cause); }
    finally { setLoading(false); }
  };
  const loadModels = async (page = 1) => {
    const id = ++modelRequestId.current;
    try {
      const data = await publicationRequest<{ records: ModelChoice[]; total: number }>(`/api/meta/models?currentOnly=true&page=${page}&size=50&keyword=${encodeURIComponent(search)}`);
      if (id === modelRequestId.current) { setModels(data.records); setModelTotal(data.total); setModelPage(page); }
    } catch (cause) { if (id === modelRequestId.current) fail(cause); }
  };
  useEffect(() => { void load(); void loadModels(); return () => { requestId.current++; modelRequestId.current++; }; }, []);
  useEffect(() => {
    const listener = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', listener);
    return () => window.removeEventListener('beforeunload', listener);
  }, [dirty]);
  const change = (next: PublicResource) => { setDraft(next); setDirty(true); setNotice(''); setError(''); };
  const chooseSource = async (code: string, reset = true) => {
    const id = ++requestId.current; setSourceLoading(true); setSource(undefined); setError(''); setSourceValidationAttempts(0);
    try {
      const next = await publicationRequest<PublicationSource>(`/api/open-platform/publication-sources/${encodeURIComponent(code)}`);
      if (id !== requestId.current) return;
      setSource(next);
      if (reset) change({ ...draft, modelCode: code, fields: { pid: identityField() }, operations: [], scopes: {}, events: [],
        sourceResources: Object.fromEntries((next.sources ?? []).map(model => [model.modelCode, ''])) });
    } catch (cause) { if (id === requestId.current) fail(cause); }
    finally { if (id === requestId.current) setSourceLoading(false); }
  };
  const select = (next?: PublicationView) => {
    setView(next); setDraft(next?.draft ?? initial()); setDirty(false); setEditing(true); setError(''); setNotice('');
    setSource(undefined); setSourceValidationAttempts(0); requestId.current++;
    if (next) void chooseSource(next.draft.modelCode, false);
  };
  const mutate = async (operation: 'save' | 'publish' | 'withdraw') => {
    if (busy || denied) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const next = await publicationRequest<PublicationView>(`/api/open-platform/publications/${encodeURIComponent(draft.code)}/${operation === 'save' ? 'draft' : operation}`, {
        method: operation === 'save' ? 'PUT' : 'POST', body: JSON.stringify({ expectedRevision: view?.draftRevision ?? 0, ...(operation === 'save' ? { definition: draft } : {}) }),
      });
      setView(next); setDraft(next.draft); setDirty(false); setConfirm(null);
      setViews(previous => [...previous.filter(row => row.code !== next.code), next]);
      setNotice(operation === 'save' ? label('saved', '草稿已保存并通过来源校验。', 'Draft saved and source validation passed.')
        : operation === 'publish' ? label('published', '已发布。第三方仍需安装授权与对应权限。', 'Published. External clients still need installation grants and scopes.')
          : label('withdrawn', '已停用，外部调用与合同下载已关闭。', 'Withdrawn. External calls and contract downloads are closed.'));
    } catch (cause) { setConfirm(null); fail(cause); }
    finally { setBusy(false); }
  };
  const download = async (kind: 'openapi' | 'types') => {
    if (!view || busy) return;
    setBusy(true); setError('');
    try {
      const response = await fetch(`/api/open-platform/publications/${encodeURIComponent(view.code)}/${kind}`);
      if (!response.ok) { const body = await response.json(); throw new PublicationApiError(response.status, body.message); }
      const content = await response.text();
      if (!content || (kind === 'openapi' ? JSON.parse(content).openapi !== '3.1.0'
        : !response.headers.get('Content-Disposition')?.includes('.d.ts') || !content.includes('export type Resource'))) {
        throw new Error(label('invalidDownload', '合同文件内容无效，请重试。', 'The contract file is invalid. Please retry.'));
      }
      const url = URL.createObjectURL(new Blob([content], { type: kind === 'openapi' ? 'application/json' : 'text/plain;charset=UTF-8' }));
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${view.code}.${kind === 'openapi' ? 'openapi.json' : 'd.ts'}`;
      document.body.append(anchor); anchor.click(); anchor.remove(); setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (cause) { fail(cause); }
    finally { setBusy(false); }
  };
  const updateField = (alias: string, field: PublicField) => change({ ...draft, fields: { ...draft.fields, [alias]: field } });
  const section = (title: string, content: ReactNode) => <section className="space-y-4 rounded-card border border-border bg-panel p-5"><h2 className="text-base font-semibold text-text">{title}</h2>{content}</section>;
  const input = (name: string, child: LabeledControl, help?: string) => <PublicationField name={name} child={child} help={help} />;
  const status = (row: PublicationView) => row.status === 'published' ? label('live', '已发布', 'Published') : row.status === 'withdrawn' ? label('disabled', '已停用', 'Withdrawn') : label('draft', '草稿', 'Draft');
  return <div data-testid="publication-editor" className="space-y-5">
    <header className="flex flex-wrap items-start justify-between gap-4"><div><p className="text-sm text-text-2">{label('intro', '选择业务模型，明确对外字段和操作，再按安装授予最小权限。', 'Choose a business model, declare public fields and operations, then grant minimum installation permissions.')}</p></div>
      <div className="flex flex-wrap gap-2">{dirty ? <><button type="button" className={BUTTON} disabled>{label('queryPublications', '公共查询发布', 'Public query publications')}</button><button type="button" className={BUTTON} disabled>{label('installations', '应用、凭据与投递', 'Applications, credentials and deliveries')}</button></>
        : <><Link className={BUTTON} to="/p/c/open_platform_queries">{label('queryPublications', '公共查询发布', 'Public query publications')}</Link><Link className={BUTTON} to="/settings/api-docs">{label('installations', '应用、凭据与投递', 'Applications, credentials and deliveries')}</Link></>}</div></header>
    {error && (!editing || denied) && <div role="alert" className="rounded-control border border-danger bg-danger-weak p-3 text-sm text-danger">{error}</div>}
    {notice && (!editing || denied) && <p role="status" className="text-sm text-success">{notice}</p>}
    {loading ? <p role="status" className="animate-pulse rounded-card bg-subtle p-5 text-text-2">{label('loading', '正在加载发布配置…', 'Loading publications…')}</p>
      : denied ? <button type="button" className={BUTTON} onClick={() => void load()}>{label('retry', '重新加载', 'Reload')}</button>
        : <div className="space-y-4">
          <div className="flex items-center justify-between"><h2 className="text-base font-semibold text-text">{label('catalog', '发布目录', 'Publication catalog')}</h2>
            <div className="flex gap-2"><button type="button" className={BUTTON} disabled={busy || dirty} onClick={() => { setEditing(false); void load(); }}>{label('reload', '刷新目录', 'Reload catalog')}</button>
              <button type="button" className={BUTTON} disabled={busy || dirty} onClick={() => select()}>{label('new', '新建公共资源', 'New public resource')}</button></div></div>
          {!catalogReady ? null : views.length === 0 ? <div className="rounded-card border border-border bg-subtle p-5"><h3 className="font-medium text-text">{label('emptyTitle', '尚未公开业务模型', 'No business models are public yet')}</h3>
            <p className="mt-1 text-sm text-text-2">{label('emptyHint', '新建一个公共资源。保存草稿不会开放外部访问。', 'Create a public resource. Saving a draft does not enable external access.')}</p></div>
            : <ul className="divide-y divide-border rounded-card border border-border bg-panel">{views.map(row => <li key={row.code} className="flex items-center justify-between gap-3 p-4"><div><p className="font-medium text-text">{row.code}</p>
              <p className="text-sm text-text-2">{status(row)} · {label('activeVersion', '当前发布版本', 'Active version')} {row.activeVersion ?? '—'} · {label('draftRevision', '草稿修订', 'Draft revision')} {row.draftRevision}</p></div>
              <button className={BUTTON} type="button" disabled={busy || dirty} onClick={() => select(row)}>{label('edit', '编辑合同', 'Edit contract')}</button></li>)}</ul>}
        </div>}
    {editing && !denied && <form ref={sourceForm} className="space-y-4" onSubmit={event => { event.preventDefault(); void mutate('save'); }}>
      <fieldset disabled={busy || sourceLoading} className="space-y-4">
        {section(label('identity', '来源与公共地址', 'Source and public address'), <div className="grid gap-4 md:grid-cols-2">
          {input(label('code', '公共资源地址', 'Public resource address'), <input required pattern="[a-z][a-z0-9.\-]{1,99}" className={CONTROL} value={draft.code} disabled={!!view} placeholder="sales.orders" onChange={event => change({ ...draft, code: event.target.value })} />,
            label('codeHelp', '用于第三方 API 路径，如 sales.orders；发布后保持稳定。', 'Used in external API paths, for example sales.orders. Keep it stable after publication.'))}
          {input(label('version', '合同版本', 'Schema version'), <input required type="number" min={1} step={1} className={CONTROL} value={draft.schemaVersion} onChange={event => change({ ...draft, schemaVersion: Number(event.target.value) })} />,
            label('versionHelp', '每次发布新快照需大于当前版本；现有字段和权限不能破坏兼容。', 'Each new snapshot needs a greater version. Existing fields and scopes must remain compatible.'))}
          <div className="md:col-span-2 space-y-2"><div className="flex items-end gap-2">{input(label('modelSearch', '查找业务模型', 'Find business model'), <input className={CONTROL} value={search} placeholder={label('searchHint', '按业务名称查找', 'Search by business name')} onChange={event => setSearch(event.target.value)} />)}
            <button type="button" className={BUTTON} onClick={() => void loadModels()}>{label('search', '查找', 'Search')}</button></div>
            {input(label('source', '业务模型', 'Business model'), <select required className={CONTROL} value={draft.modelCode} disabled={!!view?.activeVersion} onChange={event => void chooseSource(event.target.value)}>
              <option value="">{label('chooseModel', '选择已发布的业务模型', 'Choose a published business model')}</option>
              {source && !models.some(model => model.code === source.modelCode) && <option value={source.modelCode}>{source.name}</option>}
              {models.filter(model => ['active', 'published'].includes((model.status ?? '').toLowerCase())).map(model => <option key={model.code} value={model.code}>{model.displayName || label('unnamed', '未命名业务模型', 'Unnamed business model')}</option>)}</select>,
              label('sourceHelp', '操作和字段取自正式模型定义；保存与发布时服务端重新校验。', 'Operations and fields come from the runtime model. The server validates them again on save and publish.'))}
            <div className="flex gap-2"><button type="button" className={BUTTON} disabled={modelPage <= 1} onClick={() => void loadModels(modelPage - 1)}>{label('previous', '上一页', 'Previous')}</button>
              <button type="button" className={BUTTON} disabled={modelPage * 50 >= modelTotal} onClick={() => void loadModels(modelPage + 1)}>{label('next', '下一页', 'Next')}</button></div></div>
        </div>)}
        {source && !!source.sources?.length && section(label('virtualSources', '虚拟模型来源授权', 'Virtual model source grants'), <div className="space-y-3">
          <p className="text-sm text-text-2">{label('virtualSourceHelp', '为每个来源选择已发布的列表资源；安装还需获得这些来源的列表和字段授权。虚拟模型自身的行约束会同时生效。', 'Select a published list resource for each source. Installations also need its list and field grants. The virtual model row constraints apply as well.')}</p>
          {source.sources.map(model => { const selected = draft.sourceResources?.[model.modelCode] ?? ''; return <div key={model.modelCode} data-testid={`publication-source-${model.modelCode}`}>
            {input(model.name || label('unnamed', '未命名业务模型', 'Unnamed business model'), <select required className={CONTROL} disabled={!!view?.activeVersion} value={selected}
              aria-invalid={sourceValidationAttempts > 0 && !selected} aria-describedby={sourceValidationAttempts > 0 && !selected ? `publication-source-error-${model.modelCode}` : undefined}
              onInvalid={event => { event.preventDefault(); setSourceValidationAttempts(previous => previous + 1); }}
              onChange={event => change({ ...draft, sourceResources: { ...draft.sourceResources, [model.modelCode]: event.target.value } })}>
              <option value="">{label('chooseSourceResource', '选择已发布的列表资源', 'Choose a published list resource')}</option>
              {selected && !model.resources.some(resource => resource.code === selected) && <option value={selected} disabled>{selected} ({label('sourceUnavailable', '来源已不可用', 'Source unavailable')})</option>}
              {model.resources.map(resource => <option key={resource.code} value={resource.code}>{resource.code} (v{resource.schemaVersion})</option>)}</select>)}
            {sourceValidationAttempts > 0 && !selected && <p id={`publication-source-error-${model.modelCode}`} role="alert" className="mt-2 text-sm text-danger">
              {label('sourceRequired', '请选择该来源的已发布列表资源。', 'Choose a published list resource for this source.')}</p>}
          </div>; })}
        </div>)}
        {sourceLoading && <p role="status">{label('sourceLoading', '正在读取来源能力…', 'Loading source capabilities…')}</p>}
        {source && section(label('operations', '操作与权限', 'Operations and scopes'), <div className="space-y-3">
          {OPERATIONS.map(op => <div key={op} className="grid items-center gap-3 md:grid-cols-2"><label className="flex items-center gap-2 text-sm text-text"><input type="checkbox" checked={draft.operations.includes(op)} disabled={!source.operations.includes(op)}
            onChange={event => { const operations = event.target.checked ? [...draft.operations, op] : draft.operations.filter(value => value !== op); const scopes = { ...draft.scopes }; if (event.target.checked) scopes[op] = ''; else delete scopes[op]; change({ ...draft, operations, scopes }); }} />{opLabel(op)}</label>
            {draft.operations.includes(op) && input(label('scope', '所需权限', 'Required scope'), <input required pattern="[a-z][a-z0-9.:\-]{1,159}" className={CONTROL} value={draft.scopes[op] ?? ''} placeholder={`sales.orders:${op}`} onChange={event => change({ ...draft, scopes: { ...draft.scopes, [op]: event.target.value } })} />)}</div>)}
          {input(label('dataPolicy', '机器数据范围', 'Machine data policy'), <select className={CONTROL} value={draft.dataPolicy} onChange={event => change({ ...draft, dataPolicy: event.target.value as PublicResource['dataPolicy'] })}>
            <option value="installation_filter">{label('filtered', '每个安装必须配置行约束', 'Require installation row constraints')}</option><option value="tenant_wide">{label('tenantWide', '允许安装访问租户内数据', 'Allow installation access to tenant data')}</option></select>,
            label('policyHelp', '安装授权只能收窄操作、字段和记录范围。', 'Installation grants can only narrow operations, fields and records.'))}
        </div>)}
        {source && section(label('fields', '公共字段白名单', 'Public field whitelist'), <div className="space-y-3"><p className="text-sm text-text-2">{label('pidHelp', '记录标识自动以只读公共 PID 返回。选择要公开的字段并为第三方命名。', 'Record identity is automatically returned as a read-only public PID. Select fields and name them for external clients.')}</p>
          {source.fields.map(meta => { const entry = Object.entries(draft.fields).find(([, field]) => field.source === meta.code); const alias = entry?.[0]; const field = entry?.[1];
            return <div key={meta.code} data-testid={`publication-field-${meta.code}`} className="space-y-3 rounded-control border border-border p-3"><label className="flex items-center gap-2 font-medium text-text"><input type="checkbox" checked={!!field} disabled={!meta.referencePid}
              onChange={event => { const fields = { ...draft.fields }; if (event.target.checked) { let index = 1; while (Object.hasOwn(fields, `field${index}`)) index++; const next = sourceField(meta); fields[`field${index}`] = publicValueType(next, next.type); } else if (alias) delete fields[alias]; change({ ...draft, fields }); }} />{t(`model.${draft.modelCode}.${meta.code}.label`, undefined,
                meta.code === 'created_at' ? label('createdAt', '创建时间', 'Created at') : meta.code === 'updated_at' ? label('updatedAt', '更新时间', 'Updated at') : meta.name || label('unnamedField', '未命名字段', 'Unnamed field'))}</label>
              {field && alias && <div className="grid gap-3 md:grid-cols-2">
                {input(label('alias', '对外字段名称', 'Public field name'), <input required pattern="[a-zA-Z][a-zA-Z0-9]{0,99}" className={CONTROL} value={alias} placeholder="orderName" onChange={event => {
                  const next = event.target.value; if (next === 'pid' || Object.hasOwn(draft.fields, next)) return; const fields = { ...draft.fields }; delete fields[alias]; fields[next] = field; change({ ...draft, fields });
                }} />)}
                {input(label('type', '对外类型', 'Public type'), <select className={CONTROL} value={field.type} onChange={event => updateField(alias, publicValueType(field, event.target.value as PublicField['type']))}>{meta.types.map(type => <option value={type} key={type}>{type}</option>)}</select>)}
                <div className="flex flex-wrap gap-3 md:col-span-2">{(['readable', 'writable', 'required', 'nullable', 'sortable'] as const).map(flag => <label key={flag} className="flex items-center gap-1 text-sm"><input type="checkbox" checked={field[flag]}
                  disabled={(flag === 'writable' && !meta.writable) || (flag === 'sortable' && (!meta.sortable || !field.readable || ['object', 'array'].includes(field.type)))} onChange={event => updateField(alias, {
                    ...field, [flag]: event.target.checked, ...(flag === 'readable' && !event.target.checked ? { operators: [], sortable: false } : {}),
                  })} />{({ readable: label('readable', '可读', 'Readable'), writable: label('writable', '可写', 'Writable'), required: label('required', '创建必填', 'Required on create'), nullable: label('nullable', '接受空值', 'Nullable'), sortable: label('sortable', '允许排序', 'Sortable') })[flag]}</label>)}</div>
                {meta.referenceModel && input(label('reference', '关联的公共资源', 'Referenced public resource'), <select required className={CONTROL} value={field.referenceResource ?? ''} onChange={event => updateField(alias, { ...field, referenceResource: event.target.value || null })}>
                  <option value="">{label('chooseReference', '选择已发布且允许详情读取的资源', 'Choose a published resource with detail access')}</option>{views.filter(row => row.status === 'published' && row.draft.modelCode === meta.referenceModel && row.draft.operations.includes('get')).map(row => <option key={row.code} value={row.code}>{row.code}</option>)}</select>)}
                {meta.filterable && field.readable && !['object', 'array'].includes(field.type) && <div className="space-y-2 md:col-span-2"><p className="text-sm">{label('filterOperators', '允许的过滤运算', 'Allowed filter operators')}</p>
                  <div className="flex flex-wrap gap-3">{['EQ', 'NE', 'GT', 'GE', 'LT', 'LE', 'IN', 'LIKE', 'IS_NULL'].map(operator => <label className="flex items-center gap-1 text-sm" key={operator}><input type="checkbox" checked={field.operators.includes(operator)} onChange={event => updateField(alias, { ...field, operators: event.target.checked ? [...field.operators, operator] : field.operators.filter(value => value !== operator) })} />{operator}</label>)}</div></div>}
                <div className="md:col-span-2"><PublicValueSchemaEditor field={field} onChange={next => updateField(alias, next)} /></div>
              </div>}
            </div>;
          })}
        </div>)}
        {section(label('budget', '执行预算与通知', 'Execution budgets and events'), <div className="grid gap-4 md:grid-cols-3">
          {input(label('maxPage', '单页上限', 'Maximum page size'), <input className={CONTROL} required type="number" min={1} max={100} step={1} value={draft.maxPageSize} onChange={event => change({ ...draft, maxPageSize: Number(event.target.value) })} />)}
          {input(label('maxConditions', '过滤条件上限', 'Maximum conditions'), <input className={CONTROL} required type="number" min={1} max={32} step={1} value={draft.maxConditions} onChange={event => change({ ...draft, maxConditions: Number(event.target.value) })} />)}
          {input(label('timeout', '查询超时（毫秒）', 'Query timeout (ms)'), <input className={CONTROL} required type="number" min={1} max={30000} step={1} value={draft.timeoutMs} onChange={event => change({ ...draft, timeoutMs: Number(event.target.value) })} />)}
          <div className="space-y-2 md:col-span-3"><p className="text-sm text-text-2">{label('eventHelp', '声明通知后，安装仍需配置订阅；通知字段受当前授权收窄。', 'Declared events still need installation subscriptions. Event fields are narrowed by current grants.')}</p>
            {(['created', 'updated', 'deleted'] as const).map(event => <label key={event} className="mr-4 inline-flex items-center gap-2 text-sm"><input type="checkbox" checked={draft.events.includes(event)} disabled={!draft.events.includes(event) && (!source?.eventsAllowed || Object.values(draft.fields).some(field => field.readable && source.fields.some(meta => meta.code === field.source && !meta.eventReadable)))}
              onChange={changeEvent => change({ ...draft, events: changeEvent.target.checked ? [...draft.events, event] : draft.events.filter(value => value !== event) })} />{event === 'created' ? label('created', '创建通知', 'Created') : event === 'updated' ? label('updated', '修改通知', 'Updated') : label('deleted', '删除通知', 'Deleted')}</label>)}</div>
        </div>)}
        <div className="sticky bottom-0 space-y-3 border-t border-border bg-panel py-3">
          {error && <div role="alert" className="rounded-control border border-danger bg-danger-weak p-3 text-sm text-danger">{error}</div>}
          {notice && <p role="status" className="text-sm text-success">{notice}</p>}
          <div className="flex flex-wrap gap-2"><button type="submit" className={`${BUTTON.replace('bg-panel', 'bg-accent').replace('hover:bg-hover', 'hover:bg-accent-hover').replace('text-text', 'text-white')} border-accent`} disabled={!source || !draft.operations.length || !dirty}>{busy ? label('working', '正在处理…', 'Working…') : label('save', '保存并校验草稿', 'Save and validate draft')}</button>
          <button type="button" className={BUTTON} disabled={!view || dirty || view.draft.schemaVersion <= (view.activeVersion ?? 0)} onClick={() => setConfirm('publish')}>{label('publish', '发布合同', 'Publish contract')}</button>
          <button type="button" className={BUTTON} disabled={view?.status !== 'published'} onClick={() => void download('openapi')}>{label('openapi', '下载 OpenAPI', 'Download OpenAPI')}</button>
          <button type="button" className={BUTTON} disabled={view?.status !== 'published'} onClick={() => void download('types')}>{label('types', '下载类型声明', 'Download types')}</button>
          <button type="button" className={BUTTON.replace('text-text', 'text-danger')} disabled={view?.status !== 'published' || dirty} onClick={() => setConfirm('withdraw')}>{label('withdraw', '停用资源', 'Withdraw resource')}</button>
          <button type="button" className={BUTTON} onClick={() => dirty ? setConfirm('discard') : setEditing(false)}>{label('close', '关闭编辑', 'Close editor')}</button></div></div>
      </fieldset>
      {confirm && <div ref={confirmationRef} role="group" aria-label={label('confirmation', '操作确认', 'Confirm operation')} className="space-y-3 rounded-control border border-border-strong bg-subtle p-4"><p className="text-sm text-text">{confirm === 'withdraw'
        ? label('withdrawConfirm', '停用将立即关闭此资源的外部访问、通知授权和合同下载。确认停用？', 'Withdrawal closes external access, event authority and downloads. Withdraw this resource?')
        : confirm === 'discard' ? label('discardConfirm', '放弃尚未保存的修改并关闭编辑？', 'Discard unsaved edits and close the editor?')
          : label('publishConfirm', '发布会创建不可变快照；服务端将检查来源和与当前版本的兼容性。确认发布？', 'Publishing creates an immutable snapshot. The server checks source validity and compatibility. Publish?')}</p>
        <div className="flex gap-2"><button type="button" className={BUTTON} disabled={busy} onClick={() => { if (confirm === 'discard') { setDirty(false); setEditing(false); setConfirm(null); void load(); } else void mutate(confirm); }}>{label('confirm', '确认', 'Confirm')}</button>
          <button type="button" className={BUTTON} disabled={busy} onClick={() => setConfirm(null)}>{label('cancel', '取消', 'Cancel')}</button></div></div>}
    </form>}
  </div>;
}

export default OpenApiPublicationEditor;
