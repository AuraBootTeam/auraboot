import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { publicationRequest, PublicationApiError, type ResourceGrantView, type QueryGrantView,
  type InstallationResourcePolicy, type InstallationQueryPolicy, type PublicOperation, type ActionGrantView, type InstallationActionPolicy } from '~/services/open-api-publications';
import { usePublicationConfirmation, PublicationField } from './PublicationControls';

const CONTROL = 'w-full rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text focus:outline-none focus:ring-2 focus:ring-accent-weak disabled:opacity-50';
const BUTTON = 'rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text hover:bg-hover focus:outline-none focus:ring-2 focus:ring-accent-weak disabled:opacity-50';
const PRIMARY = BUTTON.replace('bg-panel', 'bg-accent').replace('hover:bg-hover', 'hover:bg-accent-hover').replace('text-text', 'text-white');
const emptyResource = (): InstallationResourcePolicy => ({ operations: [], readableFields: ['pid'], writableFields: [], rowConstraints: [] });
const toggle = <T extends string>(values: T[], value: T) => values.includes(value) ? values.filter(item => item !== value) : [...values, value];

/** Structured installation authority is edited against published contracts, never local drafts. */
export function OpenApiInstallationGrantEditor({ installationPid, active, scopes, onClose }: {
  installationPid: string; active: boolean; scopes: string[]; onClose: () => void;
}) {
  const { t, locale } = useI18n();
  const label = (key: string, cn: string, en: string) => t(`openPlatform.installationGrants.${key}`, undefined, locale.startsWith('zh') ? cn : en);
  const [resources, setResources] = useState<ResourceGrantView[]>([]); const [queries, setQueries] = useState<QueryGrantView[]>([]); const [actions, setActions] = useState<ActionGrantView[]>([]);
  const [selection, setSelection] = useState(''); const [resourcePolicy, setResourcePolicy] = useState<InstallationResourcePolicy>(emptyResource);
  const [queryPolicy, setQueryPolicy] = useState<InstallationQueryPolicy>({ readableFields: [] });
  const [actionPolicy, setActionPolicy] = useState<InstallationActionPolicy>({ execute: false, effectFields: [] });
  const [loading, setLoading] = useState(true); const [busy, setBusy] = useState(false); const [dirty, setDirty] = useState(false);
  const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [denied, setDenied] = useState(false);
  const [confirmation, setConfirmation] = useState<'revoke' | 'discard' | null>(null); const confirmationRef = useRef<HTMLDivElement>(null);
  const requestId = useRef(0);
  const isQuery = selection.startsWith('query:'); const isAction = selection.startsWith('action:'); const code = selection.slice(selection.indexOf(':') + 1);
  const resource = isQuery || isAction ? undefined : resources.find(view => view.code === code);
  const query = isQuery ? queries.find(view => view.code === code) : undefined;
  const action = isAction ? actions.find(view => view.code === code) : undefined;
  const selected = query ?? action ?? resource; const editable = active && selected?.status === 'published' && !busy && !loading && !denied;
  const fail = (cause: unknown) => {
    if (cause instanceof PublicationApiError && cause.status === 403) { setDenied(true); setError(label('denied', '需要当前租户的开放平台管理权限。', 'Open Platform administrator permission in this tenant is required.')); }
    else setError(cause instanceof Error ? cause.message : label('failed', '授权请求失败。', 'Authority request failed.'));
  };
  const load = async () => { const id = ++requestId.current; setLoading(true); setError(''); setDenied(false);
    try { const [nextResources, nextQueries, nextActions] = await Promise.all([
      publicationRequest<ResourceGrantView[]>(`/api/open-platform/installations/${encodeURIComponent(installationPid)}/resources`),
      publicationRequest<QueryGrantView[]>(`/api/open-platform/installations/${encodeURIComponent(installationPid)}/queries`),
      publicationRequest<ActionGrantView[]>(`/api/open-platform/installations/${encodeURIComponent(installationPid)}/actions`),
    ]); if (id === requestId.current) { setResources(nextResources); setQueries(nextQueries); setActions(nextActions); } }
    catch (cause) { if (id === requestId.current) fail(cause); } finally { if (id === requestId.current) setLoading(false); }
  };
  useEffect(() => { void load(); return () => { requestId.current++; }; }, [installationPid]);
  useEffect(() => { const listener = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', listener); return () => window.removeEventListener('beforeunload', listener);
  }, [dirty]);
  const confirmationKeyDown = usePublicationConfirmation(confirmation, confirmationRef, () => setConfirmation(null), busy);
  const select = (key: string) => { setSelection(key); setDirty(false); setError(''); setNotice(''); setConfirmation(null);
    if (key.startsWith('action:')) setActionPolicy(structuredClone(actions.find(view => view.code === key.slice(7))?.policy ?? { execute: false, effectFields: [] }));
    else if (key.startsWith('query:')) setQueryPolicy(structuredClone(queries.find(view => view.code === key.slice(6))?.policy ?? { readableFields: [] }));
    else setResourcePolicy(structuredClone(resources.find(view => view.code === key.slice(9))?.policy ?? emptyResource()));
  };
  const changeResource = (next: InstallationResourcePolicy) => { setResourcePolicy(next); setDirty(true); setError(''); setNotice(''); };
  const changeQuery = (next: InstallationQueryPolicy) => { setQueryPolicy(next); setDirty(true); setError(''); setNotice(''); };
  const save = async (event: FormEvent) => { event.preventDefault(); if (!editable) return; setBusy(true); setError('');
    try { await publicationRequest(`/api/open-platform/installations/${encodeURIComponent(installationPid)}/${isAction ? 'actions' : isQuery ? 'queries' : 'resources'}/${encodeURIComponent(code)}`,
      { method: 'PUT', body: JSON.stringify(isAction ? actionPolicy : isQuery ? queryPolicy : resourcePolicy) }); setDirty(false); await load();
      setNotice(label('saved', '授权已保存。实际调用仍受 Scope、当前合同及全部来源授权约束。', 'Authority saved. Calls remain subject to scopes, current contracts and every source grant.'));
    } catch (cause) { fail(cause); } finally { setBusy(false); }
  };
  const revoke = async () => { setBusy(true); setError('');
    try { await publicationRequest(`/api/open-platform/installations/${encodeURIComponent(installationPid)}/${isAction ? 'actions' : isQuery ? 'queries' : 'resources'}/${encodeURIComponent(code)}`, { method: 'DELETE' });
      setDirty(false); setConfirmation(null); if (isAction) setActionPolicy({ execute: false, effectFields: [] }); else if (isQuery) setQueryPolicy({ readableFields: [] }); else setResourcePolicy(emptyResource()); await load();
      setNotice(label('revoked', '授权已撤销，后续调用及通知重新按当前权限裁决。', 'Authority revoked. Subsequent calls and notifications recheck current authority.'));
    } catch (cause) { fail(cause); } finally { setBusy(false); }
  };
  const operationName = (operation: PublicOperation) => label(operation, ({ list: '列表查询', get: '详情读取', create: '创建记录', update: '修改记录', delete: '删除记录' })[operation], operation);
  return <section data-testid="installation-grant-editor" className="mt-4 space-y-4 rounded-card border border-border bg-panel p-4 text-text">
    <div className="flex flex-wrap items-center justify-between gap-3"><h3 className="text-base font-semibold">{label('title', '安装数据授权', 'Installation data authority')}</h3>
      <button type="button" className={BUTTON} disabled={busy} onClick={() => dirty ? setConfirmation('discard') : onClose()}>{label('close', '关闭授权编辑', 'Close authority editor')}</button></div>
    <p className="text-sm text-text-2">{label('help', '选择已发布合同，明确收窄操作、字段及行范围。安装 Scope 与数据授权共同决定外部访问；草稿不参与授权。', 'Choose a published contract and narrow operations, fields and rows. Installation scopes and data grants jointly control access; drafts cannot grant authority.')}</p>
    {loading && <p role="status">{label('loading', '正在读取安装授权…', 'Loading installation authority…')}</p>}
    {!loading && !denied && !resources.length && !queries.length && !actions.length && <p>{label('empty', '暂无已发布合同，请先完成资源或查询发布。', 'No published contracts. Publish a resource or query first.')}</p>}
    {!denied && <PublicationField name={label('contract', '授权合同', 'Authority contract')} child={<select className={CONTROL} value={selection} disabled={loading || busy || dirty} onChange={event => select(event.target.value)}>
      <option value="">{label('choose', '选择公共资源或查询', 'Choose a public resource or query')}</option>
      <optgroup label={label('resources', '公共资源', 'Public resources')}>{resources.map(view => <option key={view.code} value={`resource:${view.code}`}>{view.code} · v{view.contract.schemaVersion}{view.status === 'published' ? '' : ` · ${label('withdrawn', '已停用', 'Withdrawn')}`}</option>)}</optgroup>
      <optgroup label={label('queries', '公共查询', 'Public queries')}>{queries.map(view => <option key={view.code} value={`query:${view.code}`}>{view.code} · v{view.contract.schemaVersion}{view.status === 'published' ? '' : ` · ${label('withdrawn', '已停用', 'Withdrawn')}`}</option>)}</optgroup>
      <optgroup label={label('actions', '公共业务接口', 'Public business APIs')}>{actions.map(view => <option key={view.code} value={`action:${view.code}`}>{view.code} · v{view.contract.schemaVersion}{view.status === 'published' ? '' : ` · ${label('withdrawn', '已停用', 'Withdrawn')}`}</option>)}</optgroup>
    </select>} />}
    {!denied && selected && <form onSubmit={event => void save(event)} className="space-y-4">
      <p className="text-sm">{selected.policy ? label('existing', '此安装已配置数据授权。', 'This installation has data authority.') : label('absent', '此安装尚未获此合同的数据授权。', 'This installation has no authority for this contract.')}</p>
      {!active && <p role="status">{label('inactive', '安装已停用；可查看或撤销现存授权，不能新增授权。', 'Installation is inactive. Existing authority can be viewed or revoked, but not granted.')}</p>}
      {selected.status !== 'published' && <p role="status">{label('unpublished', '合同已停用；现存授权不会开放调用，可撤销授权。', 'Contract is withdrawn. Existing grants cannot enable calls and may be revoked.')}</p>}
      <fieldset disabled={!editable} className="space-y-4">
        {resource && <><fieldset className="space-y-2"><legend className="font-medium">{label('operations', '允许操作', 'Allowed operations')}</legend>
          {resource.contract.operations.map(operation => <label key={operation} className="flex flex-wrap items-center gap-2"><input type="checkbox" checked={resourcePolicy.operations.includes(operation)} onChange={() => changeResource({ ...resourcePolicy, operations: toggle(resourcePolicy.operations, operation) })} />{operationName(operation)}
            {!scopes.includes(resource.contract.scopes[operation] ?? '') && <span className="text-sm text-text-2">{label('missingScope', '安装尚缺对应 Scope，授权后仍不能调用。', 'Installation lacks the corresponding scope; granting authority will not enable calls.')}</span>}</label>)}</fieldset>
          <fieldset className="space-y-2"><legend className="font-medium">{label('fields', '公共字段权限', 'Public field authority')}</legend>
            {Object.entries(resource.contract.fields).map(([alias, field]) => <div key={alias} className="flex flex-wrap items-center gap-4 rounded-control border border-border p-3"><span className="min-w-24 font-medium">{alias}</span>
              {field.readable && <label><input type="checkbox" disabled={alias === 'pid'} checked={resourcePolicy.readableFields.includes(alias)} onChange={() => changeResource({ ...resourcePolicy, readableFields: toggle(resourcePolicy.readableFields, alias) })} /> {label('read', '读取', 'Read')} {alias}</label>}
              {field.writable && <label><input type="checkbox" checked={resourcePolicy.writableFields.includes(alias)} onChange={() => changeResource({ ...resourcePolicy, writableFields: toggle(resourcePolicy.writableFields, alias) })} /> {label('write', '写入', 'Write')} {alias}{field.required ? ` (${label('required', '创建必填', 'Required on create')})` : ''}</label>}</div>)}</fieldset>
          <fieldset className="space-y-3"><legend className="font-medium">{label('rows', '行范围约束', 'Row constraints')}</legend>
            <p className="text-sm text-text-2">{label('rowHelp', '不同字段条件同时满足；同一字段可填写多个允许值，每行一个。', 'All field constraints must hold; multiple values for one field are alternatives, one value per line.')}{resource.contract.dataPolicy === 'installation_filter' ? ` ${label('rowRequired', '此合同必须配置行范围。', 'This contract requires row constraints.')}` : ''}</p>
            {resourcePolicy.rowConstraints.map((constraint, index) => <div key={index} className="space-y-2 rounded-control border border-border p-3" data-testid={`grant-constraint-${index}`}>
              <PublicationField name={label('rowField', '约束公共字段', 'Constraint public field')} child={<select className={CONTROL} required value={constraint.field} onChange={event => changeResource({ ...resourcePolicy, rowConstraints: resourcePolicy.rowConstraints.map((value, i) => i === index ? { ...value, field: event.target.value } : value) })}>
                <option value="">{label('chooseField', '选择可过滤字段', 'Choose a filterable field')}</option>{Object.entries(resource.contract.fields).filter(([, field]) => field.readable && field.operators.length && !['object', 'array'].includes(field.type)).map(([alias]) => <option key={alias} value={alias}>{alias}</option>)}</select>} />
              <PublicationField name={label('rowValues', '允许值（每行一个）', 'Allowed values (one per line)')} child={<textarea className={CONTROL} required rows={3} value={constraint.values.join('\n')} onChange={event => changeResource({ ...resourcePolicy, rowConstraints: resourcePolicy.rowConstraints.map((value, i) => i === index ? { ...value, values: event.target.value.split('\n') } : value) })} />} />
              <button type="button" className={BUTTON} onClick={() => changeResource({ ...resourcePolicy, rowConstraints: resourcePolicy.rowConstraints.filter((_, i) => i !== index) })}>{label('remove', '移除约束', 'Remove constraint')}</button></div>)}
            <button type="button" className={BUTTON} disabled={resourcePolicy.rowConstraints.length >= 16} onClick={() => changeResource({ ...resourcePolicy, rowConstraints: [...resourcePolicy.rowConstraints, { field: '', values: [''] }] })}>{label('add', '添加行约束', 'Add row constraint')}</button></fieldset></>}
        {query && <><fieldset className="space-y-2"><legend className="font-medium">{label('queryFields', '查询输出字段', 'Query output fields')}</legend>{Object.keys(query.contract.fields).map(alias => <label key={alias} className="flex items-center gap-2"><input type="checkbox" checked={queryPolicy.readableFields.includes(alias)} onChange={() => changeQuery({ readableFields: toggle(queryPolicy.readableFields, alias) })} />{alias}</label>)}</fieldset>
          {!scopes.includes(query.contract.scopes.execute) && <p>{label('queryScope', '安装缺少查询执行 Scope。', 'Installation lacks the query execution scope.')}</p>}
          <div><h4 className="font-medium">{label('sources', '查询来源授权', 'Query source authority')}</h4>{Object.values(query.contract.sourceResources).map(source => { const authority = resources.find(view => view.code === source); return <p key={source} className="text-sm">{source}: {authority?.status === 'published' && authority.policy?.operations.includes('list') ? label('sourceGranted', '已有列表授权；字段和行范围仍参与查询裁决。', 'List granted; fields and rows remain enforced.') : label('sourceMissing', '缺少当前列表授权，查询调用将被拒绝。', 'Current list authority is missing; query calls will be denied.')}</p>; })}</div></>}
        {action && <><label className="flex items-center gap-2"><input type="checkbox" checked={actionPolicy.execute} onChange={event => { setActionPolicy({ ...actionPolicy, execute: event.target.checked }); setDirty(true); setError(''); setNotice(''); }} />{label('actionExecute', '允许调用此业务接口', 'Allow this business API')}</label>
          {!!action.contract.effectFields.length && <fieldset className="space-y-2"><legend className="font-medium">{label('actionEffects', '允许的受控变更字段', 'Allowed controlled effects')}</legend>
            <p className="text-sm text-text-2">{label('actionEffectsHelp', '命令需要全部声明字段。状态字段的受控变更不会开放通用修改；资源当前的输入、可读字段与行范围仍生效。', 'The command requires every declared effect. Controlled state effects do not enable generic writes; current resource input, readable field and row authority still applies.')}</p>
            {action.contract.effectFields.map(alias => <label key={alias} className="flex items-center gap-2"><input type="checkbox" checked={actionPolicy.effectFields.includes(alias)} onChange={() => { setActionPolicy({ ...actionPolicy, effectFields: toggle(actionPolicy.effectFields, alias) }); setDirty(true); setError(''); setNotice(''); }} />{alias}</label>)}</fieldset>}
          {!scopes.includes(action.contract.scopes.execute) && <p>{label('actionScope', '安装缺少此业务接口的调用 Scope。', 'Installation lacks this business API execution scope.')}</p>}
          <p className="text-sm">{label('actionResource', '绑定资源', 'Bound resource')}: {action.contract.resourceCode}</p>
          {(() => { const source = resources.find(view => view.code === action.contract.resourceCode); const operation = action.contract.sourceKind === 'command' ? 'update' : 'list';
            return <p className="text-sm text-text-2">{source?.status === 'published' && source.policy?.operations.includes(operation)
              ? label('actionResourceGranted', '已有所需资源操作授权；调用仍会复核字段、行范围及 Scope。', 'Required resource operation granted. Calls still recheck fields, rows and scopes.')
              : label('actionResourceMissing', '缺少绑定资源的当前操作授权，接口调用将被拒绝。', 'Current bound-resource operation authority is missing; calls will be denied.')}</p>; })()}
        </>}
      </fieldset>
      <div className="sticky bottom-0 space-y-3 border-t border-border bg-panel py-3">
        {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
        <div className="flex flex-wrap gap-2"><button type="submit" className={PRIMARY} disabled={!editable || !dirty}>{label('save', '保存安装授权', 'Save installation authority')}</button>
          <button type="button" className={BUTTON} disabled={busy || !selected.policy} onClick={() => setConfirmation('revoke')}>{label('revoke', '撤销安装授权', 'Revoke installation authority')}</button></div>
      </div>
    </form>}
    {(!selected || denied) && error && <p role="alert">{error}</p>}
    {confirmation && <div ref={confirmationRef} tabIndex={-1} onKeyDown={confirmationKeyDown} role="group" aria-label={label('confirmation', '安装授权操作确认', 'Installation authority confirmation')} className="space-y-3 rounded-control border border-border bg-bg p-3">
      <p>{confirmation === 'revoke' ? label('revokeHelp', '确认撤销此安装的合同授权？后续调用及通知将按撤销后的权限处理。', 'Revoke this installation grant? Subsequent calls and notifications will use the revoked authority.') : label('discardHelp', '放弃未保存的授权修改并关闭？', 'Discard unsaved authority changes and close?')}</p>
      <div className="flex gap-2"><button type="button" className={BUTTON} disabled={busy} onClick={() => confirmation === 'revoke' ? void revoke() : onClose()}>{label('confirm', '确认', 'Confirm')}</button><button type="button" className={BUTTON} disabled={busy} onClick={() => setConfirmation(null)}>{label('cancel', '取消', 'Cancel')}</button></div>
    </div>}
  </section>;
}
