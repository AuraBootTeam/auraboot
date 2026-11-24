import { Link, useSearchParams } from 'react-router';
import { useAuditText } from '~/ui/smart/diagnostics/HttpAuditInspector';
import OpenPlatformCallLogs from './OpenPlatformCallLogs';
import OpenPlatformOperationsPanel from './OpenPlatformOperationsPanel';

export interface OperationsApplication {
  pid: string; name: string; status: string;
  permissions: { readOperations: boolean; manageRuntime: boolean };
  installations: { pid: string; environment: 'development' | 'staging' | 'production'; status: string }[];
}
export function OpenPlatformOperationsTabs({ view }: { view: string }) {
  const text = useAuditText();
  return <nav className="border-border flex min-w-0 flex-wrap gap-6 border-b" aria-label={text('navigation', 'Open Platform navigation')}>
    {[[ 'applications', text('applications', 'Applications') ], ['calls', text('callLogs', 'API call logs')], ['webhooks', text('webhooks', 'Webhook deliveries')]].map(([value, label]) => <Link key={value} to={`/settings/api-docs?view=${value}`} className={`pb-3 text-sm ${view === value ? 'border-accent text-accent border-b-2 font-medium' : 'text-text-2'}`} aria-current={view === value ? 'page' : undefined}>{label}</Link>)}
  </nav>;
}
export default function OpenPlatformOperationsWorkspace({ applications, loading, error, onRetry }: {
  applications: OperationsApplication[]; loading: boolean; error: string | null; onRetry: () => void;
}) {
  const text = useAuditText(); const [params, setParams] = useSearchParams();
  const view = params.get('view') === 'webhooks' ? 'webhooks' : 'calls';
  const readable = applications.filter(application => application.permissions.readOperations);
  const selectedApplication = readable.find(app => app.pid === params.get('application')) ?? readable[0];
  const installations = selectedApplication?.installations ?? [];
  const installation = installations.find(item => item.pid === params.get('installation')) ?? installations[0];
  const selectApplication = (pid: string) => { const next = new URLSearchParams(params); next.set('application', pid); next.delete('installation'); setParams(next); };
  const selectInstallation = (pid: string) => { const next = new URLSearchParams(params); next.set('installation', pid); if(selectedApplication)next.set('application', selectedApplication.pid); setParams(next); };
  const environmentLabel = (environment: string) => environment === 'production' ? text('production', 'Production') : environment === 'staging' ? text('staging', 'Staging') : text('development', 'Development');
  return <main className="mx-auto min-w-0 max-w-[1800px] space-y-5 p-4 sm:p-6" data-testid="open-platform-page">
    <header><p className="text-text-3 mb-1 text-xs">{text('openPlatform', 'Open Platform')}</p><h1 className="text-text text-2xl font-semibold">{view === 'calls' ? text('callLogs', 'API call logs') : text('webhooks', 'Webhook deliveries')}</h1><p className="text-text-3 mt-2 text-sm">{view === 'calls' ? text('subtitle', 'Trace API calls and inspect their requests, responses and results.') : text('webhookSubtitle', 'Inspect outbound deliveries, retries and integration health separately.')}</p></header>
    <OpenPlatformOperationsTabs view={view}/>
    {loading ? <div className="bg-hover h-40 animate-pulse rounded" role="status" aria-label={text('loading', 'Loading logs')}/> : error ? <div className="rounded-card border-border bg-panel border p-6"><h2 className="text-text mb-3 text-sm font-medium">{text('loadFailed', 'Unable to load logs')}</h2><button className="text-accent text-sm" type="button" onClick={onRetry}>{text('retry', 'Retry')}</button></div>
      : !selectedApplication ? <div className="rounded-card border-border bg-panel border border-dashed px-6 py-16 text-center"><h2 className="text-text mb-2 text-sm font-medium">{text('noApplications', 'No applications available')}</h2><p className="text-text-3 text-sm">{text('noApplicationsHelp', 'Create an application or ask an owner to share it with you.')}</p><Link to="/settings/api-docs?view=applications" className="text-accent mt-4 inline-block text-sm">{text('applications', 'Applications')}</Link></div>
      : <><div className="flex flex-wrap items-center gap-x-6 gap-y-3"><label className="text-text-2 flex items-center gap-3 text-xs">{text('app', 'Application')}<select className="rounded-control border-border-strong bg-panel h-8 max-w-64 border px-3 text-xs" aria-label={text('app', 'Application')} value={selectedApplication.pid} onChange={event => selectApplication(event.target.value)}>{readable.map(application => <option key={application.pid} value={application.pid}>{application.name}</option>)}</select></label>
        <label className="text-text-2 flex items-center gap-3 text-xs">{text('environment', 'Environment')}<select className="rounded-control border-border-strong bg-panel h-8 max-w-64 border px-3 text-xs" aria-label={text('environment', 'Environment')} value={installation?.pid ?? ''} disabled={!installations.length} onChange={event => selectInstallation(event.target.value)}>{installations.map(item => <option key={item.pid} value={item.pid}>{environmentLabel(item.environment)}{item.status !== 'active' ? ` · ${text('disabled', 'Disabled')}` : ''}{installations.filter(other => other.environment === item.environment).length > 1 ? ` · ${item.pid.slice(-6)}` : ''}</option>)}</select></label><span className="text-text-3 ms-auto text-xs">{text('isolation', 'Application and environment scoped · Tenant isolated')}</span></div>
        {!installation ? <p className="text-text-3 rounded-card border-border bg-panel border p-8 text-center text-sm">{text('noInstallation', 'This application has no installations. Install it before inspecting calls.')}</p>
          : view === 'calls' ? <OpenPlatformCallLogs key={installation.pid} installationPid={installation.pid} applicationName={selectedApplication.name} environment={environmentLabel(installation.environment)} canReadPayload={selectedApplication.permissions.manageRuntime}/>
          : <OpenPlatformOperationsPanel key={installation.pid} installationPid={installation.pid} deliveriesOnly/>}</>}
  </main>;
}
