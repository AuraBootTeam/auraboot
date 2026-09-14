import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ArrowPathIcon,
  ArrowTopRightOnSquareIcon,
  CheckIcon,
  ClipboardDocumentIcon,
  CodeBracketSquareIcon,
  KeyIcon,
  PlusIcon,
  ShieldCheckIcon,
  TrashIcon,
} from '@heroicons/react/24/outline';
import { useI18n } from '~/contexts/I18nContext';
import { useToastContext } from '~/contexts/ToastContext';

interface Capability {
  code: string;
  method: string;
  pathPattern: string;
  requiredScope: string;
  dataPolicy: string;
  schemaVersion: number;
}

interface Installation {
  pid: string;
  environment: 'development' | 'staging' | 'production';
  status: string;
  scopes: string[];
  rateLimitPerMinute: number;
  installedAt: string;
}

interface ExternalApplication {
  pid: string;
  name: string;
  description?: string;
  status: string;
  createdAt: string;
  installations: Installation[];
}

interface Credential {
  pid: string;
  clientId: string;
  status: string;
  createdAt: string;
  expiresAt?: string;
  lastUsedAt?: string;
}

interface CredentialSecret {
  credentialPid: string;
  clientId: string;
  clientSecret: string;
  createdAt: string;
  expiresAt?: string;
}

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok || String(body.code) !== '0') {
    throw new Error(body.message ?? `HTTP ${response.status}`);
  }
  return body.data as T;
}

export default function OpenPlatformPage() {
  const { t } = useI18n();
  const { showToast } = useToastContext();
  const [applications, setApplications] = useState<ExternalApplication[]>([]);
  const [capabilities, setCapabilities] = useState<Capability[]>([]);
  const [credentials, setCredentials] = useState<Record<string, Credential[]>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [appName, setAppName] = useState('');
  const [appDescription, setAppDescription] = useState('');
  const [creating, setCreating] = useState(false);
  const [installingApp, setInstallingApp] = useState<string | null>(null);
  const [environment, setEnvironment] = useState<Installation['environment']>('development');
  const [selectedScopes, setSelectedScopes] = useState<Set<string>>(new Set());
  const [secret, setSecret] = useState<CredentialSecret | null>(null);
  const [pendingRevoke, setPendingRevoke] = useState<{ installationPid: string; credential: Credential } | null>(null);
  const [editingScopes, setEditingScopes] = useState<Installation | null>(null);
  const [pendingDisable, setPendingDisable] = useState<
    { kind: 'application'; pid: string; name: string } | { kind: 'installation'; pid: string; name: string } | null
  >(null);

  const scopeCatalog = useMemo(
    () => [...new Set(capabilities.map((capability) => capability.requiredScope))].sort(),
    [capabilities],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [appData, capabilityData] = await Promise.all([
        apiFetch<ExternalApplication[]>('/api/open-platform/applications'),
        apiFetch<Capability[]>('/api/open-platform/capabilities'),
      ]);
      setApplications(appData ?? []);
      setCapabilities(capabilityData ?? []);
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : t('openPlatform.loadFailed', undefined, 'Unable to load Open Platform'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => {
    void load();
  }, [load]);

  const createApplication = async () => {
    if (!appName.trim()) return;
    setCreating(true);
    try {
      await apiFetch<ExternalApplication>('/api/open-platform/applications', {
        method: 'POST',
        body: JSON.stringify({ name: appName.trim(), description: appDescription.trim() || null }),
      });
      setShowCreate(false);
      setAppName('');
      setAppDescription('');
      showToast(t('openPlatform.applicationCreated', undefined, 'Application created'), 'success');
      await load();
    } catch (createError) {
      showToast(createError instanceof Error ? createError.message : t('openPlatform.createFailed', undefined, 'Unable to create application'), 'error');
    } finally {
      setCreating(false);
    }
  };

  const beginInstall = (applicationPid: string) => {
    setInstallingApp(applicationPid);
    setEnvironment('development');
    setSelectedScopes(new Set(scopeCatalog.slice(0, 1)));
  };

  const install = async () => {
    if (!installingApp || selectedScopes.size === 0) return;
    try {
      await apiFetch<Installation>(`/api/open-platform/applications/${installingApp}/installations`, {
        method: 'POST',
        body: JSON.stringify({ environment, scopes: [...selectedScopes] }),
      });
      setInstallingApp(null);
      showToast(t('openPlatform.installed', undefined, 'Application installed'), 'success');
      await load();
    } catch (installError) {
      showToast(installError instanceof Error ? installError.message : t('openPlatform.installFailed', undefined, 'Unable to install application'), 'error');
    }
  };

  const loadCredentials = async (installationPid: string) => {
    try {
      const result = await apiFetch<Credential[]>(`/api/open-platform/installations/${installationPid}/credentials`);
      setCredentials((current) => ({ ...current, [installationPid]: result ?? [] }));
    } catch (credentialError) {
      showToast(credentialError instanceof Error ? credentialError.message : t('openPlatform.credentialsFailed', undefined, 'Unable to load credentials'), 'error');
    }
  };

  const createCredential = async (installationPid: string) => {
    try {
      const created = await apiFetch<CredentialSecret>(`/api/open-platform/installations/${installationPid}/credentials`, { method: 'POST' });
      setSecret(created);
      await loadCredentials(installationPid);
    } catch (credentialError) {
      showToast(credentialError instanceof Error ? credentialError.message : t('openPlatform.credentialCreateFailed', undefined, 'Unable to create credential'), 'error');
    }
  };

  const revokeCredential = async () => {
    if (!pendingRevoke) return;
    const { installationPid, credential } = pendingRevoke;
    try {
      await apiFetch<void>(`/api/open-platform/installations/${installationPid}/credentials/${credential.pid}`, { method: 'DELETE' });
      setPendingRevoke(null);
      showToast(t('openPlatform.credentialRevoked', undefined, 'Credential revoked'), 'success');
      await loadCredentials(installationPid);
    } catch (revokeError) {
      showToast(revokeError instanceof Error ? revokeError.message : t('openPlatform.revokeFailed', undefined, 'Unable to revoke credential'), 'error');
    }
  };

  const beginEditScopes = (installation: Installation) => {
    setSelectedScopes(new Set(installation.scopes));
    setEditingScopes(installation);
  };

  const updateScopes = async () => {
    if (!editingScopes || selectedScopes.size === 0) return;
    try {
      await apiFetch<Installation>(`/api/open-platform/installations/${editingScopes.pid}/scopes`, {
        method: 'PUT', body: JSON.stringify({ scopes: [...selectedScopes] }),
      });
      setEditingScopes(null);
      showToast(t('openPlatform.scopesUpdated', undefined, 'Scopes updated; existing tokens were revoked'), 'success');
      await load();
    } catch (scopeError) {
      showToast(scopeError instanceof Error ? scopeError.message : t('openPlatform.scopeUpdateFailed', undefined, 'Unable to update scopes'), 'error');
    }
  };

  const disableTarget = async () => {
    if (!pendingDisable) return;
    const path = pendingDisable.kind === 'application'
      ? `/api/open-platform/applications/${pendingDisable.pid}`
      : `/api/open-platform/installations/${pendingDisable.pid}`;
    try {
      await apiFetch<void>(path, { method: 'DELETE' });
      setPendingDisable(null);
      showToast(t('openPlatform.disabled', undefined, 'Access disabled and active tokens revoked'), 'success');
      await load();
    } catch (disableError) {
      showToast(disableError instanceof Error ? disableError.message : t('openPlatform.disableFailed', undefined, 'Unable to disable access'), 'error');
    }
  };

  const copy = async (value: string) => {
    await navigator.clipboard.writeText(value);
    showToast(t('common.copied', undefined, 'Copied to clipboard'), 'success');
  };

  return (
    <main className="mx-auto max-w-6xl space-y-6 p-6" data-testid="open-platform-page">
      <header className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <div className="mb-2 inline-flex items-center gap-2 rounded-full bg-indigo-50 px-3 py-1 text-xs font-medium text-indigo-700 dark:bg-indigo-950 dark:text-indigo-300">
            <ShieldCheckIcon className="h-4 w-4" />
            {t('openPlatform.badge', undefined, 'Machine-to-machine access')}
          </div>
          <h1 className="text-2xl font-semibold text-gray-950 dark:text-white">{t('openPlatform.title', undefined, 'Open Platform')}</h1>
          <p className="mt-1 max-w-2xl text-sm text-gray-500 dark:text-gray-400">
            {t('openPlatform.subtitle', undefined, 'Create tenant-scoped applications, grant minimum scopes, rotate credentials, and inspect the public API contract.')}
          </p>
        </div>
        <div className="flex gap-2">
          <button type="button" onClick={() => window.open('/swagger-ui/index.html?urls.primaryName=open-platform', '_blank', 'noopener,noreferrer')} className="inline-flex items-center gap-2 rounded-lg border border-gray-300 bg-white px-3 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:bg-gray-900 dark:text-gray-200">
            <CodeBracketSquareIcon className="h-4 w-4" />
            {t('openPlatform.apiReference', undefined, 'API reference')}
            <ArrowTopRightOnSquareIcon className="h-4 w-4" />
          </button>
          <button type="button" onClick={() => setShowCreate(true)} className="inline-flex items-center gap-2 rounded-lg bg-indigo-600 px-3 py-2 text-sm font-medium text-white hover:bg-indigo-700" data-testid="open-platform-create-app">
            <PlusIcon className="h-4 w-4" />
            {t('openPlatform.newApplication', undefined, 'New application')}
          </button>
        </div>
      </header>

      <section className="grid gap-3 sm:grid-cols-3" aria-label={t('openPlatform.connectionFlow', undefined, 'Connection flow')}>
        {[
          ['1', t('openPlatform.step1', undefined, 'Create an application and installation')],
          ['2', t('openPlatform.step2', undefined, 'Grant explicit scopes and create a secret')],
          ['3', t('openPlatform.step3', undefined, 'Exchange credentials for a short-lived token')],
        ].map(([step, label]) => (
          <div key={step} className="rounded-xl border border-gray-200 bg-white p-4 dark:border-gray-800 dark:bg-gray-900">
            <span className="mb-2 flex h-6 w-6 items-center justify-center rounded-full bg-indigo-100 text-xs font-semibold text-indigo-700 dark:bg-indigo-900 dark:text-indigo-200">{step}</span>
            <p className="text-sm font-medium text-gray-800 dark:text-gray-200">{label}</p>
          </div>
        ))}
      </section>

      {loading ? (
        <div className="rounded-xl border border-gray-200 bg-white py-16 text-center text-sm text-gray-500 dark:border-gray-800 dark:bg-gray-900">{t('common.loading', undefined, 'Loading…')}</div>
      ) : error ? (
        <div className="rounded-xl border border-red-200 bg-red-50 p-6 text-sm text-red-700 dark:border-red-900 dark:bg-red-950 dark:text-red-300">
          <p>{error}</p>
          <button type="button" onClick={() => void load()} className="mt-3 inline-flex items-center gap-2 font-medium"><ArrowPathIcon className="h-4 w-4" />{t('common.retry', undefined, 'Retry')}</button>
        </div>
      ) : applications.length === 0 ? (
        <div className="rounded-xl border border-dashed border-gray-300 bg-white px-6 py-16 text-center dark:border-gray-700 dark:bg-gray-900" data-testid="open-platform-empty">
          <KeyIcon className="mx-auto h-10 w-10 text-gray-300" />
          <h2 className="mt-3 font-medium text-gray-900 dark:text-white">{t('openPlatform.emptyTitle', undefined, 'No external applications yet')}</h2>
          <p className="mt-1 text-sm text-gray-500">{t('openPlatform.emptyBody', undefined, 'Create the first application to issue tenant-bound credentials.')}</p>
        </div>
      ) : (
        <div className="space-y-4" data-testid="open-platform-application-list">
          {applications.map((application) => (
            <article key={application.pid} className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm dark:border-gray-800 dark:bg-gray-900">
              <div className="flex flex-col gap-3 border-b border-gray-100 p-5 sm:flex-row sm:items-center sm:justify-between dark:border-gray-800">
                <div>
                  <div className="flex items-center gap-2"><h2 className="font-semibold text-gray-950 dark:text-white">{application.name}</h2><StatusBadge status={application.status} /></div>
                  {application.description && <p className="mt-1 text-sm text-gray-500">{application.description}</p>}
                  <code className="mt-2 block text-xs text-gray-400">{application.pid}</code>
                </div>
                <div className="flex gap-2">
                  <button type="button" onClick={() => beginInstall(application.pid)} disabled={application.status !== 'active'} className="inline-flex items-center gap-2 self-start rounded-lg border border-indigo-200 px-3 py-2 text-sm font-medium text-indigo-700 hover:bg-indigo-50 disabled:opacity-40 dark:border-indigo-800 dark:text-indigo-300" data-testid={`open-platform-install-${application.pid}`}><PlusIcon className="h-4 w-4" />{t('openPlatform.addInstallation', undefined, 'Add installation')}</button>
                  {application.status === 'active' && <button type="button" onClick={() => setPendingDisable({ kind: 'application', pid: application.pid, name: application.name })} className="rounded-lg border border-red-200 px-3 py-2 text-sm font-medium text-red-700 hover:bg-red-50 dark:border-red-900 dark:text-red-300">{t('openPlatform.disable', undefined, 'Disable')}</button>}
                </div>
              </div>
              <div className="space-y-3 p-5">
                {application.installations.length === 0 ? <p className="text-sm text-gray-400">{t('openPlatform.noInstallations', undefined, 'No installations')}</p> : application.installations.map((installation) => (
                  <div key={installation.pid} className="rounded-lg border border-gray-200 p-4 dark:border-gray-700" data-testid={`open-platform-installation-${installation.pid}`}>
                    <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
                      <div><div className="flex items-center gap-2"><span className="text-sm font-medium capitalize text-gray-900 dark:text-white">{installation.environment}</span><StatusBadge status={installation.status} /></div><code className="mt-1 block text-xs text-gray-400">{installation.pid}</code></div>
                      <div className="flex flex-wrap gap-2">
                        <button type="button" onClick={() => void loadCredentials(installation.pid)} className="rounded-md border border-gray-300 px-2.5 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50 dark:border-gray-700 dark:text-gray-200">{t('openPlatform.viewCredentials', undefined, 'View credentials')}</button>
                        <button type="button" onClick={() => beginEditScopes(installation)} disabled={installation.status !== 'active'} className="rounded-md border border-gray-300 px-2.5 py-1.5 text-xs font-medium text-gray-700 hover:bg-gray-50 disabled:opacity-40 dark:border-gray-700 dark:text-gray-200">{t('openPlatform.editScopes', undefined, 'Edit scopes')}</button>
                        <button type="button" onClick={() => void createCredential(installation.pid)} disabled={installation.status !== 'active'} className="inline-flex items-center gap-1 rounded-md bg-gray-900 px-2.5 py-1.5 text-xs font-medium text-white hover:bg-gray-700 disabled:opacity-40 dark:bg-white dark:text-gray-900"><KeyIcon className="h-3.5 w-3.5" />{t('openPlatform.newCredential', undefined, 'New credential')}</button>
                        {installation.status === 'active' && <button type="button" onClick={() => setPendingDisable({ kind: 'installation', pid: installation.pid, name: installation.environment })} className="rounded-md p-2 text-red-600 hover:bg-red-50" aria-label={t('openPlatform.disableInstallation', undefined, 'Disable installation')}><TrashIcon className="h-4 w-4" /></button>}
                      </div>
                    </div>
                    <p className="mt-3 text-xs text-gray-400">{t('openPlatform.rateLimit', undefined, 'Rate limit')}: {installation.rateLimitPerMinute}/min</p>
                    <div className="mt-2 flex flex-wrap gap-1.5">{installation.scopes.map((scope) => <span key={scope} className="rounded-full bg-slate-100 px-2 py-1 font-mono text-[11px] text-slate-700 dark:bg-slate-800 dark:text-slate-200">{scope}</span>)}</div>
                    {credentials[installation.pid] && <CredentialList installationPid={installation.pid} credentials={credentials[installation.pid]} onRevoke={(credential) => setPendingRevoke({ installationPid: installation.pid, credential })} t={t} />}
                  </div>
                ))}
              </div>
            </article>
          ))}
        </div>
      )}

      {showCreate && <Dialog title={t('openPlatform.createTitle', undefined, 'Create external application')} onClose={() => setShowCreate(false)}><label className="block text-sm font-medium text-gray-700 dark:text-gray-200">{t('openPlatform.name', undefined, 'Application name')}<input value={appName} onChange={(event) => setAppName(event.target.value)} maxLength={160} autoFocus className="mt-1 w-full rounded-lg border border-gray-300 bg-white px-3 py-2 dark:border-gray-700 dark:bg-gray-950" data-testid="open-platform-app-name" /></label><label className="mt-4 block text-sm font-medium text-gray-700 dark:text-gray-200">{t('openPlatform.description', undefined, 'Description')}<textarea value={appDescription} onChange={(event) => setAppDescription(event.target.value)} maxLength={1000} rows={3} className="mt-1 w-full rounded-lg border border-gray-300 bg-white px-3 py-2 dark:border-gray-700 dark:bg-gray-950" /></label><DialogActions onCancel={() => setShowCreate(false)} onConfirm={() => void createApplication()} confirmDisabled={creating || !appName.trim()} confirmText={t('common.create', undefined, 'Create')} cancelText={t('common.cancel', undefined, 'Cancel')} /></Dialog>}

      {installingApp && <Dialog title={t('openPlatform.installTitle', undefined, 'Install application')} onClose={() => setInstallingApp(null)}><label className="block text-sm font-medium text-gray-700 dark:text-gray-200">{t('openPlatform.environment', undefined, 'Environment')}<select value={environment} onChange={(event) => setEnvironment(event.target.value as Installation['environment'])} className="mt-1 w-full rounded-lg border border-gray-300 bg-white px-3 py-2 dark:border-gray-700 dark:bg-gray-950"><option value="development">development</option><option value="staging">staging</option><option value="production">production</option></select></label><fieldset className="mt-4"><legend className="text-sm font-medium text-gray-700 dark:text-gray-200">{t('openPlatform.scopes', undefined, 'Scopes')}</legend><p className="mb-2 text-xs text-gray-500">{t('openPlatform.minimumScopes', undefined, 'Grant only the capabilities this installation needs.')}</p><div className="space-y-2">{scopeCatalog.map((scope) => <label key={scope} className="flex cursor-pointer items-center gap-2 rounded-lg border border-gray-200 p-3 text-sm dark:border-gray-700"><input type="checkbox" checked={selectedScopes.has(scope)} onChange={() => setSelectedScopes((current) => { const next = new Set(current); next.has(scope) ? next.delete(scope) : next.add(scope); return next; })} /><code>{scope}</code></label>)}</div></fieldset><DialogActions onCancel={() => setInstallingApp(null)} onConfirm={() => void install()} confirmDisabled={selectedScopes.size === 0} confirmText={t('openPlatform.install', undefined, 'Install')} cancelText={t('common.cancel', undefined, 'Cancel')} /></Dialog>}

      {secret && <Dialog title={t('openPlatform.secretTitle', undefined, 'Save this credential now')} onClose={() => setSecret(null)}><div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-900 dark:bg-amber-950 dark:text-amber-200">{t('openPlatform.secretWarning', undefined, 'The client secret is shown once. AuraBoot stores only its hash.')}</div><SecretRow label="client_id" value={secret.clientId} onCopy={copy} copyLabel={t('common.copy', undefined, 'Copy')} /><SecretRow label="client_secret" value={secret.clientSecret} onCopy={copy} copyLabel={t('common.copy', undefined, 'Copy')} /><DialogActions onCancel={() => setSecret(null)} onConfirm={() => setSecret(null)} confirmText={t('openPlatform.saved', undefined, 'I saved it')} cancelText={t('common.cancel', undefined, 'Cancel')} hideCancel /></Dialog>}

      {pendingRevoke && <Dialog title={t('openPlatform.revokeTitle', undefined, 'Revoke credential?')} onClose={() => setPendingRevoke(null)}><p className="text-sm text-gray-600 dark:text-gray-300">{t('openPlatform.revokeWarning', undefined, 'Existing access tokens issued by this credential will stop working immediately.')}</p><code className="mt-3 block rounded bg-gray-100 p-2 text-xs dark:bg-gray-800">{pendingRevoke.credential.clientId}</code><DialogActions onCancel={() => setPendingRevoke(null)} onConfirm={() => void revokeCredential()} confirmText={t('openPlatform.revoke', undefined, 'Revoke')} cancelText={t('common.cancel', undefined, 'Cancel')} destructive /></Dialog>}

      {editingScopes && <Dialog title={t('openPlatform.editScopes', undefined, 'Edit scopes')} onClose={() => setEditingScopes(null)}><p className="text-sm text-amber-700 dark:text-amber-300">{t('openPlatform.scopeRevokeWarning', undefined, 'Saving a new scope set immediately revokes existing access tokens for this installation.')}</p><div className="mt-4 space-y-2">{scopeCatalog.map((scope) => <label key={scope} className="flex cursor-pointer items-center gap-2 rounded-lg border border-gray-200 p-3 text-sm dark:border-gray-700"><input type="checkbox" checked={selectedScopes.has(scope)} onChange={() => setSelectedScopes((current) => { const next = new Set(current); next.has(scope) ? next.delete(scope) : next.add(scope); return next; })} /><code>{scope}</code></label>)}</div><DialogActions onCancel={() => setEditingScopes(null)} onConfirm={() => void updateScopes()} confirmDisabled={selectedScopes.size === 0} confirmText={t('common.save', undefined, 'Save')} cancelText={t('common.cancel', undefined, 'Cancel')} /></Dialog>}

      {pendingDisable && <Dialog title={t('openPlatform.disableTitle', undefined, 'Disable access?')} onClose={() => setPendingDisable(null)}><p className="text-sm text-gray-600 dark:text-gray-300">{t('openPlatform.disableWarning', undefined, 'This immediately revokes active tokens. The action cannot be undone from this screen.')}</p><code className="mt-3 block rounded bg-gray-100 p-2 text-xs dark:bg-gray-800">{pendingDisable.name}</code><DialogActions onCancel={() => setPendingDisable(null)} onConfirm={() => void disableTarget()} confirmText={t('openPlatform.disable', undefined, 'Disable')} cancelText={t('common.cancel', undefined, 'Cancel')} destructive /></Dialog>}
    </main>
  );
}

function CredentialList({ installationPid, credentials, onRevoke, t }: { installationPid: string; credentials: Credential[]; onRevoke: (credential: Credential) => void; t: (key: string, params?: Record<string, any>, fallback?: string) => string }) {
  return <div className="mt-4 border-t border-gray-100 pt-3 dark:border-gray-800" data-testid={`open-platform-credentials-${installationPid}`}>{credentials.length === 0 ? <p className="text-xs text-gray-400">{t('openPlatform.noCredentials', undefined, 'No credentials')}</p> : <div className="space-y-2">{credentials.map((credential) => <div key={credential.pid} className="flex items-center justify-between rounded bg-gray-50 px-3 py-2 dark:bg-gray-950"><div><code className="text-xs text-gray-700 dark:text-gray-200">{credential.clientId}</code><div className="mt-1 flex items-center gap-2"><StatusBadge status={credential.status} />{credential.lastUsedAt && <span className="text-[11px] text-gray-400">{t('openPlatform.lastUsed', undefined, 'Last used')} {new Date(credential.lastUsedAt).toLocaleString()}</span>}</div></div>{credential.status === 'active' && <button type="button" onClick={() => onRevoke(credential)} className="rounded p-2 text-red-600 hover:bg-red-50" aria-label={t('openPlatform.revoke', undefined, 'Revoke')}><TrashIcon className="h-4 w-4" /></button>}</div>)}</div>}</div>;
}

function StatusBadge({ status }: { status: string }) {
  const active = status === 'active' || status === 'success';
  return <span className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[11px] font-medium ${active ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-300' : 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300'}`}>{active && <CheckIcon className="h-3 w-3" />}{status}</span>;
}

function SecretRow({ label, value, onCopy, copyLabel }: { label: string; value: string; onCopy: (value: string) => Promise<void>; copyLabel: string }) {
  return <div className="mt-4"><span className="text-xs font-medium text-gray-500">{label}</span><div className="mt-1 flex items-center gap-2 rounded-lg bg-gray-950 p-3 text-white"><code className="min-w-0 flex-1 break-all text-xs">{value}</code><button type="button" onClick={() => void onCopy(value)} className="rounded p-1.5 hover:bg-gray-800" aria-label={`${copyLabel} ${label}`}><ClipboardDocumentIcon className="h-4 w-4" /></button></div></div>;
}

function Dialog({ title, onClose, children }: { title: string; onClose: () => void; children: React.ReactNode }) {
  return <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4" role="presentation" onMouseDown={(event) => event.target === event.currentTarget && onClose()}><section role="dialog" aria-modal="true" aria-label={title} className="max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-xl bg-white p-5 shadow-xl dark:bg-gray-900"><h2 className="text-lg font-semibold text-gray-950 dark:text-white">{title}</h2><div className="mt-4">{children}</div></section></div>;
}

function DialogActions({ onCancel, onConfirm, confirmText, cancelText, confirmDisabled, destructive, hideCancel }: { onCancel: () => void; onConfirm: () => void; confirmText: string; cancelText: string; confirmDisabled?: boolean; destructive?: boolean; hideCancel?: boolean }) {
  return <div className="mt-6 flex justify-end gap-2">{!hideCancel && <button type="button" onClick={onCancel} className="rounded-lg border border-gray-300 px-3 py-2 text-sm font-medium text-gray-700 dark:border-gray-700 dark:text-gray-200">{cancelText}</button>}<button type="button" onClick={onConfirm} disabled={confirmDisabled} className={`rounded-lg px-3 py-2 text-sm font-medium text-white disabled:opacity-40 ${destructive ? 'bg-red-600 hover:bg-red-700' : 'bg-indigo-600 hover:bg-indigo-700'}`}>{confirmText}</button></div>;
}
