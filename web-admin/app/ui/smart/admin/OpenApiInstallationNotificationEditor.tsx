import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { publicationRequest, PublicationApiError, type ResourceGrantView } from '~/services/open-api-publications';
import { PublicationField } from './PublicationControls';

const CONTROL = 'w-full min-w-0 rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text focus:outline-none focus:ring-2 focus:ring-accent-weak disabled:opacity-50';
const BUTTON = 'rounded-control border border-border-strong bg-panel px-3 py-2 text-sm text-text hover:bg-hover focus:outline-none focus:ring-2 focus:ring-accent-weak disabled:opacity-50';
const PRIMARY = BUTTON.replace('bg-panel', 'bg-accent').replace('hover:bg-hover', 'hover:bg-accent-hover').replace('text-text', 'text-white');
interface EventContract { type: string; currentVersion: number; supportedVersions: number[]; subjectResourceCode: string }
interface Subscription { pid: string; installationPid: string; name: string; targetUrl: string; eventType: string; eventVersion: number;
  secret: string; maxRetries: number; timeoutMs: number; enabled: boolean; modelCode?: string; headers?: string; filterExpression?: string }
interface Form { name: string; targetUrl: string; eventType: string; eventVersion: number; secret: string; maxRetries: number; timeoutMs: number }
const empty = (): Form => ({ name: '', targetUrl: '', eventType: '', eventVersion: 1, secret: '', maxRetries: 3, timeoutMs: 10000 });

/** Installation identity is inherited; event choices come from published, readable contracts. */
export function OpenApiInstallationNotificationEditor({ installationPid, active, scopes, onClose }: {
  installationPid: string; active: boolean; scopes: string[]; onClose: () => void;
}) {
  const { t, locale } = useI18n();
  const label = (key: string, cn: string, en: string) => t(`openPlatform.notifications.${key}`, undefined, locale.startsWith('zh') ? cn : en);
  const [events, setEvents] = useState<EventContract[]>([]); const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
  const [form, setForm] = useState<Form>(empty); const [selected, setSelected] = useState<Subscription | null>(null);
  const [loading, setLoading] = useState(true); const [ready, setReady] = useState(false); const [busy, setBusy] = useState(false);
  const [dirty, setDirty] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('');
  const [denied, setDenied] = useState(false); const [confirm, setConfirm] = useState<'discard' | 'disable' | null>(null);
  const [pendingDisable, setPendingDisable] = useState<Subscription | null>(null);
  const requestId = useRef(0); const confirmRef = useRef<HTMLDivElement>(null);
  const fail = (cause: unknown) => {
    if (cause instanceof PublicationApiError && cause.status === 403) { setDenied(true); setError(label('denied', '需要当前租户的开放平台管理与 Webhook 管理权限。', 'Open Platform and Webhook administrator permissions in this tenant are required.')); }
    else setError(label('failed', '通知配置请求失败，请检查合同、授权及接收地址后重试。', 'Notification request failed. Check the contract, authority and receiver address before retrying.'));
  };
  const load = async () => {
    const id = ++requestId.current; setLoading(true); setReady(false); setError(''); setDenied(false);
    try {
      const [catalog, grants, rows] = await Promise.all([
        publicationRequest<EventContract[]>('/api/open-platform/event-catalog'),
        publicationRequest<ResourceGrantView[]>(`/api/open-platform/installations/${encodeURIComponent(installationPid)}/resources`),
        publicationRequest<Subscription[]>('/api/webhooks'),
      ]);
      if (id !== requestId.current) return;
      setEvents(catalog.filter(event => event.type.startsWith('open.resource.') && grants.some(grant =>
        grant.code === event.subjectResourceCode && grant.status === 'published' && grant.policy &&
        (['get', 'list'] as const).some(operation => grant.policy!.operations.includes(operation) && scopes.includes(grant.contract.scopes[operation] ?? '')))));
      setSubscriptions(rows.filter(row => row.installationPid === installationPid && row.eventType.startsWith('open.resource.')));
      setReady(true);
    } catch (cause) { if (id === requestId.current) fail(cause); }
    finally { if (id === requestId.current) setLoading(false); }
  };
  useEffect(() => { void load(); return () => { requestId.current++; }; }, [installationPid]);
  useEffect(() => { const listener = (event: BeforeUnloadEvent) => { if (dirty) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', listener); return () => window.removeEventListener('beforeunload', listener); }, [dirty]);
  useEffect(() => { if (confirm) { confirmRef.current?.scrollIntoView({ block: 'nearest' }); confirmRef.current?.querySelector<HTMLButtonElement>('button')?.focus(); } }, [confirm]);
  const change = (next: Partial<Form>) => { setForm(current => ({ ...current, ...next })); setDirty(true); setError(''); setNotice(''); };
  const edit = (row: Subscription | null) => { setSelected(row); setForm(row ? { name: row.name, targetUrl: row.targetUrl, eventType: row.eventType,
    eventVersion: row.eventVersion, secret: '', maxRetries: row.maxRetries, timeoutMs: row.timeoutMs } : empty()); setDirty(false); setError(''); setNotice(''); };
  const contract = events.find(event => event.type === form.eventType);
  const editable = active && ready && !busy && !denied && !!contract && contract.supportedVersions.includes(form.eventVersion);
  const save = async (event: FormEvent) => { event.preventDefault(); if (!editable) return; setBusy(true); setError('');
    try {
      const row = await publicationRequest<Subscription>(selected ? `/api/webhooks/${encodeURIComponent(selected.pid)}` : '/api/webhooks', {
        method: selected ? 'PUT' : 'POST', body: JSON.stringify({ ...form, installationPid, secret: form.secret || selected?.secret,
          enabled: selected?.enabled ?? true, modelCode: selected?.modelCode, headers: selected?.headers, filterExpression: selected?.filterExpression }),
      });
      edit(row); await load(); setNotice(label('saved', '订阅已保存。实际通知按发送时的合同、安装授权及行范围裁决；在运维面板查看投递。', 'Subscription saved. Each send rechecks the contract, installation authority and row policy. View deliveries in Operations.'));
    } catch (cause) { fail(cause); } finally { setBusy(false); }
  };
  const enable = async (row: Subscription) => { setBusy(true); setError('');
    try { await publicationRequest(`/api/webhooks/${encodeURIComponent(row.pid)}/${row.enabled ? 'disable' : 'enable'}`, { method: 'PUT' });
      setConfirm(null); edit(null); await load(); setNotice(row.enabled ? label('disabled', '订阅已停用，后续不再发送。', 'Subscription disabled. Subsequent deliveries will not be sent.') : label('enabled', '订阅已启用，发送时仍复核当前授权。', 'Subscription enabled. Sends still recheck current authority.'));
    } catch (cause) { fail(cause); } finally { setBusy(false); }
  };
  const eventName = (event: EventContract) => `${event.subjectResourceCode} · ${label(event.type.split('.').at(-1)!,
    ({ created: '新增记录', updated: '修改记录', deleted: '删除记录' } as Record<string, string>)[event.type.split('.').at(-1)!] ?? '模型事件',
    ({ created: 'Record created', updated: 'Record updated', deleted: 'Record deleted' } as Record<string, string>)[event.type.split('.').at(-1)!] ?? 'Model event')}`;
  return <section data-testid="installation-notification-editor" className="mt-4 min-w-0 space-y-4 rounded-card border border-border bg-panel p-4 text-text">
    <div className="flex flex-wrap items-center justify-between gap-3"><h3 className="font-semibold">{label('title', '动态模型通知', 'Dynamic model notifications')}</h3>
      <div className="flex flex-wrap gap-2"><button type="button" className={BUTTON} disabled={busy || loading || dirty} onClick={() => void load()}>{label('reload', '刷新通知', 'Reload notifications')}</button>
        <button type="button" className={BUTTON} disabled={busy} onClick={() => dirty ? setConfirm('discard') : onClose()}>{label('close', '关闭通知编辑', 'Close notification editor')}</button></div></div>
    <p className="text-sm text-text-secondary">{label('help', '订阅自动关联当前安装。只可选择已发布且此安装有读取授权与 Scope 的动态模型事件。接收方需校验原始请求签名，并按事件标识去重。', 'Subscriptions inherit this installation. Choose published model events with current read authority and scopes. Receivers must verify the raw request signature and deduplicate by event identity.')}</p>
    {loading && <p role="status">{label('loading', '正在读取事件合同与订阅…', 'Loading event contracts and subscriptions…')}</p>}
    {error && <p role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    {!active && <p role="status">{label('inactive', '安装或应用已停用；通知配置只读。', 'The installation or application is inactive. Notification configuration is read-only.')}</p>}
    {ready && !denied && <>
      <div data-testid="notification-subscriptions" role="group" className="space-y-3" aria-label={label('subscriptions', '当前安装订阅', 'Installation subscriptions')}>
        {!subscriptions.length && <p className="text-sm">{label('empty', '此安装尚无动态模型通知订阅。配置事件合同和接收地址后保存。', 'This installation has no model notification subscriptions. Choose an event contract and receiver, then save.')}</p>}
        {subscriptions.map(row => <div key={row.pid} data-testid={`notification-${row.pid}`} className="flex min-w-0 flex-wrap items-center justify-between gap-3 rounded-control border border-border p-3">
          <div className="min-w-0"><p className="font-medium break-words">{row.name}</p><p className="text-sm break-words">{eventName({ type: row.eventType, subjectResourceCode: row.eventType.slice('open.resource.'.length, row.eventType.lastIndexOf('.')), currentVersion: row.eventVersion, supportedVersions: [row.eventVersion] })}</p><p className="text-sm break-all">{row.targetUrl}</p><p className="text-sm">v{row.eventVersion} · {row.enabled ? label('on', '已启用', 'Enabled') : label('off', '已停用', 'Disabled')}</p></div>
          <div className="flex gap-2"><button type="button" className={BUTTON} disabled={busy || dirty} onClick={() => edit(row)}>{label('edit', '编辑订阅', 'Edit subscription')}</button>
            <button type="button" className={BUTTON} disabled={!active || busy || dirty || (!row.enabled && !events.some(event => event.type === row.eventType && event.supportedVersions.includes(row.eventVersion)))}
              onClick={() => row.enabled ? (setPendingDisable(row), setConfirm('disable')) : void enable(row)}>{row.enabled ? label('disable', '停用订阅', 'Disable subscription') : label('enable', '启用订阅', 'Enable subscription')}</button></div>
        </div>)}
      </div>
      {!events.length && <p role="status">{label('noEvents', '暂无可订阅事件。请先发布模型事件，并为此安装配置读取 Scope 和数据授权。', 'No subscribable events. Publish model events and grant this installation read scopes and data authority first.')}</p>}
      <form className="space-y-4" onSubmit={event => void save(event)}>
        <fieldset data-testid="notification-contract-fields" disabled={!active || busy} className="grid min-w-0 gap-3 md:grid-cols-2"><legend className="mb-3 font-medium">{label('contract', '事件合同', 'Event contract')}</legend>
          <PublicationField name={label('name', '订阅名称', 'Subscription name')} child={<input className={CONTROL} required value={form.name} placeholder={label('nameExample', '例如：ERP 客户同步', 'For example: ERP customer sync')} onChange={event => change({ name: event.target.value })} />} />
          <PublicationField name={label('event', '模型事件', 'Model event')} child={<select className={CONTROL} required value={form.eventType} onChange={event => change({ eventType: event.target.value, eventVersion: events.find(item => item.type === event.target.value)?.currentVersion ?? 1 })}>
            <option value="">{label('choose', '选择已授权模型事件', 'Choose an authorized model event')}</option>{events.map(event => <option key={event.type} value={event.type}>{eventName(event)}</option>)}
            {form.eventType && !contract && <option value={form.eventType}>{label('unavailable', '原事件已撤回或缺少读取授权', 'Original event withdrawn or read authority missing')}</option>}
          </select>} />
          <PublicationField name={label('version', '事件版本', 'Event version')} child={<select className={CONTROL} required value={form.eventVersion} disabled={!contract} onChange={event => change({ eventVersion: Number(event.target.value) })}>
            {(contract?.supportedVersions ?? [form.eventVersion]).slice().sort((a, b) => a - b).map(version => <option key={version} value={version}>v{version}</option>)}
          </select>} />
          {contract && <p data-testid="notification-current-event" className="text-sm break-words md:col-span-2">{label('currentEvent', '当前事件', 'Selected event')}: {eventName(contract)} · v{form.eventVersion}</p>}
        </fieldset>
        <fieldset data-testid="notification-receiver-fields" disabled={!active || busy} className="grid min-w-0 gap-3 md:grid-cols-2"><legend className="mb-3 font-medium">{label('receiver', '接收与安全', 'Receiver and security')}</legend>
          <PublicationField name={label('url', '接收地址', 'Receiver URL')} child={<input className={CONTROL} type="url" required value={form.targetUrl} placeholder="https://partner.example/hooks" onChange={event => change({ targetUrl: event.target.value })} />} />
          <PublicationField name={label('secret', '签名密钥', 'Signing secret')} child={<input className={CONTROL} type="password" autoComplete="new-password" required={!selected} value={form.secret} placeholder={selected ? label('keepSecret', '留空保留现有密钥', 'Leave blank to keep the existing secret') : label('sharedSecret', '与接收方约定的签名密钥', 'Signing secret shared with the receiver')} onChange={event => change({ secret: event.target.value })} />} />
          <PublicationField name={label('retries', '最大尝试次数', 'Maximum attempts')} child={<input className={CONTROL} type="number" required min={1} max={10} value={form.maxRetries} onChange={event => change({ maxRetries: Number(event.target.value) })} />} />
          <PublicationField name={label('timeout', '接收超时（毫秒）', 'Receiver timeout (ms)')} child={<input className={CONTROL} type="number" required min={1000} max={30000} step={1000} value={form.timeoutMs} onChange={event => change({ timeoutMs: Number(event.target.value) })} />} />
        </fieldset>
        <p className="text-sm text-text-secondary">{label('securityHelp', '创建时必须配置签名密钥；编辑时留空保留。返回失败会按次数上限重试并进入死信，可在运维面板确认重放。', 'A signing secret is required on creation; leave it blank when editing to keep it. Failed deliveries retry up to the attempt limit, then enter dead letter. Confirm replay in Operations.')}</p>
        <div data-testid="notification-actions" className="sticky bottom-0 flex flex-wrap gap-2 border-t border-border bg-panel py-3"><button type="submit" className={PRIMARY} disabled={!editable || !dirty}>{label('save', '保存通知订阅', 'Save notification subscription')}</button>
          {selected && <button type="button" className={BUTTON} disabled={busy || dirty} onClick={() => edit(null)}>{label('new', '新建通知订阅', 'New notification subscription')}</button>}</div>
      </form>
    </>}
    {confirm && <div ref={confirmRef} role="group" aria-label={label('confirmation', '通知操作确认', 'Notification confirmation')} className="space-y-3 rounded-control border border-border bg-canvas p-3">
      <p>{confirm === 'disable' ? label('disableHelp', '确认停用此订阅？后续通知及重放将停止发送。', 'Disable this subscription? Subsequent notifications and replays will not be sent.') : label('discardHelp', '放弃未保存的通知修改并关闭？', 'Discard unsaved notification changes and close?')}</p>
      <div className="flex flex-wrap gap-2"><button type="button" className={BUTTON} disabled={busy} onClick={() => confirm === 'disable' && pendingDisable ? void enable(pendingDisable) : onClose()}>{label('confirm', '确认', 'Confirm')}</button>
        <button type="button" className={BUTTON} disabled={busy} onClick={() => setConfirm(null)}>{label('cancel', '取消', 'Cancel')}</button></div>
    </div>}
  </section>;
}
